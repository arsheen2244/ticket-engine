# Load test: a flash-sale rush through the web app's API

Script: [`load-tests/ui-flow.js`](../load-tests/ui-flow.js). It calls the same endpoints the Next.js app calls,
in the same order a person would:

1. `GET /api/events` (event list page)
2. `GET /api/events/{id}` (event detail page)
3. `POST /api/bookings` with a fresh `Idempotency-Key`; for 30% of users a second, identical POST (a double click)
4. `GET /api/bookings/{key}` until the booking is stored (only when it was queued, in async mode)
5. `GET /api/me/orders` (My orders page)

Every virtual user is a real account (signed up before the sale opens) using its JWT. There are **2,000 users
and 1,000 tickets**, so half of them must be told "sold out". After the rush the script reads the admin stats
to check that no ticket was sold twice.

## Setup

- Machine: Intel Core i5-1155G7 (4 cores / 8 threads), 16 GB RAM, Windows 11 Home.
- Everything on that one laptop at the same time: k6 with 2,000 virtual users, Spring Boot, PostgreSQL 18,
  Memurai (Redis-compatible) and the Next.js server.
- Backend in its default synchronous mode (`redis` strategy). Kafka was not running on this machine, so the
  async path (`TICKET_ASYNC=true`) is covered by the integration tests and the CI smoke test, not by this run.
- Date: 2026-11-13. Raw k6 output was saved locally under `results/` (git-ignored).

## Results

### 1. Straight to the backend (`BASE_URL=http://localhost:8080`), users arrive over 5 seconds

| | |
|---|---|
| HTTP requests | 10,290 (2,000 of them sign-ups during setup) |
| Failed requests | **0 (0.00%)** |
| Bookings confirmed | **1,000** of 1,000 tickets |
| Told "sold out" (409) | 1,000 |
| Oversold | **no** (1,000 reservations for 1,000 tickets) |
| Double clicks that returned the same booking | **287 of 287** |

| Endpoint | median | p95 | p99 |
|---|---|---|---|
| `GET /api/events` | 1,445 ms | 2,268 ms | 2,647 ms |
| `GET /api/events/{id}` | 1,076 ms | 1,838 ms | 1,990 ms |
| `POST /api/bookings` | 1,441 ms | 2,008 ms | 2,100 ms |
| `GET /api/me/orders` | 919 ms | 1,854 ms | 1,900 ms |

Latencies are high in absolute terms because the load generator and every server share 4 CPU cores.
Compare them with each other, not with a production setup.

### 2. Through the Next.js proxy (`BASE_URL=http://localhost:3000`), as the browser does

| Arrival window | Failed requests | Tickets sold | Double clicks deduplicated | Oversold |
|---|---|---|---|---|
| 2,000 users over 30 s | 7.4% (763 of 10,284) | 1,000 / 1,000 | 276 / 276 | no |
| 2,000 users over 5 s | 52.8% (5,368 of 10,176) | 567 / 1,000 | 135 / 135 | no |

Every failure was `connection refused` at port 3000: the single Node.js process that serves the pages and
proxies `/api` could not accept connections fast enough on this Windows laptop. Those requests never reached
the backend. Every request that **did** reach it was answered correctly: no oversell and no duplicate booking
in any run.

## What this means

- The booking path is correct under contention: exactly as many reservations as tickets, and the
  idempotency key turned every double click into the original booking.
- In this setup the bottleneck is the Next.js proxy, not the Spring backend. For a real flash sale, route
  `/api/*` straight to the backend at the load balancer or CDN (the paths are identical), or run several
  frontend instances. The browser code does not change.

## Run it yourself

```bash
docker compose up --build          # or run the backend and frontend separately
k6 run -e ADMIN_EMAIL=admin@example.com -e ADMIN_PASSWORD=change-me-admin-pass load-tests/ui-flow.js
# straight to the backend, users arriving over 5 s:
k6 run -e BASE_URL=http://localhost:8080 -e ARRIVAL_SECONDS=5 -e ADMIN_EMAIL=... -e ADMIN_PASSWORD=... load-tests/ui-flow.js
```

Options: `USERS` (2000), `TICKETS` (1000), `ARRIVAL_SECONDS` (5), `DOUBLE_CLICK_RATE` (0.3).
