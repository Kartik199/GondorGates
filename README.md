---
title: Home
nav_order: 1
permalink: /
description: GondorGates — Horizontally scalable API rate-limiting gateway
---

# GondorGates

> Horizontally scalable API rate-limiting gateway — atomic, reactive, zero-lock.

GondorGates sits in front of your backend services and enforces configurable traffic limits per endpoint, per caller dimension (global, per-user, per-IP, per-API key). Every decision is made by a single atomic Redis Lua script in one round-trip. No distributed locks, no application-level CAS loops, no background jobs.

Built with **Spring Boot 3.4 / WebFlux** and **Redis 7**.

---

## How it works

```
Client Request
  └─▶ GondorGatesWebFilter   (Spring WebFlux @Order -100)
          └─▶ PolicyResolver  (Redis override → YAML longest-prefix)
                  └─▶ Dimension 1: GLOBAL bucket  (atomic Lua eval)
                          └─▶ Dimension 2: USER bucket  (atomic Lua eval)
                                  └─▶ Backend  (proxy or chain.filter)
```

Each request passes through one WebFlux filter. The filter resolves the matching policy, then evaluates each declared dimension in order via a single atomic Lua script per dimension. The first denial short-circuits — no further Redis calls are made and no further buckets are charged.

---

## Features

- **Atomic correctness** — a Lua script performs read → refill → decide → write in one Redis round-trip. Exactly the configured number of requests are allowed under concurrent load; no double-spend.
- **Non-blocking I/O** — built on Project Reactor (`Mono`/`Flux`). Threads are never parked waiting on Redis.
- **Multi-dimensional** — enforce independent budgets per endpoint: `GLOBAL`, `USER` (`X-User-Id`), `IP`, `API_KEY` (`X-API-Key`).
- **Two-tier policy config** — YAML declares the baseline; the Admin REST API overlays live overrides without restart.
- **Fail-open** — if Redis is unreachable, requests are allowed through. Rate limiting is a protection mechanism, not a gate.
- **Sidecar-ready** — ships as a distroless Docker image on GHCR. Drop in front of any HTTP service with two lines of config.
- **Observability built-in** — Micrometer metrics at `/actuator/prometheus`, auto-provisioned Grafana dashboard and alert rule included.

---

## Prerequisites

| Tool | Version |
|---|---|
| Java | 21+ |
| Docker | Any recent version |
| Maven | Included via `./mvnw` wrapper |

---

## Quick start

**1. Start Redis, Prometheus, and Grafana**

```bash
docker compose -f docker-compose.infra.yml up -d
```

**2. Start GondorGates on the host**

```bash
# Standalone — returns 404 for non-actuator paths, rate-limit headers are still applied
./mvnw spring-boot:run

# With a backend — all allowed requests are proxied
BACKEND_URL=http://localhost:5000 ./mvnw spring-boot:run
```

**3. Verify it is running**

```bash
curl http://localhost:8080/actuator/health
```

**4. Send a rate-limited request**

```bash
for i in $(seq 1 7); do
  echo -n "Request $i → "
  curl -s -o /dev/null -w "HTTP %{http_code}\n" \
    http://localhost:8080/api/login -H "X-User-Id: alice"
done
```

The first 5 requests return `HTTP 200`. Request 6 onward returns `HTTP 429` — the default `/api/login` USER policy has `capacity: 5`.

For a full end-to-end walkthrough (sample backend, Grafana, live policy changes), see **[QUICKSTART]({% link QUICKSTART.md %})**.

---

## Adding GondorGates to your stack

The image is published to GitHub Container Registry on every merge to `main`:

```bash
docker pull ghcr.io/kartik199/gondorgates:latest
```

Copy `docker-compose.sidecar.yml` from this repository and set one variable:

```yaml
- BACKEND_URL=http://your-api:3000   # your service name and port
```

```bash
docker compose -f docker-compose.sidecar.yml up -d
```

Point your clients at port `8080` instead of your service directly. That is the complete integration — no code changes to your API required.

---

## Configuring policies

Policies live in `src/main/resources/application.yml` under `gondorgates.policies`. Each policy targets a path prefix and declares one or more dimensions:

```yaml
gondorgates:
  policies:
    - path: /api/login
      dimensions:
        - type: GLOBAL        # shared across all callers
          capacity: 100
          refillRate: 10      # tokens added per second
        - type: USER          # per X-User-Id header value
          capacity: 5
          refillRate: 1

    - path: /                 # catch-all for all unmatched paths
      dimensions:
        - type: GLOBAL
          capacity: 1000
          refillRate: 100
        - type: USER
          capacity: 100
          refillRate: 10
```

**Path matching** uses longest-prefix-wins. `/api/orders/123` matches `/api/orders`. The `/` policy is the catch-all for any path not matched by a more specific entry.

**Evaluation order** follows YAML declaration order. Put `GLOBAL` first so a shared-budget exhaustion short-circuits without charging per-user buckets.

### Dimension types

| Type | Identity key | Source |
|---|---|---|
| `GLOBAL` | `"GLOBAL"` (constant) | — |
| `USER` | `X-User-Id` header value | Falls back to `"anonymous"` |
| `IP` | Remote IP address | Falls back to `"unknown_ip"` |
| `API_KEY` | `X-API-Key` header value | Falls back to `"anonymous"` |

> **Anonymous fallback**: when the identity header is absent, `USER` and `API_KEY` dimensions both fall back to the key `"anonymous"`. All unauthenticated callers share one bucket. Use the `IP` dimension for per-client isolation of unauthenticated traffic.

### Environment variable policy config

Policies can be set entirely through environment variables — useful in the sidecar compose template where no file mounting is available:

```bash
GONDORGATES_POLICIES_0_PATH=/api/login
GONDORGATES_POLICIES_0_DIMENSIONS_0_TYPE=GLOBAL
GONDORGATES_POLICIES_0_DIMENSIONS_0_CAPACITY=100
GONDORGATES_POLICIES_0_DIMENSIONS_0_REFILLRATE=10
GONDORGATES_POLICIES_0_DIMENSIONS_1_TYPE=USER
GONDORGATES_POLICIES_0_DIMENSIONS_1_CAPACITY=5
GONDORGATES_POLICIES_0_DIMENSIONS_1_REFILLRATE=1
```

---

## Admin REST API

Change policies on a live instance without restarting. The API is **disabled by default** — enable it by setting `GONDORGATES_ADMIN_TOKEN`:

```bash
export GONDORGATES_ADMIN_TOKEN=$(openssl rand -hex 32)
```

All admin endpoints require `Authorization: Bearer <token>`. Without the env var set, the server returns `503 Service Unavailable`.

| Endpoint | Description |
|---|---|
| `GET /admin/policies` | List all active policies (YAML baseline + runtime overrides) |
| `POST /admin/policies` | Create or update a policy; takes effect on the next request |
| `DELETE /admin/policies/{path}` | Remove a runtime override; YAML baseline takes effect immediately |

```bash
# Tighten /api/login to 3 requests per user
curl -X POST http://localhost:8080/admin/policies \
  -H "Authorization: Bearer $GONDORGATES_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"path": "/api/login", "dimensions": [{"type": "USER", "capacity": 3, "refillRate": 1}]}'

# Restore the YAML default
curl -X DELETE http://localhost:8080/admin/policies/api/login \
  -H "Authorization: Bearer $GONDORGATES_ADMIN_TOKEN"
```

---

## Response headers

| Header | Present when | Value |
|---|---|---|
| `X-RateLimit-Limit` | Always | Capacity of the most restrictive dimension evaluated |
| `X-RateLimit-Remaining` | Always | Minimum remaining tokens across all evaluated dimensions |
| `X-RateLimit-Reset` | 429 only | Unix timestamp (seconds) when the next token becomes available |
| `Retry-After` | 429 only | Seconds until the next token becomes available |

A denied request body: `{"error": "Too Many Requests"}`

---

## Observability

GondorGates exposes Micrometer metrics at `/actuator/prometheus`. Prometheus and a Grafana dashboard are included in the compose files — no manual import or configuration required.

| Metric | Type | Tags |
|---|---|---|
| `gondor.requests.total` | Counter | `path`, `outcome` (allowed / denied) |
| `gondor.filter.duration` | Timer | `path`, `outcome` |
| `gondor.bucket.remaining` | Gauge | `path`, `dimension` |
| `gondor.redis.eval.duration` | Timer | — |
| `gondor.redis.errors.total` | Counter | — |
| `gondor.admin.policies.active` | Gauge | — |

Grafana is at **http://localhost:3000** — anonymous viewer access is enabled for local use. An alert rule fires when `gondor.redis.errors.total` increases, signalling that Redis is unreachable and fail-open is active.

---

## Performance

Measured on local Docker (Apple Silicon) using the k6 load test in `k6/load-test.js`:

| Scenario | P95 latency |
|---|---|
| Baseline — `/actuator/info`, no Redis call | ~9ms |
| Rate-limited path — `/api/orders`, full filter path | ~14ms |
| **GondorGates overhead** | **~5ms** (one Redis Lua round-trip) |

**Correctness**: 20 concurrent VUs sharing one `X-User-Id` against a `capacity=5` bucket produced exactly 5 allowed requests out of 40 — no double-spend under load.

```bash
# Run the full stack and benchmark
docker compose -f docker-compose.yml up -d --build
BASE_URL=http://localhost:8080 k6 run k6/load-test.js
```

Results will vary with hardware and network. The k6 threshold is `p(95)<50ms` to accommodate CI environments.

---

## Security

GondorGates trusts `X-User-Id` and `X-API-Key` headers as-is. It does not validate or verify them. Any caller that can reach the service can send an arbitrary header value.

**Expected production topology:**

```
Internet → Load balancer / API Gateway
              (strips inbound identity headers; injects them from validated JWT / session)
                    → GondorGates → Backend
```

Before deploying: strip `X-User-Id` and `X-API-Key` from all inbound client requests at your ingress and reinject them only after authentication. Deploying GondorGates as a public-facing endpoint without this makes per-user and per-API-key limits trivially bypassable.

---

## Known limitations

- **Single Redis node** — a Redis restart causes fail-open (rate limits suspended until Redis recovers and buckets rebuild from scratch). Redis Sentinel / Cluster not implemented.
- **Redis Cluster incompatible** — the key format `rate_limit:{dimension}:{id}:{path}` crosses hash slots. Running against Redis Cluster produces `CROSSSLOT` errors from the Lua script.
- **Header trust** — `X-User-Id` and `X-API-Key` are accepted without verification. Strip at ingress before production deployment.
- **No path-parameter awareness** — `/api/users/123` and `/api/users/456` are treated identically and map to the same policy bucket.
- **Admin token** — the static pre-shared secret has no expiry and no per-caller identity. Suitable for internal tooling; not appropriate for production admin exposure without additional hardening.

---

## Further reading

- **[QUICKSTART]({% link QUICKSTART.md %})** — end-to-end walkthrough: stand up a sample backend, hit rate limits, watch Grafana, change limits live.
- **[ARCHITECTURE]({% link ARCHITECTURE.md %})** — token bucket design, architectural decision log, component reference, build history.
