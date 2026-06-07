---
title: Architecture
nav_order: 3
description: Technical design, architectural decisions, and engineering history
---

# GondorGates — Architecture

## What GondorGates is

GondorGates is a distributed API rate-limiting gateway built to enforce globally consistent traffic limits across horizontally scaled backend services. It is middleware: it sits on the request hot path, evaluates one atomic Redis operation per policy dimension, and either passes the request through or returns `429 Too Many Requests` with retry metadata.

It is **not** a service mesh, a full API gateway, or a quota-billing system. It is a purpose-built, reactive rate limiter that is correct under concurrency, configurable without redeployment, and resilient to Redis going down.

---

## Non-negotiable properties

| Property | How it is achieved |
|---|---|
| **Distributed correctness** | All token bucket state lives in Redis. A single atomic Lua script performs read → refill → decide → write in one round-trip. No application-level locking, no CAS retry loop at the Java layer. |
| **Non-blocking I/O** | The application uses Project Reactor (`Mono`/`Flux`) exclusively. The calling thread is never parked waiting for Redis — it is released back to the event loop while the Redis response is in flight. |
| **Horizontal scalability** | GondorGates application instances are stateless. Add more instances behind a load balancer; all share the same Redis state. |
| **Fail-open resiliency** | If Redis is unreachable, requests are allowed through. Rate limiting is a protection mechanism, not a gate — API availability is the higher priority. |
| **Configuration-driven** | Policies are declared in YAML and version-controlled alongside the code. A two-tier model (YAML baseline + Redis runtime overrides) allows live reconfiguration without restart via the Admin REST API. |

---

## Component reference

| Component | Package | Role |
|---|---|---|
| `GondorGatesWebFilter` | `filter` | Spring WebFlux `WebFilter` at `@Order(-100)`. Intercepts every non-admin, non-actuator request. Resolves policy, evaluates dimensions serially, writes response headers, proxies or delegates. |
| `AdminAuthWebFilter` | `admin` | `WebFilter` at `@Order(-99)`. Guards all `/admin/*` paths. Returns `503` when `GONDORGATES_ADMIN_TOKEN` is unset; `401` for an invalid token. Uses constant-time comparison (`MessageDigest.isEqual`) to prevent timing attacks. |
| `PolicyResolver` | `policy` | On each request, checks the `PolicyStore` for a Redis override (exact path match) first, then falls through to the YAML policies sorted by path length descending (longest-prefix wins). |
| `RedisPolicyStore` | `admin` | In-memory `ConcurrentHashMap` cache of admin overrides, populated at startup via `@PostConstruct` from Redis. `save` and `delete` update Redis and the cache atomically. Implements the `PolicyStore` interface. |
| `ClientIdentityResolver` | `filter` | Extracts the identity string for a dimension: `GLOBAL` → `"GLOBAL"`, `USER` → `X-User-Id` header (falls back to `"anonymous"`), `IP` → remote address (falls back to `"unknown_ip"`), `API_KEY` → `X-API-Key` header (falls back to `"anonymous"`). |
| `RedisRateLimiter` | `engine` | Executes `token_bucket.lua` via `ReactiveStringRedisTemplate`. On Redis error, increments `gondor.redis.errors.total` and returns an allow decision (fail-open). |
| `token_bucket.lua` | `resources` | Atomic server-side Lua script. One round-trip: reads `HMGET`, computes refill, makes decision, writes `HMSET`, sets `EXPIRE`. Returns `{allowed, remaining_tokens, retry_after_ms}`. |
| `RateLimitKeyUtils` | `util` | Builds Redis keys: `rate_limit:{dimension}:{id}:{path}` — e.g. `rate_limit:user:alice:/api/login`. |
| `BackendProxyHandler` | `proxy` | Transparent WebClient proxy. Activated when `BACKEND_URL` env var is set. Strips hop-by-hop headers, forwards method / path / query / body, streams the response back. When `BACKEND_URL` is blank, the filter falls through to `chain.filter()` unchanged. |
| `AdminPolicyController` | `admin` | REST controller for `GET /admin/policies`, `POST /admin/policies`, `DELETE /admin/policies/**`. Input validated with JSR-380 constraints (`@NotBlank`, `@NotEmpty`, `@Positive`). |

---

## Token bucket algorithm

Each policy dimension maintains a Redis Hash with two fields:

- `tokens` — current available tokens
- `last_refill` — epoch milliseconds of the last **successful grant**

The Lua script (`token_bucket.lua`) runs the following logic atomically on every request:

```
1. HMGET key "tokens" "last_refill"
2. If key is new: tokens = capacity, last_refill = now
3. Otherwise:
     elapsed = max(0, now - last_refill)
     refill  = floor(elapsed × refillRate / 1000)
     tokens  = min(capacity, tokens + refill)
4. If tokens >= 1:
     allowed = true
     tokens  = tokens - 1
     last_refill = now        ← only advances on a grant
5. Else:
     allowed = false
     retry_after = ceil(ms_to_next_token + (needed - 1) × ms_per_token)
6. HMSET key "tokens" tokens "last_refill" last_refill
7. EXPIRE key ttl            ← ttl = max(60, capacity × 10 / refillRate)
8. Return {allowed, tokens, retry_after}
```

**Grant-gated clock**: `last_refill` advances only when a request is granted. A denied request still computes the refill (tokens are updated) but leaves `last_refill` frozen at the last grant time. Each successive denial therefore measures `elapsed` from the same anchor — growing with every retry — so refill credit accumulates across the denial window. A heavily rate-limited client recovers their budget slightly faster than the nominal rate would suggest. The effect is bounded by `capacity` and cannot exceed the configured ceiling.

The benefit is a single timestamp per bucket, no background refill job, and state that is self-consistent across Redis restarts when AOF persistence is enabled.

**TTL formula**: `max(60, capacity × 10 / refillRate)`. For `capacity=5, refillRate=1` that is `max(60, 50)` = 60 seconds. Idle buckets expire and are garbage collected by Redis without any application-side cleanup.

**Retry-after precision**: the naive formula `needed × 1000 / refillRate` ignores partial progress in the current refill window and overstates the wait by up to one token period. The Lua script accounts for `elapsed % ms_per_token` to give the client a tighter, more accurate `Retry-After` value.

---

## Multi-dimensional evaluation

A policy can enforce multiple independent budgets for the same endpoint. The `GondorGatesWebFilter` evaluates dimensions using `Flux.concatMap` (serial, in declaration order) + `takeUntil` (short-circuit on denial) + `reduce` (accumulate the most restrictive allowed result):

```
GLOBAL (100 req / 10 tokens/s — shared by all callers)
  ↓ allowed (remaining: 73)
USER   (5 req / 1 token/s — per X-User-Id: alice)
  ↓ denied  → stop; IP and API_KEY buckets are not charged
  return 429, Retry-After: 800ms
```

The response headers on an allowed request expose the **minimum remaining tokens** and the **capacity of the most restrictive dimension evaluated**. On a denied request, `X-RateLimit-Remaining` is always `0`.

---

## Policy resolution — two-tier model

`PolicyResolver.resolve(path)` is called on every non-actuator, non-admin request:

1. **Redis override (exact match)**: `RedisPolicyStore.get(path)` looks up the in-memory cache. A cache hit returns immediately — zero extra Redis round-trips for the common case where no override exists.
2. **YAML fallback (longest-prefix match)**: the YAML policies are pre-sorted by path length descending at startup. The first entry where `path.equals(policyPath)` or `path.startsWith(policyPath + "/")` is returned. The `/` policy acts as the catch-all.

Admin overrides are written to Redis and the local cache simultaneously by `RedisPolicyStore.save`. At startup, `@PostConstruct` reloads all overrides from Redis so they survive an application restart.

---

## Architectural decisions

### Spring WebFlux over Spring MVC

GondorGates is on the hot path of every request. Spring MVC assigns one thread per request; that thread blocks on the Redis call. At 1000 concurrent requests and a 2ms Redis latency, MVC pins 1000 threads simultaneously — hundreds of megabytes of stack and severe context-switch overhead at scale. Spring WebFlux uses Reactor operators and non-blocking I/O: the calling thread is released back to the event loop while waiting for Redis and serves other connections in the meantime. The application code makes no direct reference to Netty — it is the embedded server provided by Spring Boot's auto-configuration when `spring-boot-starter-webflux` is on the classpath. This is the correct architecture for middleware that is always on the critical path.

### Redis Lua over application-level locking

Distributed locks (Redlock, etc.) are expensive and complex. The simpler alternative — GET then SET — races: two concurrent requests can both read `tokens=1`, both grant, both write `tokens=0`, leaking one token. Lua scripts execute atomically on the Redis thread. The entire evaluate-and-mutate operation is linearizable without any application-layer coordination. The script is loaded once at startup and SHA-cached by Redis.

### Fail-open over fail-closed

When Redis is unavailable, a fail-closed system returns 429 to every request — effectively a self-inflicted outage. GondorGates chooses availability: if the rate limiter cannot function, it steps aside. This is the correct tradeoff for infrastructure middleware that protects services from overload; when the protection is unavailable, the underlying services are still better off receiving traffic than being blanket-blocked. The `gondor.redis.errors.total` counter and the provisioned Grafana alert rule detect this condition.

### Trusted-header identity model

`ClientIdentityResolver` reads `X-User-Id` and `X-API-Key` directly from the incoming request headers without verification. This is an explicit design constraint: GondorGates is intended to run behind an authentication layer (API gateway, ingress controller, or service mesh) that strips client-supplied identity headers and reinjects verified values from a validated JWT claim, session token, or mTLS certificate. Deploying GondorGates as a public-facing endpoint without that stripping layer makes the `USER` and `API_KEY` dimensions trivially bypassable.

### Two-tier policy model (YAML + Redis)

YAML is authoritative for the baseline: version-controlled, diff-able in PRs, deployed with the image, always present as a fallback. Redis overlays runtime overrides on top. `PolicyResolver` checks Redis first on every request (exact-match lookup against the in-memory cache — zero extra round-trips on a miss). This preserves the original decision's benefits (diff-able defaults, no schema migration) while adding live reconfiguration without restart. The in-memory cache means the Redis override path adds no latency cost beyond the baseline YAML lookup.

### Static admin token over JWT

The Admin API uses a static pre-shared secret compared with `MessageDigest.isEqual` (constant-time to prevent timing attacks). The token is disabled by default (503 when `GONDORGATES_ADMIN_TOKEN` is not set) to prevent accidental open access. JWT would add a dependency on an identity service; for developer-tooling and operational convenience at this scope, a static token with the right defaults (disabled, constant-time check, no logging of the value) is the correct tradeoff. The known limitation is that the token has no expiry and no per-caller identity — see [Post-MVP: Admin API hardening](#post-mvp-ideas).

---

## Observability

Metrics are exposed via Micrometer at `/actuator/prometheus`. A Prometheus scrape job and a Grafana dashboard are auto-provisioned in the compose files — no manual import required.

| Metric | Type | What it tells you |
|---|---|---|
| `gondor.requests.total` | Counter | Request volume by `path` and `outcome` (allowed / denied) |
| `gondor.filter.duration` | Timer | End-to-end filter latency by `path` and `outcome`, with percentile histogram |
| `gondor.bucket.remaining` | Gauge | Live token count per `path` and `dimension` — watch this drain to zero |
| `gondor.redis.eval.duration` | Timer | Redis Lua script latency with percentile histogram |
| `gondor.redis.errors.total` | Counter | Redis connection failures — when this increments, fail-open is active |
| `gondor.admin.policies.active` | Gauge | Number of active runtime overrides stored in Redis |

An alert rule is provisioned at `grafana/provisioning/alerting/gondor-alerts.yml`. It fires critical when `rate(gondor_redis_errors_total[1m]) > 0` — signalling that Redis is unreachable and all rate limits are suspended.

---

## Security model

| Concern | How it is handled |
|---|---|
| **Rate-limit auth bypass** | `X-User-Id` and `X-API-Key` are trusted as-is. Strip at ingress; do not expose GondorGates publicly without an authentication layer in front. |
| **Admin API access** | `Authorization: Bearer <token>` required. `503` when `GONDORGATES_ADMIN_TOKEN` is unset. Constant-time token comparison. |
| **Container attack surface** | Production Docker image uses a distroless Java 21 base — no shell, no package manager, minimal attack surface (~200MB). |
| **Redis exposure** | Redis is on a private Docker network. It is not exposed to the host in the full-stack compose file (`docker-compose.yml`); it is exposed on `localhost:6379` only in `docker-compose.infra.yml` (dev mode). |
| **Secret scanning** | The CI pipeline runs gitleaks on every PR before the build step. |

---

## Known limitations

- **Single Redis node** — Redis is a single point of failure. A restart causes fail-open and cold-start empty buckets (all counters reset). Fail-open is intentional — see [Fail-open over fail-closed](#fail-open-over-fail-closed).
- **Redis Cluster incompatible** — the key format `rate_limit:{dimension}:{id}:{path}` crosses hash slots arbitrarily. Running against Redis Cluster produces `CROSSSLOT` errors from the Lua script. Hash tags (e.g. `rate_limit:{user:alice}:/api/login`) would fix this but are not implemented.
- **Header trust** — `X-User-Id` and `X-API-Key` are accepted without verification. Deploying without an ingress that strips these headers makes per-user and per-key limits bypassable.
- **No path-parameter awareness** — GondorGates matches on static path prefixes. `/api/users/123` and `/api/users/456` are treated identically and map to the same bucket.
- **Admin token** — the static pre-shared secret has no expiry and no per-caller identity. Not appropriate for production admin exposure without additional hardening.

---

## Post-MVP ideas

### Redis high availability
Redis Sentinel (primary + replicas, auto-failover, transparent to clients) is the simpler HA path. Redis Cluster (horizontal sharding) requires key hash-tag changes so all bucket keys for a given identity land on the same slot (`rate_limit:{user:alice}:/api/login`), making the Lua script Cluster-compatible. Both are out of scope for v1; fail-open covers the downtime window.

### Sliding window strategy
Alternative to token bucket for strictly "N requests per minute" semantics with no burst tolerance. Implemented as a second Lua script, selectable per policy via `strategy: SLIDING_WINDOW`.

### Abuse detection and auto-blocking
Detect sustained violation patterns (e.g. 500 denied requests in 60 seconds from one IP) and temporarily blacklist the source. A separate `AbuseDetector` component watches the denied counter and writes to a Redis blocklist checked at the top of the filter.

### Admin API hardening
Options: (a) mTLS — mutual certificate auth, no token required; (b) short-lived JWT issued by a separate identity service; (c) at minimum, a token rotation mechanism that takes effect without restart.

### OpenAPI / Swagger
The admin REST API has no machine-readable contract. Adding `springdoc-openapi-starter-webflux-ui` would auto-generate a Swagger UI at `/swagger-ui.html` and an OpenAPI JSON at `/v3/api-docs`.

### Multi-tenant isolation
Tenant-aware key namespacing (`rate_limit:{tenantId}:{dimension}:{id}:{path}`) so one GondorGates instance can serve multiple products with isolated budgets.

### GraalVM Native Image
Compile to a native binary for sub-second startup and a smaller image. Suitable for auto-scaling-from-zero deployments.

---

## Build history

A record of what was built and why, in the order it was built. Useful for understanding which decisions evolved across epics and why the codebase looks the way it does.

### Epic 0 — Infrastructure
Spring Boot 3.4 / WebFlux skeleton, Netty embedded server, Redis 7.2 container with AOF persistence and health check, GitHub Actions CI with a Redis service container, `/actuator/health` endpoint, `HealthCheckIT`.

### Epic 1 — Core rate limiting engine
`RateLimiter` interface, `RateLimitDecision` record (`allowed`, `remainingTokens`, `retryAfter`, `capacity`), `TokenBucket` Java model (unused in production path — superseded by the Lua script in Epic 2), `RateLimitKeyUtils` key builder.

### Epic 2 — Distributed Redis backend
`token_bucket.lua` atomic Lua script, `RedisRateLimiter` executing the script via `ReactiveStringRedisTemplate`, fail-open `onErrorResume`, `RedisRateLimiterIT` validating distributed consistency.

### Epic 3 — WebFilter and HTTP integration
`GondorGatesWebFilter` at `@Order(-100)`, `ClientIdentityResolver` (`X-API-Key` → `X-User-Id` → IP → anonymous), HTTP 429 response with JSON body, `X-RateLimit-Remaining` and `Retry-After` response headers, `GondorGatesWebFilterIT`.

Key bug fixed: `Mono<Void>` always completes empty, so `switchIfEmpty` was firing on every request (double filter-chain invocation). Resolved by replacing the reactive chain with a synchronous null-check in the policy resolution path, which is correct since `PolicyResolver.resolve()` is synchronous.

### Epic 4 — Policy engine
`RateLimitPolicy` POJO, `GondorGatesProperties` with `@ConfigurationProperties(prefix = "gondorgates")`, `PolicyResolver` with longest-prefix-wins path matching, four policies in `application.yml` (login, orders, test, catch-all).

Key bug fixed: `startsWith("/api/order")` falsely matched `/api/orders`. Corrected to `startsWith(policyPath + "/")`.

### Epic 5 — Multi-dimensional rate limiting
`RateLimitDimension` enum (`GLOBAL`, `USER`, `IP`, `API_KEY`), `DimensionPolicy` POJO (type + capacity + refillRate), `RateLimitPolicy` migrated from flat fields to `List<DimensionPolicy>`, `ClientIdentityResolver.resolveForDimension()`, `GondorGatesWebFilter` refactored to evaluate dimensions serially via `Flux.concatMap` + `takeUntil` + `reduce`, `RateLimitKeyUtils` activated. All policies in YAML restructured to dimension format.

### Epic 6 — Observability
`micrometer-registry-prometheus` added; `/actuator/prometheus` exposed. `GondorGatesWebFilter` instrumented with `gondor.requests.total` counter, `gondor.filter.duration` timer, and `gondor.bucket.remaining` gauge (one `AtomicLong` per `(dimension, path)` pair via `ConcurrentHashMap`, registered once and updated on every request). `RedisRateLimiter` instrumented with `gondor.redis.eval.duration` timer and `gondor.redis.errors.total` counter. `PrometheusMetricsIT` validates all four metrics are recorded on a real request.

### Epic 7 — Grafana dashboard
Grafana and Prometheus services added to `docker-compose.yml`. Prometheus scrape job configured against GondorGates `/actuator/prometheus`. Dashboard auto-provisioned via `grafana/provisioning/` — no manual import required. Alert rule provisioned at `grafana/provisioning/alerting/gondor-alerts.yml` — fires critical when Redis fail-open is triggered.

### Epic 8 — Deployment and product readiness
Multi-stage `Dockerfile` — Maven build layer cached separately from app layer; distroless Java 21 runtime image (no shell, ~200MB final image). `docker-compose.yml` — single `docker compose up -d --build` starts four services with health-check dependency ordering. `BackendProxyHandler` — transparent WebClient proxy activated by `BACKEND_URL` env var; strips hop-by-hop headers. k6 load test with correctness and throughput scenarios. Load test CI workflow (`load-test.yml`) on `workflow_dispatch`.

### Epic 8b — Sidecar UX and image publishing
`publish.yml` GitHub Actions workflow — builds and pushes `ghcr.io/kartik199/gondorgates:latest` plus a short SHA tag to GHCR on every merge to main. `docker-compose.sidecar.yml` — minimal two-container template (GondorGates + Redis) that consumers copy into their project. `BACKEND_URL` is the only required change.

### Epic 9 — Admin REST API
`GET /admin/policies`, `POST /admin/policies`, `DELETE /admin/policies/**`. `PolicyResolver` checks Redis overrides before YAML on every request. `AdminAuthWebFilter` with `Authorization: Bearer` and constant-time comparison. `RedisPolicyStore` loads overrides from Redis at startup so they survive restarts. `PolicyStore` interface extracted — `PolicyResolver` and `AdminPolicyController` depend on the interface, not `RedisPolicyStore` directly (DIP). JSR-380 validation on `POST /admin/policies` body.

### Epic 10 — Benchmark
k6 load test fixed for k6 v2.0 compatibility (`setup()` for shared state, `responseCallback` to exclude expected 429s). Baseline scenario added (same VU ramp as throughput, targeting `/actuator/info` which makes no Redis calls) to isolate GondorGates overhead. Measured results: baseline P95 ~9ms, throughput P95 ~14ms — overhead ~5ms per request (one Redis Lua round-trip). Correctness verified: exactly 5 requests allowed out of 40 concurrent against a `capacity=5` USER bucket.

### Code quality pass (post-epic)
Applied after all epics; no behaviour changes.

| Area | Change |
|---|---|
| DIP | `PolicyStore` interface; `PolicyResolver` and `AdminPolicyController` depend on the interface, not `RedisPolicyStore` |
| Logging | `System.out.println` in `PolicyResolver` replaced with SLF4J `log.info` |
| Validation | `@NotBlank`, `@NotEmpty`, `@NotNull`, `@Positive` on `RateLimitPolicy` and `DimensionPolicy`; `@Valid` on controller `@RequestBody` |
| Semantics | `@Configuration` on `GondorGatesProperties` replaced with `@Component` |
| Readability | `Long.MAX_VALUE` sentinel in `RateLimitDecision` replaced with named constant `UNCONSTRAINED` |
| Build | macOS Netty DNS resolver dependency moved to a `<profile id="mac-dev">` — no longer ships in the production Docker image |
| Dependencies | `.github/dependabot.yml` added — weekly automated updates for Maven and GitHub Actions |
