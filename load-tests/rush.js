// Usage: k6 run -e STRATEGY=naive|pessimistic|optimistic|redis [-e TICKETS=1000 -e REQUESTS=10000] load-tests/rush.js
import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

http.setResponseCallback(http.expectedStatuses(201, 409, 429));

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const STRATEGY = __ENV.STRATEGY || 'redis';
const TICKETS = parseInt(__ENV.TICKETS || '1000');
const JSON_HEADERS = { 'Content-Type': 'application/json' };

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
    JSON.stringify({ name: `Rush ${Date.now()}`, totalTickets: TICKETS }), { headers: JSON_HEADERS });
  return { eventId: res.json('id') };
}

export default function (data) {
  const res = http.post(
    `${BASE}/api/events/${data.eventId}/reserve?strategy=${STRATEGY}`,
    JSON.stringify({ userId: `user-${__VU}-${__ITER}`, qty: 1 }),
    { headers: { ...JSON_HEADERS, 'Idempotency-Key': uuidv4() } });
  check(res, { 'accepted (201) or rejected (409/429)': (r) => [201, 409, 429].includes(r.status) });
}

export function teardown(data) {
  const stats = http.get(`${BASE}/api/events/${data.eventId}/stats`).json();
  console.log(`RESULT strategy=${STRATEGY} ${JSON.stringify(stats)}`);
}
