# Deploying to the cloud (free tiers)

The app now reads its settings from environment variables, and the UI is served by Spring Boot itself
at `/`, so you deploy ONE service plus two managed databases.

| Piece | Free option | What you copy from it |
|---|---|---|
| App (Dockerfile) | Render, Railway or Fly.io | public URL |
| PostgreSQL | Neon or Supabase | host, db name, user, password |
| Redis | Upstash | `rediss://...` connection URL |

## Environment variables for the app

| Variable | Example |
|---|---|
| `DB_URL` | `jdbc:postgresql://<host>/<dbname>?sslmode=require` |
| `DB_USER` | `<user>` |
| `DB_PASSWORD` | `<password>` |
| `DB_POOL` | `5` (free Postgres tiers allow few connections) |
| `REDIS_URL` | `rediss://default:<password>@<host>:6379` |

`PORT` is set by the platform automatically.

Note: Neon shows a `postgresql://user:pass@host/db` string. The app needs the JDBC form above, with
user and password in their own variables.

## Steps (Render example)
1. Push the project to GitHub.
2. Create the Postgres (Neon) and Redis (Upstash) instances; copy their details.
3. Render: New > Web Service > connect the repo > Runtime: Docker.
4. Add the environment variables above, then deploy. Flyway creates the tables on first start.
5. Open the Render URL: the UI loads at `/`.

## Cautions
- Free web services sleep when idle, so the first request can take about a minute.
- Benchmark with k6 against `localhost` for README numbers. Against a cloud URL you mostly measure your own internet.
- `/reset` and the stats endpoints are open to anyone. Fine for a demo; add auth before sharing widely.
