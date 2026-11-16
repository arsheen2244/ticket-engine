#!/usr/bin/env bash
# End-to-end check of the running stack (docker compose up), through the frontend's /api proxy,
# exactly the way the browser calls it. Used by CI; also handy locally after `docker compose up`.
#   BASE=http://localhost:3000 ADMIN_EMAIL=... ADMIN_PASSWORD=... scripts/smoke-test.sh
set -euo pipefail

BASE="${BASE:-http://localhost:3000}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@example.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-change-me-admin-pass}"
JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT

step() { printf '\n== %s\n' "$*"; }
json() { python3 -c "import sys, json; print(json.load(sys.stdin)$1)"; }

step "Frontend serves pages"
curl -fsS -o /dev/null "$BASE/login"

step "Admin logs in and creates a sale"
ADMIN_TOKEN=$(curl -fsS -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" "$BASE/api/auth/login" | json "['token']")
EVENT_ID=$(curl -fsS -H 'Content-Type: application/json' -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"name":"Smoke test sale","totalTickets":5}' "$BASE/api/admin/events" | json "['id']")
echo "event $EVENT_ID"

step "Event list is public"
curl -fsS "$BASE/api/events" | grep -q "Smoke test sale"

step "Live stock stream sends the current count"
# The stream never ends by itself, so curl stops at --max-time (exit 28); keep what it received.
SSE=$(curl -sN --max-time 3 -H 'Accept: text/event-stream' "$BASE/api/events/$EVENT_ID/stream" || true)
echo "$SSE" | grep -m1 '"remaining":5'

step "A user signs up (cookie) and books with an idempotency key"
curl -fsS -c "$JAR" -H 'Content-Type: application/json' \
  -d "{\"email\":\"smoke-$RANDOM$RANDOM@example.com\",\"password\":\"smoke-pass-123\"}" "$BASE/api/auth/signup" >/dev/null
KEY="smoke-$(date +%s)-$RANDOM"
for i in 1 2; do # the second call is a "double click" with the same key
  curl -sS -b "$JAR" -o /dev/null -w "POST /api/bookings -> %{http_code}\n" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $KEY" -d "{\"eventId\":$EVENT_ID,\"qty\":2}" "$BASE/api/bookings"
done

step "Booking reaches PENDING_PAYMENT (queued through Kafka when TICKET_ASYNC=true, as in docker compose)"
for _ in $(seq 1 30); do
  STATUS=$(curl -fsS -b "$JAR" "$BASE/api/bookings/$KEY?eventId=$EVENT_ID" | json "['status']")
  echo "status $STATUS"
  [ "$STATUS" = "PENDING_PAYMENT" ] && break
  sleep 1
done
[ "$STATUS" = "PENDING_PAYMENT" ]

step "Exactly one order, and 3 tickets left"
[ "$(curl -fsS -b "$JAR" "$BASE/api/me/orders" | json '.__len__()')" = "1" ]
[ "$(curl -fsS "$BASE/api/events/$EVENT_ID" | json "['remaining']")" = "3" ]

step "Protected endpoints reject anonymous calls"
[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/me/orders")" = "401" ]
[ "$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" "$BASE/api/admin/stats")" = "403" ]

printf '\nSmoke test passed\n'
