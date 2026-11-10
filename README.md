# Flash-Sale Ticket Booking Engine

A Spring Boot backend that sells a limited number of tickets to thousands of concurrent users **without overselling**. Five reservation strategies sit behind one endpoint so you can benchmark them against each other, from a deliberately broken baseline to an async Kafka pipeline.

**Stack:** Java 21, Spring Boot 3, PostgreSQL, Redis (Lua), Kafka, Flyway, JUnit 5, k6, GitHub Actions, Docker.

## Demo

Naive strategy oversells under concurrent load:

![Naive strategy oversold](docs/naive-oversold.png)

Redis Lua gate: exactly 1,000 sold, no overselling:

![Redis strategy safe](docs/redis-safe.png)

## Strategies

| Strategy | Idea |
|---|---|
| `naive` | read, check, write with no locking (oversells on purpose) |
| `pessimistic` | `SELECT ... FOR UPDATE` row lock |
| `optimistic` | version compare-and-swap with retries |
| `redis` | atomic Lua script gate in Redis, Postgres as source of truth |
| `redis_async` | Redis gate, then Kafka + transactional outbox; the API returns `202` before Postgres is written |

Safety nets that apply everywhere: `CHECK (available >= 0)`, a `UNIQUE` idempotency key, and compensation of Redis stock on failure.

## Architecture (async mode)

```
Client -> API -> Redis Lua gate (stock, per-user limit, duplicate key)
                    |
                    v
              Kafka: reservation-requests  --(3 retries)--> reservation-requests.DLT
                    |
                    v
   Consumer: ONE Postgres transaction = decrement stock + insert reservation + insert outbox row
                    |
                    v
   Outbox publisher (FOR UPDATE SKIP LOCKED) -> Kafka: reservation-events -> notification consumer
```

Full sequence diagram and failure table: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

Key design points:
- **Transactional outbox:** the event is written in the same transaction as the reservation, so a crash can never lose it or publish it for a reservation that was not stored.
- **At-least-once delivery with idempotent consumers:** redelivered messages are detected by idempotency key and skipped.
- **Dead-letter topic:** a poison message is retried 3 times, then parked on `reservation-requests.DLT`.
- **Reservation lifecycle:** reservations start as `PENDING_PAYMENT`. A mock payment endpoint confirms them, and an expiry job returns the tickets of unpaid ones after 5 minutes.
- **Reconciliation job:** Postgres is the source of truth. If Redis stock disagrees for two consecutive runs, it is reset (this also recovers from a Redis restart).

## Run it

Requirements: Java 21, Maven, Docker, [k6](https://k6.io/docs/get-started/installation/).

```bash
docker compose up -d          # Postgres, Redis, Kafka
mvn spring-boot:run           # sync strategies only, no Kafka needed
```

Async mode (enables `redis_async`, the consumer, outbox publisher and notification listener):

```bash
TICKET_ASYNC=true mvn spring-boot:run        # PowerShell: $env:TICKET_ASYNC = "true"
```

Running Kafka without Docker: see [docs/RUNNING-KAFKA.md](docs/RUNNING-KAFKA.md). A small UI is served at `http://localhost:8080/`.

## API

```bash
# create an event with 1000 tickets
curl -X POST localhost:8080/api/events -H 'Content-Type: application/json' \
  -d '{"name":"Concert","totalTickets":1000}'

# reserve (strategy: naive | pessimistic | optimistic | redis | redis_async)
curl -X POST 'localhost:8080/api/events/1/reserve?strategy=redis' \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: abc-123' \
  -d '{"userId":"u1","qty":2}'

curl localhost:8080/api/events/1/stats                    # includes "oversold": true/false
curl localhost:8080/api/reservations/by-key/abc-123       # outcome of an async reservation
curl -X POST localhost:8080/api/reservations/<id>/pay     # mock payment
curl -X POST localhost:8080/api/events/1/reset
```

Status codes: `201` reserved, `202` queued (async), `409` sold out or payment conflict, `429` per-user limit.

## Tests

```bash
mvn verify
```

Needs Postgres and Redis running. Kafka is not required, because the async test runs against an embedded broker. The same command runs on every push via GitHub Actions.

- `OversellingTest`: 500 parallel requests against 100 tickets for the pessimistic, optimistic and Redis strategies. Asserts exactly 100 sold, zero overselling, and that repeating one idempotency key creates exactly one reservation. Also checks the per-user limit.
- `AsyncFlowTest`: 200 concurrent async requests against 20 tickets. Asserts exactly 20 are accepted and stored, no overselling, and that the outbox drains to Kafka.

## Benchmark (500 virtual users, 10,000 requests, 1,000 tickets)

```powershell
.\run-benchmarks.ps1          # runs all five strategies and prints this table
```

Measured on <YOUR MACHINE: CPU, RAM, OS>, with Postgres, Redis and Kafka running locally. Absolute numbers depend on the machine, so compare the strategies against each other.

| Strategy | Tickets reserved / total | Oversold? | p95 latency | Req/s |
|---|---|---|---|---|
| naive | <PASTE> / 1000 | **YES** | 673 ms | 1578 |
| pessimistic | 1000 / 1000 | no | 532 ms | 2182 |
| optimistic | 1000 / 1000 | no | 1115 ms | 1697 |
| redis | 1000 / 1000 | no | 514 ms | 2408 |
| redis_async | <PASTE> / 1000 | no | <PASTE> | <PASTE> |

Reading the results:
- `naive` sells far more than 1,000 tickets, which is the race condition the other strategies exist to prevent.
- `optimistic` is correct but pays for retries under heavy contention.
- `redis` rejects excess traffic in Redis, so only winners reach Postgres.
- `redis_async` returns `202` before Postgres is written, so its latency measures acceptance, not durability. Throughput of the whole pipeline is limited by the consumer.

## Observability

Spring Boot Actuator exposes two endpoints (configured under `management.endpoints` in `application.yml`):

| Endpoint | What it gives you |
|---|---|
| `GET /actuator/health` | `{"status":"UP"}` when the app can reach its dependencies. Used as the Render health check. |
| `GET /actuator/prometheus` | Metrics in Prometheus text format (for example HTTP request timings, JVM memory and GC, and the Hikari connection pool). |

```bash
curl localhost:8080/actuator/health
curl localhost:8080/actuator/prometheus
```

## Deploy

The repo includes a [Render Blueprint](render.yaml) that defines the web service (Docker, free plan, health check on `/actuator/health`). In Render choose **New > Blueprint**, pick the repo, and fill in the secret values it asks for. Postgres and Redis come from Neon and Upstash free tiers. Full steps: [DEPLOY.md](DEPLOY.md).

## Known limits

- Single Kafka broker with replication factor 1 (development only).
- A crash after the Redis gate but before the Kafka publish leaves a decremented Redis counter; the reconciliation job repairs it.
- Per-user limit counters are not reconciled.
- `/reset` and the stats endpoints are unauthenticated (demo only).