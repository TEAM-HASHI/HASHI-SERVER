import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// This script is launched by MapQueryLoadTest against its disposable loopback server only.
const base = __ENV.BASE_URL;
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(base || '')) {
    throw new Error('BASE_URL must identify the local test server');
}
const profile = __ENV.MAP_LOAD_PROFILE || 'smoke';
if (!['smoke', 'normal', 'staged', 'ttl'].includes(profile)) throw new Error('Invalid load profile');
const traffic = __ENV.MAP_LOAD_TRAFFIC || 'shared';
if (!['shared', 'distinct'].includes(traffic)) throw new Error('Invalid load traffic');
const rejected429 = new Counter('map_rejected_429');
const rejected503 = new Counter('map_rejected_503');
const responseCodes = new Counter('map_response_codes');
const errorCodes = Object.fromEntries(['013', '014', '015', '016', '017', '022']
    .map(code => [`RESTAURANT-${code}`, new Counter(`map_error_RESTAURANT_${code}`)]));
const unknownErrors = new Counter('map_error_unknown');
const newQueryMs = new Trend('map_new_query_ms', true);
const nextPageMs = new Trend('map_next_page_ms', true);
const sortMs = new Trend('map_sort_ms', true);
const filterMs = new Trend('map_filter_ms', true);
const semanticFailures = new Rate('map_semantic_failures');

export const options = {
    scenarios: {
        browsing: {
            executor: 'ramping-vus', startVUs: 0,
            stages: profile === 'smoke'
                ? [{ duration: '15s', target: 2 }, { duration: '60s', target: 2 }, { duration: '15s', target: 0 }]
                : [{ duration: '30s', target: 5 }, { duration: '60s', target: 5 },
                    { duration: '30s', target: 10 }, { duration: '60s', target: 10 },
                    { duration: '30s', target: 20 }, { duration: '60s', target: 20 }, { duration: '30s', target: 0 }], gracefulRampDown: '5s',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'], checks: ['rate>0.99'],
        map_semantic_failures: ['rate==0'],
        map_new_query_ms: ['p(95)<3000'], map_next_page_ms: ['p(95)<3000'],
        map_sort_ms: ['p(95)<3000'], map_filter_ms: ['p(95)<3000'],
    },
};

function page(query, operation, metric) {
    const requestOptions = {
        tags: { name: `map_${operation}`, operation, traffic }, timeout: '10s',
    };
    if (traffic === 'distinct') {
        // The Java harness accepts this only through its explicitly trusted loopback proxy.
        const offset = __VU - 1;
        requestOptions.headers = {
            'X-Forwarded-For': `198.18.${Math.floor(offset / 254)}.${(offset % 254) + 1}`,
        };
    }
    const response = http.get(`${base}/api/v1/restaurants/map?${query}`, requestOptions);
    metric.add(response.timings.duration);
    responseCodes.add(1, {status: String(response.status), operation});
    if (response.status === 429) rejected429.add(1);
    if (response.status === 503) rejected503.add(1);
    if (!check(response, { 'map response succeeds': r => r.status === 200 })) {
        let code;
        try { code = response.json('code'); } catch (_) { /* malformed failure body */ }
        (errorCodes[code] || unknownErrors).add(1);
        semanticFailures.add(true);
        return null;
    }
    let data;
    try {
        data = response.json('data');
    } catch (_) {
        recordFailure('map response contains valid JSON');
        return null;
    }
    const valid = Boolean(data && Array.isArray(data.content) && data.content.length === 10
        && data.content.every(item => item !== null && typeof item === 'object'
            && Number.isSafeInteger(item.restaurantId) && item.restaurantId > 0)
        && data.hasNext === true
        && typeof data.nextCursor === 'string' && data.nextCursor.trim().length > 0
        && typeof data.querySessionId === 'string' && data.querySessionId.trim().length > 0);
    check(valid, { 'page has ten cards and a next page': value => value });
    semanticFailures.add(!valid);
    return valid ? data : null;
}

function recordFailure(name) {
    check(false, { [name]: value => value });
    semanticFailures.add(true);
}

export default function () {
    try {
        const bounds = 'south=10&north=11&west=20&east=21';
        const first = page(bounds, 'new', newQueryMs);
        if (!first) return;
        const second = page(`cursor=${encodeURIComponent(first.nextCursor)}`, 'next', nextPageMs);
        const replay = page(`cursor=${encodeURIComponent(first.nextCursor)}`, 'next', nextPageMs);
        const sorted = page(`querySessionId=${encodeURIComponent(first.querySessionId)}&sort=rating`, 'sort', sortMs);
        page(`${bounds}&genre=sushi`, 'filter', filterMs);
        if (second && replay && sorted) {
            const ids = first.content.map(item => item.restaurantId);
            const nextIds = second.content.map(item => item.restaurantId);
            const valid = new Set([...ids, ...nextIds]).size === 20
                && JSON.stringify(nextIds) === JSON.stringify(replay.content.map(item => item.restaurantId))
                && JSON.stringify(ids) === JSON.stringify(sorted.content.map(item => item.restaurantId));
            check(valid, { 'no duplicates, cursor replay and tied sort retain order': value => value });
            semanticFailures.add(!valid);
        }
    } catch (_) {
        // k6 does not fail its process for an iteration exception unless a threshold records it.
        recordFailure('map iteration completes without an unexpected exception');
    } finally {
        // 5 requests and 2 new queries per iteration: paced callers stay below production budgets.
        const pacing = 12 + (__VU % 5) * 0.25;
        sleep(profile === 'normal' || profile === 'ttl' ? pacing : 2);
    }
}
