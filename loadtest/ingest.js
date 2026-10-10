// k6 load test for the ingestion API. SYNTHETIC DATA ONLY (fake patients, no real identifiers).
//
//   export KEYCLOAK_URL=http://localhost:8180 APP_URL=http://localhost:8080
//   export HDIP_PW_INGESTOR1=...   # from .env (scripts/gen-dev-env.sh)
//   k6 run loadtest/ingest.js            # see loadtest/README.md
import http from 'k6/http';
import { check, sleep } from 'k6';

const KEYCLOAK = __ENV.KEYCLOAK_URL || 'http://localhost:8180';
const APP = __ENV.APP_URL || 'http://localhost:8080';
const PASSWORD = __ENV.HDIP_PW_INGESTOR1;

export const options = {
  scenarios: {
    ingest: {
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs: 20,
      maxVUs: 50,
      stages: [
        { target: Number(__ENV.RATE || 5), duration: '30s' },
        { target: Number(__ENV.RATE || 5), duration: __ENV.DURATION || '1m' },
        { target: 0, duration: '10s' },
      ],
    },
  },
  thresholds: {
    // 429 (rate limit) is an expected, handled response and is not counted as a failure here
    'http_req_failed{expected_response:true}': ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  if (!PASSWORD) throw new Error('HDIP_PW_INGESTOR1 is not set');
  const res = http.post(`${KEYCLOAK}/realms/healthcare/protocol/openid-connect/token`, {
    grant_type: 'password', client_id: 'pipeline-client', username: 'ingestor1', password: PASSWORD,
  });
  check(res, { 'token issued': (r) => r.status === 200 });
  return { token: res.json('access_token') };
}

function hl7(i) {
  const now = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14);
  return [
    `MSH|^~\\&|LOADTEST|SYN|HDIP|SYN|${now}||ADT^A01|SYN-${__VU}-${i}|P|2.5`,
    `PID|1||SYN-${__VU}${i}^^^SYN^MR||Testperson^Synthetic||19700101|F`,
  ].join('\r');
}

export default function (data) {
  const headers = { Authorization: `Bearer ${data.token}` };
  if (__ITER % 2 === 0) {
    const r = http.post(`${APP}/api/v1/ingest/hl7`, hl7(__ITER), { headers: { ...headers, 'Content-Type': 'text/plain' }, responseCallback: http.expectedStatuses(202, 429) });
    check(r, { 'hl7 accepted or throttled': (x) => x.status === 202 || x.status === 429 });
  } else {
    const r = http.post(`${APP}/api/v1/synthetic?count=2`, null, { headers, responseCallback: http.expectedStatuses(202, 429) });
    check(r, { 'synthetic accepted or throttled': (x) => x.status === 202 || x.status === 429 });
  }
  sleep(0.1);
}
