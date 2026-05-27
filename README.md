---
title: Home
nav_order: 1
permalink: /
description: GondorGates — Distributed API rate-limiting gateway
---

# GondorGates

GondorGates is a distributed API rate-limiting gateway built with Spring WebFlux and Redis. It sits in front of your backend services and enforces configurable traffic limits per endpoint, per dimension (global, per-user, per-IP, per-API key) — atomically and without a single distributed lock.

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

Refill only advances on a successful grant ("lazy refill"), which simplifies state and avoids a separate background job.

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

Requires a running Redis. All 6 integration tests must pass.

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

**Path matching** uses longest-prefix-wins. `/api/orders/123` matches `/api/orders`, not `/`. The root `/` policy acts as the catch-all.

**Evaluation order** follows YAML declaration order. Put `GLOBAL` first so a shared budget exhaustion short-circuits without charging per-user buckets.

---

## Response headers

| Header | Present when | Value |
|---|---|---|
| `X-RateLimit-Remaining` | Always | Minimum remaining tokens across all evaluated dimensions |
| `Retry-After` | 429 response only | Seconds until the denying bucket has a token available |

---

## HTTP responses

| Status | Meaning |
|---|---|
| `2xx` | Request allowed. Backend response is passed through. |
| `429 Too Many Requests` | Rate limit exceeded. Body: `{"error": "Too Many Requests"}`. Headers: `Retry-After`. |

---

## Adding GondorGates to your stack

GondorGates runs as a sidecar container in front of your API. No code changes are required in your service.

### 5-minute setup

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

Any path not matched by a configured policy falls through to a built-in catch-all (`/`) with generous defaults (GLOBAL: 1000, USER: 100).

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
| 8b — Sidecar UX | In Progress | GHCR image publish, sidecar compose template, env var policy config |
| 9 — Admin REST API | Planned | Runtime policy changes without restart via `POST /admin/policies` |
| 10 — Benchmark | Planned | Load test against a real API, documented P95 overhead numbers |

---

## Further reading

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the full technical vision, architectural decision log, epic history, and planned next steps.
