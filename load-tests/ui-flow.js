// A flash-sale rush through the SAME endpoints the web app uses, via the Next.js /api proxy.
//
// Each virtual user is a signed-up account that does what a person does in the UI:
//   open the event list -> open the sale -> click Book (and, for some, an accidental second click
//   with the same Idempotency-Key) -> follow the booking status if it was queued -> open My orders.
// There are more users than tickets, so the sale sells out mid-rush.
//
// Usage (stack running, e.g. `docker compose up`):
//   k6 run -e ADMIN_EMAIL=admin@example.com -e ADMIN_PASSWORD=change-me-admin-pass load-tests/ui-flow.js
// Options: -e BASE_URL=http://localhost:3000 -e USERS=2000 -e TICKETS=1000 -e DOUBLE_CLICK_RATE=0.3 -e ARRIVAL_SECONDS=5
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE = __ENV.BASE_URL || 'http://localhost:3000';
const USERS = parseInt(__ENV.USERS || '2000');
const TICKETS = parseInt(__ENV.TICKETS || '1000');
const DOUBLE_CLICK_RATE = parseFloat(__ENV.DOUBLE_CLICK_RATE || '0.3');
// Users arrive at random moments within this many seconds of the sale opening (0 = all at once).
// All-at-once opens every connection in the same millisecond, which overflows a single Node.js
// process's listen backlog on Windows ("connection refused") before the backend is even reached.
const ARRIVAL_SECONDS = parseFloat(__ENV.ARRIVAL_SECONDS || '5');
const JSON_HEADERS = { 'Content-Type': 'application/json' };

// 409 = sold out and 429 = per-user limit are correct answers in a rush, not errors.
http.setResponseCallback(http.expectedStatuses(200, 201, 202, 204, 409, 429));

const booked = new Counter('bookings_confirmed');
const soldOut = new Counter('bookings_sold_out');
const failed = new Counter('bookings_failed');
const duplicateSafe = new Rate('double_click_returned_same_booking');

const endpoints = ['events_list', 'event_detail', 'book', 'booking_status', 'my_orders'];
const thresholds = { http_req_failed: ['rate<0.01'], double_click_returned_same_booking: ['rate==1'] };
// A threshold per endpoint makes k6 report its latency separately in the summary.
for (const name of endpoints) thresholds[`http_req_duration{name:${name}}`] = ['p(95)<5000'];

export const options = {
  setupTimeout: '10m',
  scenarios: {
    rush: { executor: 'per-vu-iterations', vus: USERS, iterations: 1, maxDuration: '5m' },
  },
  thresholds,
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function post(path, body, params = {}) {
  return http.post(`${BASE}${path}`, JSON.stringify(body), { ...params, headers: { ...JSON_HEADERS, ...(params.headers || {}) } });
}

export function setup() {
  const login = post('/api/auth/login', { email: __ENV.ADMIN_EMAIL, password: __ENV.ADMIN_PASSWORD });
  if (login.status !== 200) throw new Error(`Admin login failed (${login.status}). Pass -e ADMIN_EMAIL and -e ADMIN_PASSWORD.`);
  const admin = { Authorization: `Bearer ${login.json('token')}` };
  const sale = post('/api/admin/events', { name: `k6 rush ${Date.now()}`, totalTickets: TICKETS }, { headers: admin });
  if (sale.status !== 201) throw new Error(`Creating the sale failed (${sale.status})`);

  // Create the accounts before the sale opens (sign-up hashes passwords, which is slow on purpose).
  const run = Date.now();
  const tokens = [];
  for (let start = 0; start < USERS; start += 50) {
    const batch = [];
    for (let i = start; i < Math.min(start + 50, USERS); i++) {
      batch.push(['POST', `${BASE}/api/auth/signup`,
        JSON.stringify({ email: `k6-${run}-${i}@example.com`, password: 'k6-password-1' }),
        { headers: JSON_HEADERS, tags: { name: 'signup' } }]);
    }
    for (const res of http.batch(batch)) {
      if (res.status !== 201) throw new Error(`Sign-up failed (${res.status})`);
      tokens.push(res.json('token'));
    }
  }
  return { eventId: sale.json('id'), tokens, admin };
}

export default function (data) {
  const auth = { Authorization: `Bearer ${data.tokens[__VU - 1]}` };
  const eventId = data.eventId;
  sleep(Math.random() * ARRIVAL_SECONDS);

  check(http.get(`${BASE}/api/events`, { tags: { name: 'events_list' } }), { 'list 200': (r) => r.status === 200 });
  check(http.get(`${BASE}/api/events/${eventId}`, { tags: { name: 'event_detail' } }), { 'detail 200': (r) => r.status === 200 });

  const key = uuidv4();
  const bookParams = { headers: { ...auth, 'Idempotency-Key': key }, tags: { name: 'book' } };
  const res = post('/api/bookings', { eventId, qty: 1 }, bookParams);

  if (Math.random() < DOUBLE_CLICK_RATE && (res.status === 201 || res.status === 202)) {
    const again = post('/api/bookings', { eventId, qty: 1 }, bookParams);
    if (again.status !== 0) { // status 0 = never reached the server, so it says nothing about duplicates
      const sameOrder = again.status === 202 || (again.status === 201 && res.status === 202)
        || (again.status === 201 && again.json('order.id') === res.json('order.id'));
      duplicateSafe.add(sameOrder);
    }
  }

  let status = res.status === 201 || res.status === 202 ? res.json('status') : null;
  for (let i = 0; status === 'QUEUED' && i < 30; i++) {
    sleep(0.5);
    status = http.get(`${BASE}/api/bookings/${key}?eventId=${eventId}`, { headers: auth, tags: { name: 'booking_status' } })
      .json('status');
  }

  if (status === 'PENDING_PAYMENT') booked.add(1);
  else if (res.status === 409) soldOut.add(1);
  else failed.add(1);

  check(http.get(`${BASE}/api/me/orders`, { headers: auth, tags: { name: 'my_orders' } }), { 'orders 200': (r) => r.status === 200 });
}

export function teardown(data) {
  sleep(3); // let any queued bookings finish
  const stats = http.get(`${BASE}/api/admin/stats`, { headers: data.admin }).json('sales')
    .find((s) => s.id === data.eventId);
  console.log(`RESULT ${JSON.stringify({ eventId: data.eventId, totalTickets: stats.totalTickets,
    ticketsSold: stats.ticketsSold, reservations: stats.reservations, remaining: stats.remaining, oversold: stats.oversold })}`);
}
