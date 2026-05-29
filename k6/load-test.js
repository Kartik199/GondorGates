import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Custom metrics
const allowedCount  = new Counter('gondor_allowed');
const deniedCount   = new Counter('gondor_denied');
const filterLatency = new Trend('gondor_filter_latency_ms', true);

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

        // Throughput: P95 filter latency based on measured baseline of ~13ms at 100 VUs.
        // Ceiling set at 50ms — 4x the measured baseline — to absorb CI/resource variance.
        'gondor_filter_latency_ms{scenario:throughput}': ['p(95)<50'],

        // Rate limiting must actually fire under load — at least one 429 expected.
        'gondor_denied{scenario:throughput}': ['count>0'],

        // Unexpected failures only (network errors, 5xx). 429s are expected and excluded
        // via responseCallback on each request, so this should stay near zero.
        'http_req_failed': ['rate<0.01'],
    },
};

// setup() runs once before any VU starts — Date.now() produces a single shared value.
// All VU functions receive this data object, so every VU uses the same correctness user ID.
// Using setup() avoids the k6 v2.0 behaviour where module-level code is evaluated per VU,
// which would give each VU a different timestamp and therefore a different Redis bucket.
export function setup() {
    return { correctnessUser: `correctness-probe-${Date.now()}` };
}

export function correctnessTest(data) {
    const res = http.get(`${BASE_URL}/api/login`, {
        headers: { 'X-User-Id': data.correctnessUser },
        tags: { scenario: 'correctness' },
        // Mark 429 as an expected status so it does not count toward http_req_failed.
        responseCallback: http.expectedStatuses(200, 429),
    });

    filterLatency.add(res.timings.duration, { scenario: 'correctness' });

    if (res.status === 200) {
        allowedCount.add(1, { scenario: 'correctness' });
    } else if (res.status === 429) {
        deniedCount.add(1, { scenario: 'correctness' });
        check(res, {
            '429 has Retry-After header': (r) => r.headers['Retry-After'] !== undefined,
            // Go's net/http canonicalises X-RateLimit-Remaining → X-Ratelimit-Remaining.
            // Check both forms to be safe across k6 versions.
            '429 has X-RateLimit-Remaining: 0': (r) =>
                r.headers['X-Ratelimit-Remaining'] === '0' ||
                r.headers['X-RateLimit-Remaining'] === '0',
        });
    }
}

export function throughputTest(data) {
    // Each VU uses its own user-id — tests GLOBAL bucket pressure, not per-user exhaustion.
    const res = http.get(`${BASE_URL}/api/orders`, {
        headers: { 'X-User-Id': `vu-${__VU}` },
        tags: { scenario: 'throughput' },
        responseCallback: http.expectedStatuses(200, 429),
    });

    filterLatency.add(res.timings.duration, { scenario: 'throughput' });

    if (res.status === 200) {
        allowedCount.add(1, { scenario: 'throughput' });
        check(res, {
            'allowed has X-RateLimit-Remaining': (r) =>
                r.headers['X-Ratelimit-Remaining'] !== undefined ||
                r.headers['X-RateLimit-Remaining'] !== undefined,
        });
    } else if (res.status === 429) {
        deniedCount.add(1, { scenario: 'throughput' });
    }

    sleep(0.1);
}
