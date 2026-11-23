import http from 'k6/http';
import encoding from 'k6/encoding';
import { check, sleep, group } from 'k6';
import { Counter, Rate } from 'k6/metrics';

// ---------------------------------------------------------------------------
// UpLink API - k6 load test
//
// Three independent signals, each with ONE meaning:
//   1. http_req_failed          -> transport errors / unexpected HTTP status
//                                  (expected statuses are declared per request)
//   2. functional_error_rate    -> response was the wrong *shape* (status ok but
//                                  body is not what the API contract promises)
//   3. http_req_duration{...}   -> latency, per endpoint, via tagged thresholds
//
// Latency is NEVER part of a check(), so slow != failed.
//
// Usage (PowerShell):
//   $env:CLERK_TOKEN = "<jwt>"
//   k6 run benchmark/load_test.js                                   # 1 VU baseline
//   k6 run -e VUS=5   -e HOLD=60s benchmark/load_test.js
//   k6 run -e VUS=20  -e HOLD=60s benchmark/load_test.js
//   k6 run -e VUS=100 -e HOLD=60s benchmark/load_test.js
//
// Env vars (all optional except CLERK_TOKEN for authenticated endpoints):
//   CLERK_TOKEN      Clerk JWT. Never printed. NOTE: Clerk session tokens are
//                    short-lived (60s by default) - see setup() warning.
//   BASE_URL         default http://localhost:8081/api/v1.0
//   VUS              peak virtual users                 (default 1)
//   RAMP             ramp-up duration                   (default 5s)
//   HOLD             time held at peak                  (default 30s)
//   PUBLIC_FILE_ID   id of a real public file; if unset the public probe hits
//                    a non-existent id and measures the 404 path
//   UPLOAD_EVERY     run POST /files/upload-request every Nth iteration per VU.
//                    1 = every iteration, 0 = never.    (default 5)
//                    Each call inserts a PENDING_UPLOAD document in MongoDB.
//   CLEANUP          1 = DELETE the file record created by upload-request
//                    (also issues a B2 delete; not included in measured
//                    latency)                           (default 0)
//   RESULTS          output json path                   (default benchmark/results-<VUS>vu.json)
// ---------------------------------------------------------------------------

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081/api/v1.0';
const AUTH_TOKEN = __ENV.CLERK_TOKEN || '';
const VUS = parseInt(__ENV.VUS || '1', 10);
const RAMP = __ENV.RAMP || '5s';
const HOLD = __ENV.HOLD || '30s';
const RAMP_DOWN = '5s';
const PUBLIC_FILE_ID = __ENV.PUBLIC_FILE_ID || 'healthcheck-benchmark-id';
const UPLOAD_EVERY = parseInt(__ENV.UPLOAD_EVERY || '5', 10);
const CLEANUP = __ENV.CLEANUP === '1';
const RESULTS_PATH = __ENV.RESULTS || `benchmark/results-${VUS}vu.json`;

// Latency targets (ms). Kept at the original values; override per run with
// e.g. -e T_CREDITS=150 when deliberately testing a different topology.
const T = {
  overall_p95: parseInt(__ENV.T_OVERALL_P95 || '150', 10),
  overall_p99: parseInt(__ENV.T_OVERALL_P99 || '300', 10),
  public: parseInt(__ENV.T_PUBLIC || '100', 10),
  credits: parseInt(__ENV.T_CREDITS || '80', 10),
  files: parseInt(__ENV.T_FILES || '120', 10),
  transactions: parseInt(__ENV.T_TRANSACTIONS || '120', 10),
  upload: parseInt(__ENV.T_UPLOAD || '200', 10),
};

// ---- Metrics ---------------------------------------------------------------
const functionalErrors = new Rate('functional_error_rate');
const authFailures = new Counter('auth_failures'); // 401/403 on authenticated calls
const uploadNoCredits = new Counter('upload_insufficient_credits'); // 402 from upload-request
const pendingFilesCreated = new Counter('pending_files_created'); // DB documents left behind

// ---- Options ---------------------------------------------------------------
export const options = {
  scenarios: {
    load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: RAMP, target: VUS },
        { duration: HOLD, target: VUS },
        { duration: RAMP_DOWN, target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },

  // p(99) is not reported by default; without this it shows up as N/A.
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],

  thresholds: {
    // 1. HTTP-level failures (only *unexpected* statuses / transport errors)
    http_req_failed: ['rate<0.02'],
    // 2. Contract / shape errors, independent of latency
    functional_error_rate: ['rate<0.02'],
    // 3. Auth problems should be zero (usually means the token expired)
    auth_failures: ['count==0'],
    // 4. Latency - measured requests only (cleanup calls are excluded)
    'http_req_duration{kind:measured}': [
      `p(95)<${T.overall_p95}`,
      `p(99)<${T.overall_p99}`,
    ],
    'http_req_duration{endpoint:public}': [`p(95)<${T.public}`],
    'http_req_duration{endpoint:credits}': [`p(95)<${T.credits}`],
    'http_req_duration{endpoint:files_my}': [`p(95)<${T.files}`],
    'http_req_duration{endpoint:transactions}': [`p(95)<${T.transactions}`],
    'http_req_duration{endpoint:upload_request}': [`p(95)<${T.upload}`],
  },
};

// ---- Expected statuses (what counts as NOT failed for http_req_failed) -----
const EXPECT_PUBLIC = http.expectedStatuses(200, 404); // 404 = id not found, server alive
const EXPECT_OK = http.expectedStatuses(200);
const EXPECT_UPLOAD = http.expectedStatuses(200, 402); // 402 = out of credits (business rule)
const EXPECT_DELETE = http.expectedStatuses(204, 404);

// ---- Helpers ---------------------------------------------------------------
function authParams(endpoint, expected) {
  return {
    headers: {
      Authorization: `Bearer ${AUTH_TOKEN}`,
      'Content-Type': 'application/json',
    },
    tags: { endpoint, kind: 'measured' },
    responseCallback: expected,
  };
}

// Strip anything sensitive before it can reach a log line.
function safe(text, max = 160) {
  if (!text) return '';
  return String(text)
    .replace(/eyJ[\w-]+\.[\w-]+\.[\w-]+/g, '<jwt>')
    .replace(/https?:\/\/\S+/g, '<url>')
    .replace(/user_[A-Za-z0-9]+/g, '<user>')
    .substring(0, max);
}

// Log at most a few failures per endpoint per VU, never successes.
const logged = {};
function logFailure(name, res, reason) {
  logged[name] = (logged[name] || 0) + 1;
  if (logged[name] > 3) return;
  console.warn(
    `[${name}] ${reason} status=${res.status} ` +
      `duration=${res.timings.duration.toFixed(0)}ms ` +
      (res.error_code ? `error_code=${res.error_code} error=${safe(res.error, 80)} ` : '') +
      `body=${safe(res.body)}`
  );
}

function parseJson(res) {
  try {
    return res.json();
  } catch (e) {
    return null;
  }
}

function noteAuth(name, res) {
  if (res.status === 401 || res.status === 403) {
    authFailures.add(1);
    logFailure(name, res, 'AUTH FAILURE (token expired or invalid?)');
  }
}

function toSeconds(d) {
  const m = /^(\d+)(s|m|h)$/.exec(d);
  if (!m) return 0;
  return parseInt(m[1], 10) * { s: 1, m: 60, h: 3600 }[m[2]];
}

// ---- Setup: sanity-check the token without ever printing it ----------------
export function setup() {
  const testSeconds = toSeconds(RAMP) + toSeconds(HOLD) + toSeconds(RAMP_DOWN);
  console.log(
    `Plan: VUS=${VUS} ramp=${RAMP} hold=${HOLD} (~${testSeconds}s) ` +
      `upload_every=${UPLOAD_EVERY} cleanup=${CLEANUP} base=${BASE_URL}`
  );

  if (!AUTH_TOKEN) {
    console.warn('CLERK_TOKEN not set: only the public endpoint will be tested.');
    return {};
  }

  try {
    const payload = JSON.parse(encoding.b64decode(AUTH_TOKEN.split('.')[1], 'rawurl', 's'));
    const remaining = payload.exp - Math.floor(Date.now() / 1000);
    if (remaining <= 0) {
      console.error('CLERK_TOKEN is already expired. Get a fresh one.');
    } else if (remaining < testSeconds) {
      console.warn(
        `CLERK_TOKEN expires in ~${remaining}s but the test runs ~${testSeconds}s. ` +
          'Expect 401s after expiry. Use a Clerk JWT template with a longer lifetime ' +
          'or shorten HOLD.'
      );
    } else {
      console.log(`CLERK_TOKEN valid for ~${remaining}s (test needs ~${testSeconds}s).`);
    }
  } catch (e) {
    console.warn('Could not decode CLERK_TOKEN expiry (not a JWT?).');
  }
  return {};
}

// ---- Test body -------------------------------------------------------------
export default function () {
  group('public_endpoints', () => {
    const res = http.get(`${BASE_URL}/files/public/${PUBLIC_FILE_ID}`, {
      tags: { endpoint: 'public', kind: 'measured' },
      responseCallback: EXPECT_PUBLIC,
    });
    const ok = check(res, {
      'public: 200 or 404': (r) => r.status === 200 || r.status === 404,
    });
    functionalErrors.add(!ok);
    if (!ok) logFailure('public', res, 'unexpected status');
    sleep(0.05);
  });

  if (!AUTH_TOKEN) {
    sleep(0.1);
    return;
  }

  group('authenticated_metadata', () => {
    // GET /users/credits
    const creditsRes = http.get(`${BASE_URL}/users/credits`, authParams('credits', EXPECT_OK));
    noteAuth('credits', creditsRes);
    const creditsBody = creditsRes.status === 200 ? parseJson(creditsRes) : null;
    const creditsOk = check(creditsRes, {
      'credits: 200': (r) => r.status === 200,
      'credits: has credits+plan': () =>
        creditsBody !== null && creditsBody.credits !== undefined && creditsBody.plan !== undefined,
    });
    functionalErrors.add(!creditsOk);
    if (!creditsOk && creditsRes.status !== 401) logFailure('credits', creditsRes, 'check failed');
    sleep(0.05);

    // GET /files/my
    const filesRes = http.get(`${BASE_URL}/files/my`, authParams('files_my', EXPECT_OK));
    noteAuth('files_my', filesRes);
    const filesBody = filesRes.status === 200 ? parseJson(filesRes) : null;
    const filesOk = check(filesRes, {
      'files list: 200': (r) => r.status === 200,
      'files list: is array': () => Array.isArray(filesBody),
    });
    functionalErrors.add(!filesOk);
    if (!filesOk && filesRes.status !== 401) logFailure('files_my', filesRes, 'check failed');
    sleep(0.05);

    // GET /transactions
    const txRes = http.get(`${BASE_URL}/transactions`, authParams('transactions', EXPECT_OK));
    noteAuth('transactions', txRes);
    const txBody = txRes.status === 200 ? parseJson(txRes) : null;
    const txOk = check(txRes, {
      'transactions: 200': (r) => r.status === 200,
      'transactions: is array': () => Array.isArray(txBody),
    });
    functionalErrors.add(!txOk);
    if (!txOk && txRes.status !== 401) logFailure('transactions', txRes, 'check failed');
    sleep(0.05);

    // POST /files/upload-request (presigned URL only; no bytes sent to B2).
    // Side effect: inserts a PENDING_UPLOAD document. Credits are NOT consumed
    // here (they are consumed in upload-confirm), so it only needs credits >= 1.
    if (UPLOAD_EVERY > 0 && __ITER % UPLOAD_EVERY === 0) {
      const uploadRes = http.post(
        `${BASE_URL}/files/upload-request`,
        JSON.stringify({
          fileName: 'benchmark-test-file.pdf',
          fileSize: 1024 * 1024,
          contentType: 'application/pdf',
        }),
        authParams('upload_request', EXPECT_UPLOAD)
      );
      noteAuth('upload_request', uploadRes);

      const uploadBody = uploadRes.status === 200 ? parseJson(uploadRes) : null;
      if (uploadRes.status === 402) uploadNoCredits.add(1);
      if (uploadRes.status === 200) pendingFilesCreated.add(1);

      const uploadOk = check(uploadRes, {
        'upload-request: 200 or 402': (r) => r.status === 200 || r.status === 402,
        'upload-request: has fileId+uploadUrl': (r) =>
          r.status !== 200 ||
          (uploadBody !== null && !!uploadBody.fileId && !!uploadBody.uploadUrl),
      });
      functionalErrors.add(!uploadOk);
      if (!uploadOk && uploadRes.status !== 401) {
        logFailure('upload_request', uploadRes, 'check failed');
      }

      // Optional cleanup so repeated runs don't pile up PENDING_UPLOAD rows.
      if (CLEANUP && uploadBody && uploadBody.fileId) {
        http.del(`${BASE_URL}/files/${uploadBody.fileId}`, null, {
          headers: { Authorization: `Bearer ${AUTH_TOKEN}` },
          tags: { endpoint: 'cleanup', kind: 'cleanup' },
          responseCallback: EXPECT_DELETE,
        });
      }
      sleep(0.1);
    }
  });

  sleep(0.1);
}

// ---- Summary ---------------------------------------------------------------
function fmt(v) {
  return v === undefined || v === null ? '   n/a' : v.toFixed(1).padStart(7);
}

function thresholdVerdicts(data) {
  const lines = [];
  for (const [name, m] of Object.entries(data.metrics)) {
    if (!m.thresholds) continue;
    for (const [expr, res] of Object.entries(m.thresholds)) {
      // k6 versions differ: {ok: bool} or a bare bool meaning "failed".
      const ok = typeof res === 'object' ? res.ok : !res;
      lines.push(`  ${ok ? 'PASS' : 'FAIL'}  ${name}  ${expr}`);
    }
  }
  return lines;
}

export function handleSummary(data) {
  const rows = [
    ['overall (measured)', 'http_req_duration{kind:measured}'],
    ['public', 'http_req_duration{endpoint:public}'],
    ['credits', 'http_req_duration{endpoint:credits}'],
    ['files/my', 'http_req_duration{endpoint:files_my}'],
    ['transactions', 'http_req_duration{endpoint:transactions}'],
    ['upload-request', 'http_req_duration{endpoint:upload_request}'],
  ];

  const val = (metric, key) => data.metrics[metric] && data.metrics[metric].values[key];
  const out = [];
  out.push('');
  out.push('=== UpLink Benchmark Results ===');
  out.push(`VUs=${VUS}  iterations=${val('iterations', 'count')}  requests=${val('http_reqs', 'count')}`);
  out.push('');
  out.push('Latency (ms)           avg     med     p90     p95     p99     max');
  for (const [label, metric] of rows) {
    out.push(
      `${label.padEnd(20)}${['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max']
        .map((k) => fmt(val(metric, k)))
        .join(' ')}`
    );
  }
  out.push('');
  const pct = (x) => (x === undefined ? 'n/a' : `${(x * 100).toFixed(2)}%`);
  out.push(`HTTP failed (unexpected status/transport): ${pct(val('http_req_failed', 'rate'))}`);
  out.push(`Functional errors (wrong status/shape):    ${pct(val('functional_error_rate', 'rate'))}`);
  out.push(`Auth failures (401/403):                   ${val('auth_failures', 'count') || 0}`);
  out.push(`upload-request 402 (no credits):           ${val('upload_insufficient_credits', 'count') || 0}`);
  out.push(`PENDING_UPLOAD docs created in MongoDB:    ${val('pending_files_created', 'count') || 0}`);
  out.push('');
  out.push('Thresholds:');
  out.push(...thresholdVerdicts(data));
  out.push('================================');
  out.push('');

  return {
    stdout: out.join('\n'),
    [RESULTS_PATH]: JSON.stringify(data, null, 2),
  };
}