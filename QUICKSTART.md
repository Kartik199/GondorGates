---
title: Quick Start
nav_order: 2
description: Stand up GondorGates, hit the limits, watch Grafana, and change policies live
---

# GondorGates — Quick Start

This guide walks you from zero to a live rate-limited API in under 10 minutes: stand up a sample backend, protect it with GondorGates, hit the limits, watch Grafana light up, and change a policy live without restarting anything.

**Prerequisites:** Docker Desktop, Java 21, Python 3.

---

## Step 1 — Generate your admin token

The admin token is a pre-shared secret you generate once and pass as an environment variable. GondorGates does not issue or sign tokens — it compares what you send against what you configured, using constant-time comparison.

```bash
export GONDORGATES_ADMIN_TOKEN=$(openssl rand -hex 32)
echo $GONDORGATES_ADMIN_TOKEN   # save this — you will need it for admin calls
```

---

## Step 2 — Start a sample backend

You need something for GondorGates to protect. Here is a minimal Flask API. Skip this step if you already have a service running on a known port.

```python
# api.py
from flask import Flask, jsonify, request

app = Flask(__name__)
items = {}

@app.route('/api/items', methods=['GET'])
def list_items():
    return jsonify(list(items.values()))

@app.route('/api/items', methods=['POST'])
def create_item():
    data = request.json
    items[data['id']] = data
    return jsonify(data), 201

@app.route('/api/items/<id>', methods=['DELETE'])
def delete_item(id):
    items.pop(id, None)
    return '', 204

if __name__ == '__main__':
    app.run(port=5000)
```

```bash
pip install flask
python api.py
```

Your backend is now running at `http://localhost:5000`.

---

## Step 3 — Start Redis, Prometheus, and Grafana

Copy the environment file first — Grafana needs `GRAFANA_ADMIN_PASSWORD` to be defined (it can be left blank for local use):

```bash
cp .env.example .env
```

```bash
docker compose -f docker-compose.infra.yml up -d
```

This starts three containers. GondorGates runs on the host in the next step, which lets Prometheus scrape it and lets it reach both Redis and your Flask app without network bridging.

Wait a few seconds, then confirm they are healthy:

```bash
docker compose -f docker-compose.infra.yml ps
```

---

## Step 4 — Start GondorGates

```bash
BACKEND_URL=http://localhost:5000 \
GONDORGATES_ADMIN_TOKEN=$GONDORGATES_ADMIN_TOKEN \
./mvnw spring-boot:run
```

GondorGates is ready when you see:

```
Started GondorGatesApplication in X.XXX seconds
```

Confirm it is up and proxying to your backend:

```bash
curl -i http://localhost:8080/api/items -H "X-User-Id: alice"
```

You should get the Flask response with two extra headers injected by GondorGates:

```
HTTP/1.1 200 OK
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 99
Content-Type: application/json

[]
```

Every request through port `8080` is now rate-limited. Your Flask API on port `5000` only receives requests that GondorGates allows through.

---

## Step 5 — Open Grafana

Navigate to **http://localhost:3000** in your browser.

Anonymous viewer access is enabled for local use — no login required. The GondorGates dashboard is auto-provisioned and ready immediately.

You will see four live panels:

| Panel | What it shows |
|---|---|
| Request rate | Requests per second, split by allowed vs. denied |
| 429 rate | Rate-limited requests — spikes in the next step |
| Filter latency (P95) | Round-trip time through the filter including the Redis Lua call |
| Bucket remaining | Live token count per dimension — watch this drain to zero |

The panels are flat for now. That changes in the next step.

---

## Step 6 — Hit the rate limit and watch Grafana

The default `/api/login` policy has `USER capacity: 5`. Send more than 5 requests with the same `X-User-Id` to trigger a 429:

```bash
for i in $(seq 1 10); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    http://localhost:8080/api/login \
    -H "X-User-Id: alice")
  echo "Request $i → HTTP $STATUS"
done
```

Expected output:

```
Request 1  → HTTP 200
Request 2  → HTTP 200
Request 3  → HTTP 200
Request 4  → HTTP 200
Request 5  → HTTP 200
Request 6  → HTTP 429
Request 7  → HTTP 429
...
Request 10 → HTTP 429
```

A 429 response includes retry metadata:

```
HTTP/1.1 429 Too Many Requests
X-RateLimit-Limit: 5
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1748627193
Retry-After: 4

{"error": "Too Many Requests"}
```

`Retry-After` is the number of seconds to wait before retrying. `X-RateLimit-Reset` is the Unix epoch when the bucket will have at least one token again.

To keep traffic flowing so the Grafana panels stay active:

```bash
while true; do
  curl -s -o /dev/null http://localhost:8080/api/login  -H "X-User-Id: alice"
  curl -s -o /dev/null http://localhost:8080/api/items  -H "X-User-Id: alice"
  curl -s -o /dev/null http://localhost:8080/api/orders -H "X-User-Id: bob"
  sleep 0.05
done
```

Switch to Grafana — the 429 rate and bucket drain panels should now show clear activity. Stop with `Ctrl+C` when done.

---

## Step 7 — Change a policy live (no restart)

The Admin REST API lets you override any policy at runtime. Changes take effect on the next request — no redeployment.

**Tighten `/api/items` to 3 requests per user:**

```bash
curl -X POST http://localhost:8080/admin/policies \
  -H "Authorization: Bearer $GONDORGATES_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "path": "/api/items",
    "dimensions": [{"type": "USER", "capacity": 3, "refillRate": 1}]
  }'
```

**Verify the new limit:**

```bash
for i in $(seq 1 6); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    http://localhost:8080/api/items \
    -H "X-User-Id: alice")
  echo "Request $i → HTTP $STATUS"
done
```

You will hit 429 after 3 requests — the override is live.

**List all active policies** (YAML baseline + runtime overrides):

```bash
curl http://localhost:8080/admin/policies \
  -H "Authorization: Bearer $GONDORGATES_ADMIN_TOKEN"
```

**Restore the YAML default** by deleting the override:

```bash
curl -X DELETE "http://localhost:8080/admin/policies/api/items" \
  -H "Authorization: Bearer $GONDORGATES_ADMIN_TOKEN"
```

The YAML baseline takes effect immediately.

---

## Step 8 — Stop everything

```bash
# Stop GondorGates — Ctrl+C in the terminal where mvnw is running

# Stop the infrastructure containers
docker compose -f docker-compose.infra.yml down
```

---

## Running without the observability stack

If you only need rate limiting without Prometheus and Grafana, use the sidecar compose template. It starts GondorGates and Redis as containers — no host Java required.

```bash
GONDORGATES_ADMIN_TOKEN=my-secret \
BACKEND_URL=http://host.docker.internal:5000 \
docker compose -f docker-compose.sidecar.yml up -d
```

Route your traffic to `http://localhost:8080` instead of your service directly. No code changes to your API required.
