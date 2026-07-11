/**
 * k6 Load Test — Distributed URL Shortening Platform
 *
 * Tests three scenarios reflecting real traffic patterns:
 *   1. Redirect hot path (90% of traffic) — read-dominant
 *   2. URL creation (9% of traffic) — write path
 *   3. Analytics queries (1% of traffic) — read-heavy aggregations
 *
 * Run:
 *   k6 run --env BASE_URL=http://localhost:8080 scripts/load-test.js
 *
 * Stages defined in options below simulate a realistic traffic ramp.
 *
 * SLO targets (checked via thresholds):
 *   - Redirect p99 < 50ms
 *   - Redirect error rate < 0.1%
 *   - API p99 < 500ms
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

// ============================================================
// Configuration
// ============================================================
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  stages: [
    { duration: '1m',  target: 50  },  // ramp up
    { duration: '3m',  target: 500 },  // sustained load
    { duration: '2m',  target: 1000 }, // peak load
    { duration: '1m',  target: 500 },  // ramp down
    { duration: '1m',  target: 0   },  // cool down
  ],
  thresholds: {
    // SLO: redirect p99 must be under 50ms
    'redirect_duration': ['p(99)<50'],
    // SLO: less than 0.1% errors on redirects
    'redirect_errors': ['rate<0.001'],
    // SLO: API p99 under 500ms
    'api_duration': ['p(99)<500'],
    // Overall HTTP error rate
    'http_req_failed': ['rate<0.01'],
  },
};

// ============================================================
// Custom metrics
// ============================================================
const redirectDuration = new Trend('redirect_duration', true);
const redirectErrors   = new Rate('redirect_errors');
const apiDuration      = new Trend('api_duration', true);
const urlsCreated      = new Counter('urls_created');

// ============================================================
// Shared test data — loaded once, shared across VUs
// ============================================================
const shortCodes = new SharedArray('shortCodes', function () {
  // Pre-populated short codes for redirect tests
  // In practice, run setup() to create these first
  return [
    'test001', 'test002', 'test003', 'test004', 'test005',
    'test006', 'test007', 'test008', 'test009', 'test010',
  ];
});

// ============================================================
// Setup: register a test user and create test URLs
// ============================================================
export function setup() {
  const email    = `loadtest-${Date.now()}@example.com`;
  const password = 'LoadTest@123';

  // Register
  const regRes = http.post(`${BASE_URL}/api/v1/auth/register`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' } });

  if (regRes.status !== 201) {
    console.error(`Setup: registration failed ${regRes.status}: ${regRes.body}`);
    return { token: null, shortCodes: [] };
  }

  const token = regRes.json('accessToken');
  const createdCodes = [];

  // Create 20 test URLs
  for (let i = 0; i < 20; i++) {
    const res = http.post(`${BASE_URL}/api/v1/urls`,
      JSON.stringify({ longUrl: `https://example.com/test-page-${i}` }),
      { headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`,
      }});

    if (res.status === 201) {
      createdCodes.push(res.json('shortCode'));
    }
  }

  console.log(`Setup complete: created ${createdCodes.length} test URLs`);
  return { token, shortCodes: createdCodes };
}

// ============================================================
// Main VU scenario — weighted traffic mix
// ============================================================
export default function (data) {
  const rand = Math.random();

  if (rand < 0.90) {
    // 90% — redirect hot path
    scenarioRedirect(data);
  } else if (rand < 0.99) {
    // 9% — URL creation
    scenarioCreate(data);
  } else {
    // 1% — analytics
    scenarioAnalytics(data);
  }
}

// ============================================================
// Scenario: Redirect
// ============================================================
function scenarioRedirect(data) {
  const codes = data.shortCodes && data.shortCodes.length > 0
    ? data.shortCodes
    : shortCodes;

  if (codes.length === 0) return;

  const code = codes[Math.floor(Math.random() * codes.length)];

  const start = Date.now();
  const res = http.get(`${BASE_URL}/r/${code}`, {
    redirects: 0,  // don't follow redirects — measure only resolution time
    tags: { name: 'redirect' },
  });
  const duration = Date.now() - start;

  redirectDuration.add(duration);

  const success = check(res, {
    'redirect: status is 3xx': (r) => r.status >= 300 && r.status < 400,
    'redirect: has Location header': (r) => r.headers['Location'] !== undefined,
    'redirect: response under 50ms': (r) => duration < 50,
  });

  redirectErrors.add(!success);
  sleep(0.01); // 10ms think time
}

// ============================================================
// Scenario: Create URL
// ============================================================
function scenarioCreate(data) {
  if (!data.token) return;

  const start = Date.now();
  const res = http.post(`${BASE_URL}/api/v1/urls`,
    JSON.stringify({
      longUrl: `https://example.com/page-${Math.random().toString(36).slice(2)}`,
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${data.token}`,
      },
      tags: { name: 'create-url' },
    });
  const duration = Date.now() - start;

  apiDuration.add(duration);

  const ok = check(res, {
    'create: status 201': (r) => r.status === 201,
    'create: has shortCode': (r) => r.json('shortCode') !== undefined,
  });

  if (ok) urlsCreated.add(1);
  sleep(0.1);
}

// ============================================================
// Scenario: Analytics
// ============================================================
function scenarioAnalytics(data) {
  if (!data.token || !data.shortCodes || data.shortCodes.length === 0) return;

  const code = data.shortCodes[Math.floor(Math.random() * data.shortCodes.length)];

  const start = Date.now();
  const res = http.get(`${BASE_URL}/api/v1/analytics/${code}?days=7`, {
    headers: { 'Authorization': `Bearer ${data.token}` },
    tags: { name: 'analytics' },
  });
  const duration = Date.now() - start;

  apiDuration.add(duration);

  check(res, {
    'analytics: status 200': (r) => r.status === 200,
    'analytics: has totalClicks': (r) => r.json('totalClicks') !== undefined,
  });

  sleep(0.5);
}

// ============================================================
// Teardown
// ============================================================
export function teardown(data) {
  console.log(`Teardown: load test complete. URLs created: ${urlsCreated.name}`);
}
