import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Custom metrics
const allowedCount  = new Counter('gondor_allowed');
const deniedCount   = new Counter('gondor_denied');
const filterLatency = new Trend('gondor_filter_latency_ms', true);

// Unique user-id for the correctness test so each run starts with a fresh bucket.
const CORRECTNESS_USER = `correctness-probe-${Date.now()}`;

export const options = {
    scenarios: {
        // ---------------------------------------------------------------
        // Scenario 1 — Correctness
        // 20 VUs all share the same user-id against /api/login (USER capacity=5).
        // Total allowed must not exceed 5 — proves the Lua script is atomic
        // and no double-spend occurs under concurrent load.
        // ---------------------------------------------------------------
        correctness: {
            executor: 'shared-iterations',
            vus: 20,
            iterations: 40,
            maxDuration: '30s',
            exec: 'correctnessTest',
            tags: { scenario: 'correctness' },
        },

        // ---------------------------------------------------------------
        // Scenario 2 — Throughput ramp
        // Ramps from 1 to 100 VUs over 2 minutes against /api/orders.
        // Each VU uses a unique user-id so USER buckets don't interfere.
        // Records real P95 latency and confirms 429s fire (rate limiting
        // is active under GLOBAL budget pressure at high VU counts).
        // ---------------------------------------------------------------
        throughput: {
            executor: 'ramping-vus',
            startVUs: 1,
            stages: [
                { duration: '30s', target: 50  },
                { duration: '60s', target: 100 },
                { duration: '30s', target: 0   },
            ],
            exec: 'throughputTest',
            tags: { scenario: 'throughput' },
            startTime: '35s',
        },
    },

    thresholds: {
        // Correctness: allowed requests for the probe user must not exceed USER capacity (5).
        // A higher count means the atomic Lua script has a race condition — a failing test
        // here is a correctness bug, not a performance issue.
        'gondor_allowed{scenario:correctness}': ['count<=5'],

        // Throughput: P95 filter latency must be under 500ms (generous ceiling for a demo).
        // Replace with a tighter bound once baseline benchmarks are established from real runs.
        'gondor_filter_latency_ms{scenario:throughput}': ['p(95)<500'],

        // Rate limiting must actually fire under load — at least one 429 expected.
        'gondor_denied{scenario:throughput}': ['count>0'],

        // HTTP errors (non-2xx, non-429) must be zero — anything else is an unexpected failure.
        'http_req_failed': ['rate<0.01'],
    },
};

export function correctnessTest() {
    const res = http.get(`${BASE_URL}/api/login`, {
        headers: { 'X-User-Id': CORRECTNESS_USER },
        tags: { scenario: 'correctness' },
    });

    filterLatency.add(res.timings.duration, { scenario: 'correctness' });

    if (res.status === 200) {
        allowedCount.add(1, { scenario: 'correctness' });
    } else if (res.status === 429) {
        deniedCount.add(1, { scenario: 'correctness' });
        check(res, {
            '429 has Retry-After header': (r) => r.headers['Retry-After'] !== undefined,
            '429 has X-RateLimit-Remaining: 0': (r) => r.headers['X-RateLimit-Remaining'] === '0',
        });
    }
}

export function throughputTest() {
    // Each VU uses its own user-id — tests GLOBAL bucket pressure, not per-user exhaustion.
    const res = http.get(`${BASE_URL}/api/orders`, {
        headers: { 'X-User-Id': `vu-${__VU}` },
        tags: { scenario: 'throughput' },
    });

    filterLatency.add(res.timings.duration, { scenario: 'throughput' });

    if (res.status === 200) {
        allowedCount.add(1, { scenario: 'throughput' });
        check(res, {
            'allowed has X-RateLimit-Remaining': (r) => r.headers['X-RateLimit-Remaining'] !== undefined,
        });
    } else if (res.status === 429) {
        deniedCount.add(1, { scenario: 'throughput' });
    }

    sleep(0.1);
}
