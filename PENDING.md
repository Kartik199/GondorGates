---
title: Pending Work
nav_order: 4
description: Backlog of pending tasks, improvements, and ideas for GondorGates
---

# GondorGates — Pending Work

This document tracks everything that is planned, in-progress, or worth doing in the future.
Add items freely — nothing here is committed to a timeline.

---

## In Progress

### Fix GHCR publish — unknown blob error
**Branch:** `fix/ghcr-unknown-blob`
Added `provenance: false` to `docker/build-push-action` to prevent buildx from pushing
an OCI attestation manifest that GHCR occasionally cannot resolve.

---

## Immediate (after fix is merged)

### Update README with live GHCR image URL
Once the publish workflow runs successfully, the image is available at:
```
ghcr.io/kartik199/gondorgates:latest
```
Packages page: https://github.com/Kartik199/GondorGates/pkgs/container/gondorgates

Update the README sidecar setup section to reference the live image and confirm
`docker pull ghcr.io/kartik199/gondorgates:latest` works.

### Improve quick-start documentation
After the image is confirmed live, add a "Quick Start" block near the top of README:
```bash
# 1. Copy the sidecar template
curl -O https://raw.githubusercontent.com/Kartik199/GondorGates/main/docker-compose.sidecar-example.yml

# 2. Set your backend URL
# Edit BACKEND_URL in docker-compose.sidecar-example.yml

# 3. Start
docker compose -f docker-compose.sidecar-example.yml up -d
```
This should be the first thing a new user sees after the project description.

---

## Epic 9 — Custom Endpoints & Admin REST API
**Goal:** Define and change rate limit policies for any custom path on a live instance
without restarting. This is the mechanism that makes GondorGates truly universal —
any API's endpoints can be rate-limited without touching YAML or rebuilding.

**What already works (requires restart):**
Custom paths can be set today via env vars at container startup:
```yaml
- GONDORGATES_POLICIES_0_PATH=/api/your-path
- GONDORGATES_POLICIES_0_DIMENSIONS_0_TYPE=USER
- GONDORGATES_POLICIES_0_DIMENSIONS_0_CAPACITY=10
- GONDORGATES_POLICIES_0_DIMENSIONS_0_REFILLRATE=2
```
Epic 9 removes the restart requirement — policies take effect on the next request.

**Prerequisite for Epic 10:** Epic 9 must be done first so the load test can configure
the real API's endpoints dynamically rather than relying on hardcoded YAML paths.

**Work:**
- `GET  /admin/policies` — list all active policies (YAML defaults + Redis overrides)
- `POST /admin/policies` — create or update a policy for any path, effective immediately
- `DELETE /admin/policies/{path}` — remove a runtime override, reverts to YAML default
- `PolicyResolver` checks Redis before YAML on every request
- Static token auth via `X-Admin-Token` header (token set via env var `GONDORGATES_ADMIN_TOKEN`)
- New Grafana panel: count of active dynamic policies
- Update `.env.example` to document `GONDORGATES_ADMIN_TOKEN`
- Integration test: set a policy via API, fire requests, confirm new limit is enforced

---

## Epic 10 — Benchmark with real API

**Goal:** Documented, reproducible performance numbers with GondorGates in the request path.

- Replace the nginx demo-backend with a more realistic API (Express or Spring Boot)
  with variable response times across endpoints
- k6 test run A: direct calls to the API (baseline latency, no GondorGates)
- k6 test run B: calls through GondorGates (measures overhead added by the filter)
- Capture and publish: P50, P95, P99 latency; overhead delta; throughput at peak VUs
- Tighten `p(95)<500` threshold in `k6/load-test.js` to the observed real baseline
- Update README and ARCHITECTURE.md with the actual numbers
- Upload results as a GitHub Actions artifact tied to a specific commit

---

## Dashboard improvements (future)

- **Redis eval duration by endpoint** — needs a `path` tag added to the
  `gondor.redis.eval.duration` timer in `RedisRateLimiter.java`; one-line code change
- **Alert thresholds on panels** — set real threshold values after Epic 10 benchmark
  numbers are known (e.g. alert when P95 > 2× the observed baseline)
- **Active dynamic policy count panel** — depends on Epic 9 Admin API existing first
- **Top blocked endpoints table** — a ranked table of paths by deny rate; useful at a
  glance when investigating abuse

---

## Post-MVP ideas

### GraalVM Native Image
Compile to a native binary for sub-second startup and ~50MB image size.
Suitable for serverless or auto-scaling-from-zero deployments.
Requires Spring AOT configuration and thorough testing of reflection-heavy paths
(Lua script loading, Micrometer registry).

### Sliding window strategy
Alternative to token bucket: "strictly N requests per minute" semantics with no burst
tolerance. Second Lua script, selectable per policy via `strategy: SLIDING_WINDOW`.

### Abuse detection & auto-blocking
Detect sustained violation patterns (e.g. 500 denied requests in 60s from one IP)
and temporarily blacklist the source. `AbuseDetector` component watches the denied
counter and writes to a Redis blocklist checked at the top of the filter.

### X-RateLimit-Reset header
Epoch seconds when the current bucket fully refills. Requires passing `capacity` and
`refillRate` back from the Lua script return value or computing from `retryAfter`.

### Multi-tenant isolation
Tenant-aware key namespacing: `rate_limit:{tenantId}:{dimension}:{id}:{path}`.
Lets one GondorGates instance serve multiple products with isolated budgets.

### Hot reload without restart
Watch for changes to `application.yml` at runtime and reload the policy list without
restarting the JVM. Spring Cloud Config or a file watcher + `@RefreshScope`.

### API Gateway expansion (post-rate-limiter MVP)
- Per-route backend URLs (route `/api/orders` to one service, `/api/login` to another)
- JWT validation and API key store
- Load balancing across multiple backend instances with health checking
- SSL/TLS termination

---

## Add your own items below

