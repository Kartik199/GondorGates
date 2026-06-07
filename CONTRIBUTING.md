# Contributing to GondorGates

## Local development setup

**Prerequisites:** Java 21, Docker Desktop, Maven wrapper (`./mvnw` — no installation needed).

**1. Start the infrastructure (Redis, Prometheus, Grafana)**

```bash
cp .env.example .env          # only needed once
docker compose -f docker-compose.infra.yml up -d
```

**2. Start GondorGates on the host**

```bash
# Standalone — rate-limit headers applied, 404 for non-actuator paths
./mvnw spring-boot:run

# With a backend to proxy
BACKEND_URL=http://localhost:5000 \
GONDORGATES_ADMIN_TOKEN=$(openssl rand -hex 32) \
./mvnw spring-boot:run
```

GondorGates is ready when you see `Started GondorGatesApplication in X.XXX seconds`. Verify:

```bash
curl http://localhost:8080/actuator/health
```

**3. Stop everything**

```bash
# Ctrl+C in the mvnw terminal, then:
docker compose -f docker-compose.infra.yml down
```

---

## Running tests

Integration tests require a Redis instance on `localhost:6379`. Start it first if it isn't already running:

```bash
docker compose -f docker-compose.infra.yml up -d gondor-redis
```

Run the full test suite (unit + integration):

```bash
./mvnw verify
```

Run only unit tests (no Redis needed):

```bash
./mvnw test
```

Test reports land in `target/surefire-reports/`.

---

## Project structure

```
src/main/java/com/gondorgates/limiter/
  admin/        AdminAuthWebFilter, AdminPolicyController, RedisPolicyStore, PolicyStore
  config/       GondorGatesProperties (@ConfigurationProperties), RedisConfig, AppConfig
  engine/       RedisRateLimiter, RateLimiter (interface), RateLimitDecision
  filter/       GondorGatesWebFilter, ClientIdentityResolver
  policy/       PolicyResolver, RateLimitPolicy, DimensionPolicy, RateLimitDimension
  proxy/        BackendProxyHandler
  util/         RateLimitKeyUtils

src/main/resources/
  application.yml          — default policies and Spring config
  scripts/token_bucket.lua — atomic Redis Lua script (the rate-limiting core)
```

---

## How to add a new dimension type

Dimensions are the identity axes GondorGates uses to partition rate limit buckets (e.g. per-user, per-IP).

**1. Add the new value to the enum** (`policy/RateLimitDimension.java`):

```java
public enum RateLimitDimension {
    GLOBAL, USER, IP, API_KEY, MY_NEW_DIMENSION
}
```

**2. Resolve the identity key** (`filter/ClientIdentityResolver.java`):

Add a `case` to `resolveForDimension` that extracts the identity string from the request — a header value, remote address, or any other request attribute:

```java
case MY_NEW_DIMENSION -> {
    String value = request.getHeaders().getFirst("X-My-Header");
    yield StringUtils.hasText(value) ? value : "anonymous";
}
```

**3. Update the dimension table** in README.md under "Dimension types".

**4. Add a policy** using the new type in `application.yml` or via the Admin REST API to verify it works end-to-end.

---

## Branch and PR conventions

| Branch prefix | Use for |
|---|---|
| `feat/` | New functionality |
| `fix/` | Bug fixes |
| `chore/` | Refactoring, dependency updates, build changes |
| `docs/` | Documentation only |
| `security/` | Security fixes |

Branch names use kebab-case: `feat/sliding-window-strategy`.

All PRs target `main`. CI runs automatically on every PR:

1. **Secret scan** (gitleaks) — runs first; the build step does not start if secrets are detected.
2. **Build and test** (`./mvnw clean verify`) — requires a passing secret scan.

Do not merge if either step is red.

---

## CI and publishing

- **`ci.yml`** — runs on every PR to `main`: secret scan → build → unit and integration tests.
- **`load-test.yml`** — manual trigger only (`workflow_dispatch`). Runs the k6 load test against the full compose stack.
- **`publish.yml`** — runs on every merge to `main`. Builds the Docker image and pushes `ghcr.io/kartik199/gondorgates:latest` plus a short SHA tag to GHCR.
- **`docs.yml`** — runs on merge to `main` when `.md`, `_config.yml`, or `Gemfile` files change. Builds the Jekyll site and publishes to GitHub Pages.
