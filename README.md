---
title: Home
nav_order: 1
permalink: /
description: GondorGates — Horizontally scalable API rate-limiting gateway
---

# GondorGates

GondorGates is a horizontally scalable API rate-limiting gateway built with Spring WebFlux and Redis. It sits in front of your backend services and enforces configurable traffic limits per endpoint, per dimension (global, per-user, per-IP, per-API key) — atomically and without a single distributed lock.

---

## What it does

Every request that passes through GondorGates is evaluated against a policy. Policies are declared in YAML, resolved by path, and evaluated across one or more **dimensions** in sequence. Each dimension maintains an independent token bucket in Redis, updated by an atomic Lua script. The first dimension that denies a request short-circuits the chain and returns a `429 Too Many Requests` — no further Redis calls are made.

```
Client
  └─▶ GondorGates (Spring WebFlux WebFilter)
          └─▶ PolicyResolver          ← match path to policy
                  └─▶ GLOBAL bucket   ← check shared budget
                          └─▶ USER bucket   ← check per-user budget
                                  └─▶ Backend API (if allowed)
```

---

## Architecture

### Core components

| Component | Role |
|---|---|
| `GondorGatesWebFilter` | Spring WebFlux `WebFilter` at `@Order(-100)`. Intercepts every request before any controller sees it. |
| `PolicyResolver` | Matches incoming path to the longest-matching policy. Falls through to the catch-all `/` if no specific policy matches. |
| `PolicyStore` | Interface for runtime policy store operations (`get`, `listAll`, `save`, `delete`). `RedisPolicyStore` is the production implementation; the interface decouples `PolicyResolver` and `AdminPolicyController` from the Redis backend. |
| `ClientIdentityResolver` | Extracts identity for each dimension: `GLOBAL` → constant `"GLOBAL"`, `USER` → `X-User-Id` header, `IP` → remote address, `API_KEY` → `X-API-Key` header. |
| `RedisRateLimiter` | Executes the Lua script against Redis via `ReactiveStringRedisTemplate`. Fail-open: if Redis is unreachable, the request is allowed. |
| `token_bucket.lua` | Atomic server-side Lua script. Reads bucket state, lazily refills tokens based on elapsed time, makes a decision, writes back, sets TTL — all in one Redis round-trip. |
| `RateLimitKeyUtils` | Builds Redis keys in the format `rate_limit:{dimension}:{id}:{path}`, e.g. `rate_limit:user:kartik:/api/orders`. |

### Architectural Decisions

####  Redis + Lua
A naive GET-then-SET approach races under concurrent load — two threads can both read `tokens=1`, both grant the request, and both write `tokens=0`, effectively allowing a double-spend. The Lua script runs atomically inside Redis, making the entire read-refill-decide-write sequence linearizable. No locks, no CAS retries at the application layer.

#### WebFlux

GondorGates is designed to sit on the hot path of every API request. Spring MVC would block a thread per request waiting on Redis. Spring WebFlux uses Reactor operators (`Mono`/`Flux`) and non-blocking I/O — the calling thread is released back to handle other connections while waiting for Redis to respond. Netty is the embedded server provided by Spring Boot's auto-configuration; the application code makes no direct reference to Netty.

#### Token Bucket algorithm

Each bucket tracks two values in a Redis Hash: `tokens` (current count) and `last_refill` (epoch milliseconds of last successful grant). On every request:

1. Compute elapsed time since `last_refill`.
2. Add `floor(elapsed_ms × refillRate / 1000)` tokens, capped at `capacity`.
3. If `tokens >= 1`, decrement and allow. Otherwise deny and return `retry_after_ms`.

This is a **grant-gated clock** variant of the token bucket: `last_refill` advances only on a successful grant, not on every request. The practical consequence is that repeated denied requests each see a larger `elapsed` window (measured from the last grant, not the last attempt), so refill credit accumulates across retries rather than being reset on each one. This means a heavily rate-limited client recovers their budget slightly faster than the nominal rate would suggest — but the recovery is bounded by `capacity`, so it cannot exceed the configured ceiling. The tradeoff is a deliberate design choice: a single timestamp per bucket (no separate "last-seen" field), no background refill job, and self-consistent state across Redis restarts via AOF persistence.

#### Multi-dimensional evaluation

Dimensions are evaluated in the order they appear in the YAML. The first denial short-circuits. Among allowed decisions, the response exposes the **minimum remaining tokens** across all evaluated dimensions — the most conservative signal to the caller.

```
GLOBAL (100 req / 10 s)
  ↓ allowed (remaining: 73)
USER   (5 req / 1 s)
  ↓ denied  → 429, Retry-After: 800ms
```

---

## Prerequisites

- Java 21+
- Maven (or use the included `./mvnw` wrapper)
- Docker (for Redis)

---

## Running locally

### 1. Start Redis

```bash
docker compose up -d
```

This starts a Redis 7.2 container on port `6379` with AOF persistence and a health check.

### 2. Start GondorGates

```bash
./mvnw spring-boot:run
```

The server starts on port `8080`.

### 3. Verify it is up

```bash
curl http://localhost:8080/actuator/health
```

Expected response:

```json
{
  "status": "UP",
  "components": {
    "redis": { "status": "UP" },
    ...
  }
}
```

### 4. Run the test suite

```bash
./mvnw clean verify
```

Requires a running Redis. All integration tests must pass.

---

## Configuring policies

Policies live in `src/main/resources/application.yml` under `gondorgates.policies`. Each policy targets a path and declares one or more dimensions.

```yaml
gondorgates:
  policies:
    - path: /api/login
      dimensions:
        - type: GLOBAL      # shared across all callers
          capacity: 100
          refillRate: 10    # tokens per second
        - type: USER        # per X-User-Id header value
          capacity: 5
          refillRate: 1

    - path: /api/orders
      dimensions:
        - type: GLOBAL
          capacity: 500
          refillRate: 50
        - type: USER
          capacity: 20
          refillRate: 5

    - path: /              # catch-all for unmatched paths
      dimensions:
        - type: GLOBAL
          capacity: 1000
          refillRate: 100
        - type: USER
          capacity: 100
          refillRate: 10
```

**Dimension types**

| Type | Identity key | Typical use |
|---|---|---|
| `GLOBAL` | `"GLOBAL"` (constant) | Protect the endpoint from aggregate traffic regardless of caller |
| `USER` | Value of `X-User-Id` header, falls back to `"anonymous"` | Per-user quota |
| `IP` | Remote IP address | Block abusive IPs independent of auth headers |
| `API_KEY` | Value of `X-API-Key` header, falls back to `"anonymous"` | Tier-based API key quotas |

> **Anonymous fallback:** When `X-User-Id` or `X-API-Key` is absent, `USER` and `API_KEY` dimensions both fall back to the key `"anonymous"`. All unauthenticated callers share a single bucket — one abusive anonymous client can exhaust the quota and deny all others. If you need per-client isolation for unauthenticated traffic, use the `IP` dimension instead.

**Path matching** uses longest-prefix-wins. `/api/orders/123` matches `/api/orders`, not `/`. The root `/` policy acts as the catch-all.

**Evaluation order** follows YAML declaration order. Put `GLOBAL` first so a shared budget exhaustion short-circuits without charging per-user buckets.

---

## Response headers

| Header | Present when | Value |
|---|---|---|
| `X-RateLimit-Limit` | Always | Capacity of the most restrictive dimension evaluated |
| `X-RateLimit-Remaining` | Always | Minimum remaining tokens across all evaluated dimensions |
| `X-RateLimit-Reset` | 429 response only | Unix timestamp (seconds) when the next token will be available |
| `Retry-After` | 429 response only | Seconds until the next token is available |

---

## HTTP responses

| Status | Meaning |
|---|---|
| `2xx` | Request allowed. Backend response is passed through. |
| `429 Too Many Requests` | Rate limit exceeded. Body: `{"error": "Too Many Requests"}`. Headers: `Retry-After`, `X-RateLimit-Reset`. |

---

## Adding GondorGates to your stack

GondorGates runs as a sidecar container in front of your API. No code changes are required in your service.

### Quick start

**1. Copy the sidecar template into your project**

```bash
curl -O https://raw.githubusercontent.com/Kartik199/GondorGates/main/docker-compose.sidecar-example.yml
```

Or copy `docker-compose.sidecar-example.yml` from this repository.

**2. Point it at your service**

Edit the one line marked `<--` in the file:

```yaml
- BACKEND_URL=http://your-api:3000   # your service name and port
```

**3. Start the sidecar**

```bash
docker compose -f docker-compose.sidecar-example.yml up -d
```

This starts two containers: GondorGates (port 8080) and Redis. Your API container is unchanged.

**4. Route traffic through GondorGates**

Point your clients or load balancer at port `8080` instead of your service directly:

```
Before:  Client → your-api:3000
After:   Client → GondorGates:8080 → your-api:3000
```

That is the complete integration. GondorGates enforces rate limits on every request and proxies allowed ones to your service transparently.

---

### Configuring rate limits via environment variables

Policies can be set entirely through environment variables — no file editing or rebuilding required. Spring Boot maps `GONDORGATES_POLICIES_{index}_*` to the policy list.

```yaml
environment:
  # Policy 0 — strict login limit
  - GONDORGATES_POLICIES_0_PATH=/api/login
  - GONDORGATES_POLICIES_0_DIMENSIONS_0_TYPE=GLOBAL
  - GONDORGATES_POLICIES_0_DIMENSIONS_0_CAPACITY=100
  - GONDORGATES_POLICIES_0_DIMENSIONS_0_REFILLRATE=10
  - GONDORGATES_POLICIES_0_DIMENSIONS_1_TYPE=USER
  - GONDORGATES_POLICIES_0_DIMENSIONS_1_CAPACITY=5
  - GONDORGATES_POLICIES_0_DIMENSIONS_1_REFILLRATE=1

  # Policy 1 — relaxed orders limit
  - GONDORGATES_POLICIES_1_PATH=/api/orders
  - GONDORGATES_POLICIES_1_DIMENSIONS_0_TYPE=GLOBAL
  - GONDORGATES_POLICIES_1_DIMENSIONS_0_CAPACITY=500
  - GONDORGATES_POLICIES_1_DIMENSIONS_0_REFILLRATE=50
```

Any path not matched by a configured policy falls through to the `/` catch-all policy. The defaults in `application.yml` are GLOBAL: 1000 req / 100 per second, USER: 100 req / 10 per second. Override them by declaring a `/` entry in your `gondorgates.policies` config the same way you would any other path.

---

### Headers your clients should send

```http
GET /api/orders HTTP/1.1
Host: gondorgates:8080
X-User-Id: user-123          ← drives the USER dimension
X-API-Key: key-abc           ← drives the API_KEY dimension (takes priority over X-User-Id)
```

If `X-User-Id` is absent, the `USER` dimension falls back to `"anonymous"`. If `X-API-Key` is absent, the `API_KEY` dimension falls back to `"anonymous"`. The `IP` dimension uses the request's remote address and is only evaluated if your policy declares an `IP` dimension.

---

### Security considerations

**GondorGates trusts `X-User-Id` and `X-API-Key` headers as-is.** It does not validate, sign, or verify them. Any client that can reach GondorGates can send an arbitrary value in these headers — including impersonating another user or bypassing their own per-user limit.

This is intentional: GondorGates is designed to run *behind* your ingress, not in front of it. The expected production topology is:

```
Internet → Load balancer / API Gateway (strips & injects identity headers) → GondorGates → Backend
```

**Before deploying to production:**
- Strip `X-User-Id` and `X-API-Key` from all inbound client requests at your ingress or load balancer.
- Inject them only after authentication — from a validated JWT claim, session token, or mTLS certificate.
- If you deploy GondorGates as a public-facing endpoint without this stripping, per-user rate limits offer no protection.

The `anonymous` fallback (used when identity headers are absent) creates a **shared bucket** across all unauthenticated callers. One abusive anonymous client can exhaust the anonymous quota and deny all other unauthenticated users.

---

### Option A — Embed in an existing Spring WebFlux app

If your backend is already a Spring WebFlux application, GondorGates can run in the same JVM instead of as a sidecar.

1. Copy the `com.gondorgates.limiter_service` packages into your project.
2. Add the `gondorgates.policies` block to your `application.yml`.
3. Ensure Redis is reachable under `spring.data.redis`.
4. Start your app — the filter registers itself at `@Order(-100)` and intercepts all requests automatically.

---

## Inspecting bucket state in Redis

```bash
# Check the GLOBAL bucket for /api/login
redis-cli HGETALL rate_limit:global:GLOBAL:/api/login

# Check a specific user's bucket for /api/orders
redis-cli HGETALL rate_limit:user:kartik:/api/orders
```

Each bucket hash contains two fields: `tokens` (current count) and `last_refill` (epoch millis of last successful grant).

---

## Performance

The load test in `k6/load-test.js` runs three scenarios back to back. The baseline scenario is specifically designed to isolate GondorGates' overhead from the underlying Spring WebFlux stack cost.

```bash
docker compose -f docker-compose.full.yml up -d
BASE_URL=http://localhost:8080 k6 run k6/load-test.js
```

**Correctness** — 20 VUs, 40 shared iterations, all targeting the same `X-User-Id` against `/api/login` (USER capacity = 5). Proves the atomic Lua script has no race condition under concurrent load.

| Result | Value |
|---|---|
| Requests allowed | **5 out of 40** (exactly at capacity — no double-spend) |
| Requests denied | 35 |

**Baseline** — same 1→100 VU ramp and 100ms sleep as the throughput scenario, targeting `/actuator/info`. The `GondorGatesWebFilter` short-circuits immediately for `/actuator` paths and `/actuator/info` makes no Redis calls — it returns static app metadata only. Matching the VU ramp and sleep pacing makes the two P95 values directly comparable.

| Metric | Value |
|---|---|
| P95 latency | **~9ms** |
| P90 latency | ~8ms |
| Average latency | ~4ms |

**Throughput ramp** — ramps from 1 to 100 VUs over 2 minutes against `/api/orders`. Full filter path: policy resolution, Redis Lua eval, dimension evaluation, response headers.

| Metric | Value |
|---|---|
| P95 latency | **~14ms** |
| P90 latency | ~12ms |
| Average latency | ~7ms |
| Throughput | ~449 req/s |
| Rate limiting fires | Yes — 429s observed throughout ramp |

**GondorGates overhead (throughput P95 − baseline P95): ~5ms per request.** This is one Redis round-trip for the atomic Lua eval — all rate-limit state lives in Redis, so every request pays one network call to read, compute, and write the token bucket atomically.

Measured on local Docker (Apple Silicon). Results will vary with hardware and network; the k6 threshold is set at `p(95) < 50ms` to accommodate CI environments.

---

## Roadmap

| Epic | Status | Description |
|---|---|---|
| 0 — Infrastructure | Done | Spring WebFlux skeleton, Redis Docker Compose, GitHub Actions CI |
| 1 — Core Engine | Done | Token Bucket model, `RateLimiter` interface, `RateLimitDecision` |
| 2 — Redis Backend | Done | Atomic Lua script, `RedisRateLimiter`, fail-open strategy |
| 3 — Web Filter | Done | `GondorGatesWebFilter`, `ClientIdentityResolver`, HTTP 429 handling |
| 4 — Policy Engine | Done | YAML-driven policies, `PolicyResolver`, longest-match-wins |
| 5 — Multi-Dimensional | Done | GLOBAL/USER/IP/API_KEY dimensions, hierarchical short-circuit |
| 6 — Observability | Done | Micrometer + Prometheus metrics at `/actuator/prometheus` |
| 7 — Grafana Dashboard | Done | Real-time per-endpoint dashboard, auto-provisioned, anonymous access |
| 8 — Deployment | Done | Dockerfile (distroless), full Docker stack, proxy handler, k6 load tests |
| 8b — Sidecar UX | Done | GHCR image publish, sidecar compose template, env var policy config |
| 9 — Admin REST API | Done | Runtime policy changes without restart via `POST /admin/policies` |
| 10 — Benchmark | Done | k6 load test with baseline comparison — overhead ~5ms per request (one Redis Lua round-trip), correctness verified |

---

## Known limitations

- **Single Redis instance** — Redis runs as a single node with no replication. A Redis restart causes fail-open (all rate limits suspended until Redis recovers and buckets rebuild from scratch). Redis Sentinel or Cluster is not implemented.
- **Redis Cluster incompatible** — the key format (`rate_limit:{dimension}:{id}:{path}`) crosses hash slots arbitrarily. Running against Redis Cluster would produce `CROSSSLOT` errors from the Lua script.
- **Header trust** — `X-User-Id` and `X-API-Key` are accepted as-is with no verification. Strip them at your ingress before production deployment. See [Security considerations](#security-considerations).
- **Policy reload requires restart** — changes to `gondorgates.policies` in `application.yml` take effect only after a restart. For live changes, use the [Admin REST API](#admin-rest-api) (`POST /admin/policies`).
- **No path-parameter awareness** — GondorGates matches on static path prefixes. `/api/users/123` and `/api/users/456` are treated identically and map to the same policy bucket.

---

## Further reading

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the full technical vision, architectural decision log, epic history, and planned next steps.
