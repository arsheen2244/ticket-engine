// Usage: k6 run -e STRATEGY=naive|pessimistic|optimistic|redis|redis_async [-e TICKETS=1000 -e REQUESTS=10000] load-tests/rush.js
// If the server sets ADMIN_TOKEN, pass the same value: -e ADMIN_TOKEN=... (needed to create the event).
import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

http.setResponseCallback(http.expectedStatuses(201, 202, 409, 429));

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const STRATEGY = __ENV.STRATEGY || 'redis';
const TICKETS = parseInt(__ENV.TICKETS || '1000');
const JSON_HEADERS = { 'Content-Type': 'application/json' };
const ADMIN_HEADERS = __ENV.ADMIN_TOKEN
  ? { ...JSON_HEADERS, 'X-Admin-Token': __ENV.ADMIN_TOKEN }
  : JSON_HEADERS;

export const options = {
  scenarios: {
    rush: {
      executor: 'shared-iterations',
      vus: parseInt(__ENV.VUS || '500'),
      iterations: parseInt(__ENV.REQUESTS || '10000'),
      maxDuration: '3m',
    },
  },
};

export function setup() {
  const res = http.post(`${BASE}/api/events`,
    JSON.stringify({ name: `Rush ${Date.now()}`, totalTickets: TICKETS }), { headers: ADMIN_HEADERS });
  if (res.status !== 200) {
    throw new Error(`Creating the event failed with ${res.status}. If the server sets ADMIN_TOKEN, pass -e ADMIN_TOKEN=...`);
  }
  return { eventId: res.json('id') };
}

export default function (data) {
  const res = http.post(
    `${BASE}/api/events/${data.eventId}/reserve?strategy=${STRATEGY}`,
    JSON.stringify({ userId: `user-${__VU}-${__ITER}`, qty: 1 }),
    { headers: { ...JSON_HEADERS, 'Idempotency-Key': uuidv4() } });
  check(res, { 'accepted (201/202) or rejected (409/429)': (r) => [201, 202, 409, 429].includes(r.status) });
}

export function teardown(data) {
  if (STRATEGY === 'redis_async') sleep(8); // let the Kafka consumer finish writing
  const stats = http.get(`${BASE}/api/events/${data.eventId}/stats`).json();
  console.log(`RESULT strategy=${STRATEGY} ${JSON.stringify(stats)}`);
}
