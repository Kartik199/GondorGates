---
title: Architecture
nav_order: 2
description: Technical vision, architectural decisions, and engineering history
---

# GondorGates

## What GondorGates is

GondorGates is a distributed API traffic-control gateway built to enforce globally consistent rate limits across horizontally scaled backend services. It is designed as middleware: it sits on the request hot path, evaluates one atomic Redis operation per policy dimension in sequence, and either passes the request through or returns a `429 Too Many Requests` with retry metadata.

It is not a service mesh, not a full API gateway, and not a quota-billing system. It is a purpose-built, reactive rate limiter that is correct under concurrency, configurable without redeployment, and resilient to its own backing store going down.

---

## Technical Vision

### Non-negotiable properties

| Property | How it is achieved |
|---|---|
| **Distributed correctness** | All token bucket state lives in Redis. A single atomic Lua script performs read → refill → decide → write in one round-trip. No application-level locking, no CAS retry loop at the Java layer. |
| **Non-blocking I/O** | The application uses Project Reactor (`Mono`/`Flux`) operators exclusively. The calling thread is never parked waiting for Redis — it is released back to handle other connections while the Redis response is in flight. Netty is the embedded server provided by Spring Boot's auto-configuration; the application code makes no direct reference to Netty. |
| **Horizontal scalability** | GondorGates application instances are stateless. Add more instances behind a load balancer; all share the same Redis state. |
| **Fail-open resiliency** | If Redis is unreachable, requests are allowed through. Rate limiting is a protection mechanism, not a gating mechanism — API availability is the higher priority. |
| **Configuration-driven** | Policies are declared in YAML and version-controlled alongside the code. Changing a policy currently requires a restart. Hot reload (applying changes without restart) is a planned feature. Zero hardcoded limits in source code. |

### The token bucket model

Each policy dimension maintains a Redis Hash with two fields:

- `tokens` — current available tokens
- `last_refill` — epoch milliseconds of the last successful grant

On every request, the Lua script computes `floor(elapsed_ms × refillRate / 1000)` new tokens (capped at `capacity`), adds them, then attempts to consume one. Refill only advances on a successful grant — this "lazy refill" approach means no background job is ever needed, and bucket state stays self-consistent even across Redis restarts with AOF persistence.

### Multi-dimensional evaluation

A policy can enforce multiple independent budgets for the same endpoint. Dimensions are evaluated in declaration order. The first denial short-circuits the evaluation — no further Redis calls are made, no further buckets are charged. The response exposes the most restrictive remaining count seen across all evaluated dimensions.

```
GLOBAL (shared by all callers)
  ↓ allowed
USER   (per X-User-Id)
  ↓ denied → stop, return 429
  (IP and API_KEY buckets are never charged for this request)
```

Redis key format: `rate_limit:{dimension}:{id}:{path}`
Examples:
- `rate_limit:global:GLOBAL:/api/login`
- `rate_limit:user:kartik:/api/login`
- `rate_limit:ip:10.0.0.5:/api/orders`

---

## Architecture decisions

### Spring WebFlux over Spring MVC

GondorGates sits on the hot path of every request. Spring MVC assigns one thread per request; that thread blocks on the Redis call. At 1000 concurrent requests and a 2ms Redis latency, MVC needs 1000 threads pinned simultaneously — hundreds of megabytes of stack, plus context-switch overhead at scale. Spring WebFlux uses Reactor operators (`Mono`/`Flux`) and non-blocking I/O: the calling thread is released back while waiting for Redis and can serve other connections in the meantime. The application code makes no direct reference to Netty — it is the embedded server provided by Spring Boot's auto-configuration when `spring-boot-starter-webflux` is on the classpath. This is not premature optimisation — it is the correct architecture for middleware that is always in the critical path.

### Redis Lua over application-level locking

Distributed locks (Redlock, etc.) are expensive and complex. The alternative — GET then SET — races: two concurrent requests can both read `tokens=1`, both grant, both write `tokens=0`, leaking one token. Lua scripts execute atomically on the Redis thread. The entire evaluate-and-mutate operation is linearizable without any application-layer coordination.

### Fail-open over fail-closed

When Redis goes down, a fail-closed system returns 429 to every request — effectively a self-inflicted DDoS. GondorGates chooses availability: if the rate limiter cannot function, it steps aside. This is the correct tradeoff for an infrastructure layer that protects services from overload; when the protection is unavailable, the underlying services are still better off receiving traffic than being blanket-blocked.

### Single Lua script over multi-key transactions

MULTI/EXEC in Redis does not support conditional logic. Lua gives us the ability to read, compute, and write conditionally in one atomic operation. It also avoids the round-trip overhead of pipeline setup. The script is loaded once at startup (SHA-based) and cached by Redis.

### YAML policy configuration over database-backed policies

For v1, simplicity wins. YAML configuration is version-controlled, diff-able in PRs, and deployable via standard CI/CD. A database-backed policy store (with an admin API) is on the post-MVP roadmap but adds significant operational surface area that is not warranted until dynamic policy management is a real product requirement.

---

## Epic history

### Epic 0 — Infrastructure ✅
Spring Boot 3.4 / WebFlux skeleton, Netty server, Dockerised Redis 7.2 with AOF and health check, GitHub Actions CI with Redis service container, `/actuator/health` endpoint, `HealthCheckIT`.

### Epic 1 — Core Rate Limiting Engine ✅
`RateLimiter` interface, `RateLimitDecision` record (`allowed`, `remainingTokens`, `retryAfter`), `TokenBucket` Java model with lazy refill and CAS loop, `RateLimitKeyUtils` key builder.

### Epic 2 — Distributed Redis Backend ✅
`token_bucket.lua` atomic Lua script, `RedisRateLimiter` executing the script via `ReactiveStringRedisTemplate`, fail-open `onErrorResume`, `RedisRateLimiterIT` validating distributed consistency.

### Epic 3 — WebFilter & HTTP Integration ✅
`GondorGatesWebFilter` at `@Order(-100)`, `ClientIdentityResolver` (X-API-Key → X-User-Id → IP → anonymous), HTTP 429 response with JSON body, `X-RateLimit-Remaining` and `Retry-After` response headers, `GondorGatesWebFilterIT`.

Key bug fixed: `Mono<Void>` always completes empty, so `switchIfEmpty` was firing on every request (double filter-chain invocation). Resolved by replacing the reactive chain with a synchronous null-check, which is correct since `PolicyResolver.resolve()` is synchronous.

### Epic 4 — Policy Engine ✅
`RateLimitPolicy` POJO, `GondorGatesProperties` with `@ConfigurationProperties(prefix = "gondorgates")`, `PolicyResolver` with longest-prefix-wins path matching, four policies in `application.yml` (login, orders, test, catch-all).

Key bug fixed: `startsWith("/api/order")` falsely matched `/api/orders`. Corrected to `startsWith(policyPath + "/")`.

### Epic 5 — Multi-Dimensional Rate Limiting ✅
`RateLimitDimension` enum (GLOBAL, USER, IP, API_KEY), `DimensionPolicy` POJO (type + capacity + refillRate), `RateLimitPolicy` migrated from flat fields to `List<DimensionPolicy>`, `ClientIdentityResolver.resolveForDimension()`, `GondorGatesWebFilter` refactored to evaluate dimensions serially via `Flux.concatMap` + `takeUntil` + `reduce`, `RateLimitKeyUtils` now active. All policies in YAML restructured to dimension format.

### Epic 6 — Observability ✅
`micrometer-registry-prometheus` dependency added; `/actuator/prometheus` endpoint exposed. `GondorGatesWebFilter` instrumented with `gondor.requests.total` counter (tags: `path`, `outcome`), `gondor.filter.duration` timer (tags: `path`, `outcome`), and `gondor.bucket.remaining` gauge (tags: `path`, `dimension`) — registered once per `(dimension, path)` pair via `ConcurrentHashMap<String, AtomicLong>` and updated on every request. `RedisRateLimiter` instrumented with `gondor.redis.eval.duration` timer and `gondor.redis.errors.total` counter. `PrometheusMetricsIT` validates all four metrics are recorded on a real request.

---

## Remaining roadmap

### Epic 7 — Grafana Dashboard ⏳

**Objective**: Provide a real-time operational view of traffic patterns.

**Planned panels:**
- Requests/sec by endpoint (allowed vs. denied)
- Top denied dimensions (which USER/IP is hitting limits)
- Redis eval latency (P50 / P95 / P99)
- Denied rate over time (spikes indicate abuse or misconfigured clients)

**Planned work:**
- Add Grafana and Prometheus services to `docker-compose.yml`
- Configure Prometheus scrape job pointing at GondorGates `/actuator/prometheus`
- Commit a `grafana/dashboard.json` that can be imported directly

---

### Epic 8 — Deployment & Product Readiness ✅

**Objective**: Single-command startup of the full stack; demonstrable under load.

**Deployment architecture:**
```
Client
  ↓
GondorGates (:8080)          ← rate limiting filter + optional proxy to backend
  ↓
Demo Backend (nginx :9090)   ← returns {"status":"ok"} for /api/login, /api/orders
  ↓
Redis (:6379)                ← token bucket state

Prometheus (:9091)           ← scrapes GondorGates /actuator/prometheus every 5s
  ↓
Grafana (:3000)              ← anonymous viewer access, auto-provisioned dashboard
```

**Delivered:**
- Multi-stage `Dockerfile` — Maven build layer cached separately from app layer; Alpine JRE runtime image
- `docker-compose.full.yml` — single `docker compose up -d --build` starts all five services with health-check dependency ordering (Redis → gondor-app → Prometheus → Grafana)
- `BackendProxyHandler` — transparent WebClient proxy activated by `BACKEND_URL` env var; strips hop-by-hop headers, forwards method/path/query/body, streams response back. When `BACKEND_URL` is blank, filter falls through to `chain.filter()` unchanged (embedded mode)
- k6 load test (`k6/load-test.js`) — two scenarios run concurrently:
  - **Correctness**: 20 VUs share one user ID against `/api/login`; `gondor_allowed ≤ 5` threshold proves the Lua atomic eval has no double-spend race condition under concurrent load
  - **Throughput**: ramp 1 → 100 VUs against `/api/orders`; records real P95 filter latency; asserts at least one 429 fires under GLOBAL bucket pressure
- `.github/workflows/load-test.yml` — `workflow_dispatch` workflow: builds full stack, waits for health, runs k6 via `grafana/k6-action`, uploads `k6-results.json` as a 30-day artifact
- `.env.example` — documents `GRAFANA_ADMIN_PASSWORD`; `.env` gitignored; anonymous access means admin password is not needed for day-to-day use

---

## Post-MVP ideas

These are intentionally deferred until all epics complete.

### Runtime policy management
Admin REST API (`POST /admin/policies`, `PUT /admin/policies/{path}`) to change limits without restart. Backed by a Redis-persisted policy store that takes precedence over YAML on startup.

### Abuse detection & auto-blocking
Detect sustained violation patterns (e.g. 500 denied requests in 60 seconds from one IP) and temporarily blacklist the source. Implemented as a separate `AbuseDetector` component that watches the denied counter and writes to a Redis blocklist checked at the top of the filter.

### Sliding window strategy
Alternative to token bucket for clients that want strictly "N requests per minute" semantics (no burst tolerance). Implemented as a second Lua script, selectable per policy via `strategy: SLIDING_WINDOW`.

### Multi-tenant isolation
Tenant-aware key namespacing (`rate_limit:{tenantId}:{dimension}:{id}:{path}`) so the same GondorGates instance can serve multiple products with isolated budgets.

### X-RateLimit-Reset header
Epoch seconds when the current bucket fully refills. Requires passing `capacity` and `refillRate` back from the Lua return value or computing it from `retryAfter`.

