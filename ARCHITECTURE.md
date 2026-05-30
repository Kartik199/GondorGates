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
| **Non-blocking I/O** | The application uses Project Reactor (`Mono`/`Flux`) operators exclusively. The calling thread is never parked waiting for Redis — it is released back to handle other connections while the Redis response is in flight. |
| **Horizontal scalability** | GondorGates application instances are stateless. Add more instances behind a load balancer; all share the same Redis state. |
| **Fail-open resiliency** | If Redis is unreachable, requests are allowed through. Rate limiting is a protection mechanism, not a gating mechanism — API availability is the higher priority. |
| **Configuration-driven** | Policies are declared in YAML and version-controlled alongside the code. Changing a policy currently requires a restart. Hot reload (applying changes without restart) is a planned feature. Zero hardcoded limits in source code. |

### The token bucket model

Each policy dimension maintains a Redis Hash with two fields:

- `tokens` — current available tokens
- `last_refill` — epoch milliseconds of the last successful grant

On every request, the Lua script computes `floor(elapsed_ms × refillRate / 1000)` new tokens (capped at `capacity`), adds them, then attempts to consume one. This is a **grant-gated clock** variant of the token bucket: `last_refill` advances only on a successful grant, not on every request. A denied request still applies the refill calculation (tokens is updated) but leaves `last_refill` frozen at the last grant time. The consequence is that each successive denied request computes `elapsed` from the same anchor — growing with every retry — so refill credit accumulates across the denial window rather than being anchored to each individual attempt. In practice this means a heavily rate-limited client recovers their budget slightly faster than the nominal rate would suggest; the effect is bounded by `capacity` and cannot exceed the configured ceiling, making it an acceptable tradeoff. The benefit is a single timestamp per bucket, no background job, and state that is self-consistent across Redis restarts with AOF persistence.

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

**Update (Epic 9):** A hybrid model was adopted. YAML remains the authoritative baseline — version-controlled, deployed with the image, always present as a fallback. Redis overlays runtime overrides on top: `PolicyResolver` checks Redis first on every request (exact-match lookup, zero extra cost on a cache hit), falling back to YAML if no override exists. This preserves the original decision's benefits (diff-able defaults, no schema migration) while adding live reconfiguration without restart via the admin REST API.

### Trusted-header identity model

`ClientIdentityResolver` reads `X-User-Id` and `X-API-Key` directly from the incoming request headers. GondorGates does not validate, sign, or verify these values — any caller that can reach the service can supply an arbitrary header and be rate-limited under that identity. This is an explicit design constraint, not an oversight: GondorGates is intended to run behind an authentication layer (an API gateway, ingress controller, or service mesh) that strips client-supplied identity headers and injects verified ones from a validated JWT claim, session token, or mTLS certificate. Deploying GondorGates as a public-facing endpoint without that stripping layer makes the USER and API_KEY dimensions trivially bypassable.

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

### Epic 7 — Grafana Dashboard ✅

**Objective**: Provide a real-time operational view of traffic patterns.

**Delivered panels:**
- Requests/sec by endpoint (allowed vs. denied)
- Top denied dimensions (which USER/IP is hitting limits)
- Redis eval latency (P50 / P95 / P99)
- Denied rate over time (spikes indicate abuse or misconfigured clients)

**Delivered:**
- Grafana and Prometheus services added to `docker-compose.yml`
- Prometheus scrape job configured against GondorGates `/actuator/prometheus`
- Dashboard auto-provisioned via `grafana/provisioning/` — no manual import required
- Alert rule provisioned via `grafana/provisioning/alerting/gondor-alerts.yml` — fires a critical alert when `rate(gondor_redis_errors_total[1m]) > 0` (Redis fail-open triggered, all rate limits suspended)

---

### Epic 8 — Deployment & Product Readiness ✅

**Objective**: Single-command startup of the full stack; demonstrable under load.

**Deployment architecture:**
```
Client
  ↓
GondorGates (:8080)          ← rate limiting filter; runs in embedded mode (no backend proxy)
  ↓
Redis (:6379)                ← token bucket state

Prometheus (:9091)           ← scrapes GondorGates /actuator/prometheus every 5s
  ↓
Grafana (:3000)              ← anonymous viewer access, auto-provisioned dashboard
```

Allowed requests return 200 from Spring actuator paths or 404 for unmatched routes — the rate-limit
headers (`X-RateLimit-Remaining`, `Retry-After`) are the observable behaviour. For proxy mode,
see `docker-compose.sidecar-example.yml`.

**Delivered:**
- Multi-stage `Dockerfile` — Maven build layer cached separately from app layer; distroless Java 21 runtime image (no shell, reduced attack surface, ~200MB final image)
- `docker-compose.yml` — single `docker compose up -d --build` starts four services with health-check dependency ordering (Redis → gondor-app → Prometheus → Grafana)
- `BackendProxyHandler` — transparent WebClient proxy activated by `BACKEND_URL` env var; strips hop-by-hop headers, forwards method/path/query/body, streams response back. When `BACKEND_URL` is blank, filter falls through to `chain.filter()` unchanged (embedded mode)
- k6 load test (`k6/load-test.js`) — two scenarios run concurrently:
  - **Correctness**: 20 VUs share one user ID against `/api/login`; `gondor_allowed ≤ 5` threshold proves the Lua atomic eval has no double-spend race condition under concurrent load
  - **Throughput**: ramp 1 → 100 VUs against `/api/orders`; records real P95 filter latency; asserts at least one 429 fires under GLOBAL bucket pressure
- `.github/workflows/load-test.yml` — `workflow_dispatch` workflow: builds full stack, waits for health, runs k6 via `grafana/k6-action`, uploads `k6-results.json` as a 30-day artifact
- `.env.example` — documents `GRAFANA_ADMIN_PASSWORD`; `.env` gitignored; anonymous access means admin password is not needed for day-to-day use

### Epic 8b — Sidecar UX & Image Publishing ✅

**Objective**: Zero-friction adoption — any team adds GondorGates to their stack in under 5 minutes with no code changes.

**Delivered:**
- `publish.yml` GitHub Actions workflow — builds and pushes `ghcr.io/kartik199/gondorgates:latest` (+ short SHA tag) to GitHub Container Registry on every merge to main
- `docker-compose.sidecar-example.yml` — minimum 2-container template (GondorGates + Redis) consumers copy into their project; `BACKEND_URL` is the only required change
- Environment variable policy configuration — Spring Boot's `GONDORGATES_POLICIES_{n}_*` override pattern documented so users configure limits without mounting files or rebuilding
- README "5-minute setup" section — complete end-to-end consumer guide

---

### Epic 9 — Admin REST API ✅

**Objective**: Change rate limit policies on a live instance without restarting.

**Delivered:**
- `GET /admin/policies` — lists all active policies (YAML defaults merged with Redis overrides)
- `POST /admin/policies` — creates or updates a policy, written to Redis, effective on the next request
- `DELETE /admin/policies/{path}` — removes a runtime override; falls back to YAML default
- `PolicyResolver` checks Redis overrides before YAML on every request (exact-match, zero performance cost on cache hit)
- Static `X-Admin-Token` header auth — disabled by default (returns 503); enabled by setting `GONDORGATES_ADMIN_TOKEN` env var
- `RedisPolicyStore` loads overrides from Redis at startup so policies survive restarts
- `PolicyStore` interface extracted from `RedisPolicyStore` — `PolicyResolver` and `AdminPolicyController` depend on the interface, not the Redis implementation (DIP)
- `POST /admin/policies` body validation via `@Valid` + JSR-380 constraints (`@NotBlank`, `@NotEmpty`, `@NotNull`, `@Positive`) — replaces manual `isValid()` check; invalid payloads return 400 with per-field constraint details

---

### Epic 10 — Benchmark ✅

**Objective**: Documented, reproducible proof of performance with real overhead numbers.

**Delivered:**
- k6 load test fixed for k6 v2.0 compatibility: shared correctness user via `setup()`, `responseCallback` to exclude expected 429s from failure rate, Go-canonical header casing
- Real threshold values: `p(95)<50ms` replaces the fictional `p(95)<500ms` placeholder
- Baseline scenario added: same 1→100 VU ramp and 100ms sleep as throughput, targeting `/actuator/info` — WebFilter bails out immediately for `/actuator` paths and `/actuator/info` makes no Redis calls (unlike `/actuator/health` which pings Redis via the health indicator); matching the VU profile and sleep pacing makes P95 values directly comparable
- Throughput scenario runs after baseline (startTime offset) to keep measurements isolated
- Measured results on local Docker (Apple Silicon): baseline P95 ~9ms, throughput P95 ~14ms — GondorGates overhead ~5ms per request (one Redis Lua round-trip for the atomic token bucket eval)
- Correctness verified: exactly 5 requests allowed out of 40 concurrent against a capacity-5 USER bucket — no double-spend under load
- README Performance section documents baseline methodology and overhead derivation

---

## Code quality pass (post-epic)

Applied after all epics were complete. No behaviour changes — correctness improvements and design tightening only.

| Area | Change |
|---|---|
| DIP | `PolicyStore` interface extracted; `PolicyResolver` and `AdminPolicyController` depend on the interface, not `RedisPolicyStore` directly |
| Logging | `System.out.println` in `PolicyResolver` replaced with SLF4J `log.info` — output now routed through the logging framework |
| Validation | `@NotBlank`, `@NotEmpty`, `@NotNull`, `@Positive` constraints added to `RateLimitPolicy` and `DimensionPolicy`; `@Valid` on `AdminPolicyController` `@RequestBody` replaces manual `isValid()` |
| Semantics | `@Configuration` on `GondorGatesProperties` replaced with `@Component` — the class is a bean, not a `@Bean`-producing configuration class |
| Readability | `Long.MAX_VALUE` sentinel in `RateLimitDecision` replaced with named constant `UNCONSTRAINED`; `@SuppressWarnings("rawtypes")` in `RedisRateLimiter` annotated with explanation; `beforeCommit` pattern in `GondorGatesWebFilter` documented with a comment |
| Build | macOS Netty DNS resolver dependency moved to a `<profile id="mac-dev">` activated by OS — no longer ships in the production Docker image |
| Dependencies | `.github/dependabot.yml` added — weekly automated updates for Maven and GitHub Actions |

---

## Post-MVP ideas

### Abuse detection & auto-blocking
Detect sustained violation patterns (e.g. 500 denied requests in 60 seconds from one IP) and temporarily blacklist the source. Implemented as a separate `AbuseDetector` component that watches the denied counter and writes to a Redis blocklist checked at the top of the filter.

### Sliding window strategy
Alternative to token bucket for clients that want strictly "N requests per minute" semantics (no burst tolerance). Implemented as a second Lua script, selectable per policy via `strategy: SLIDING_WINDOW`.

### Multi-tenant isolation
Tenant-aware key namespacing (`rate_limit:{tenantId}:{dimension}:{id}:{path}`) so the same GondorGates instance can serve multiple products with isolated budgets.

### Admin API authentication hardening
The current `X-Admin-Token` static header has no expiry and no per-caller identity. Options: (a) mTLS — mutual certificate auth, no token required; (b) short-lived JWT issued by a separate identity service; (c) at minimum, a token rotation mechanism that takes effect without restart. The current implementation is correctly disabled-by-default (returns 503 without a token), which limits blast radius, but the static-token model is not appropriate for production use where the token controls all rate limit policy.

### Redis high availability
Redis is a single point of failure in v1. The application layer is stateless and horizontally scalable, but all rate-limit state lives in one Redis node — a restart or crash causes fail-open (all rate limits suspended) and cold-start empty buckets (counters reset). Fail-open is an intentional design choice: rate limiting is a protection mechanism, not a gating mechanism, so API availability is the higher priority during the outage window. The two standard HA paths are Redis Sentinel (auto-failover with a primary + replicas, transparent to clients via the Sentinel-aware connection string) and Redis Cluster (horizontal sharding across nodes, but requires key-slot-aware key design). The current key format `rate_limit:{dimension}:{id}:{path}` crosses hash slots arbitrarily; adopting Redis hash tags — e.g. `rate_limit:{user:kartik}:/api/orders` — would pin all keys for a given identity to one slot and make the Lua script Cluster-compatible. Both Sentinel and Cluster are out of scope for v1; fail-open covers the downtime window.

### OpenAPI / Swagger documentation
The admin REST API (`GET/POST/DELETE /admin/policies`) has no machine-readable spec. Adding `springdoc-openapi` would auto-generate a Swagger UI at `/swagger-ui.html` and an OpenAPI JSON at `/v3/api-docs`, making the API self-documenting and testable via the browser.

### GraalVM Native Image
Compile to a native binary for sub-second startup and ~50MB image size. Suitable for serverless or auto-scaling-from-zero deployments.

