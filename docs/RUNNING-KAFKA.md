# Running the async mode

Default (`TICKET_ASYNC` unset): the app works exactly as before and needs no Kafka.

## Tests need no Kafka install
`mvn test` runs `AsyncFlowTest` against an in-JVM broker. Postgres and Redis must be running.

## Running the real app with Kafka

### Option A: Docker (if it works on your machine)
```powershell
docker compose up -d
$env:TICKET_ASYNC = "true"
mvn spring-boot:run
```

### Option B: Kafka natively on Windows (no Docker, no WSL)
1. Download the binary `.tgz` of Kafka 3.9.x from kafka.apache.org/downloads and extract it to a SHORT path such as `E:\kafka` (long paths break the Windows scripts).
2. In a new PowerShell window:
   ```powershell
   cd E:\kafka
   .\bin\windows\kafka-storage.bat random-uuid
   .\bin\windows\kafka-storage.bat format -t <PASTE-THE-ID> -c .\config\kraft\server.properties
   .\bin\windows\kafka-server-start.bat .\config\kraft\server.properties
   ```
   Leave it running. If a path differs in your download, look inside the extracted folder.
3. In another window, from the project folder:
   ```powershell
   $env:TICKET_ASYNC = "true"
   mvn spring-boot:run
   ```
Kafka on Windows is not officially supported; it is fine for demos, but if it crashes on log cleanup, restart it.

## Demo
1. Open http://localhost:8080/, create an event, choose `redis + Kafka (async)` and click Reserve. The log shows `202 queued`, then `stored by consumer`.
2. Watch the app console: the consumer writes the reservation, and `[notification] would send a confirmation email` appears once the outbox publisher relays the event.
3. Click **Pay last reservation**. The status becomes CONFIRMED.
4. Reserve again and do not pay. After 5 minutes the expiry job returns the ticket (check the Reserved count drop).
5. Reconciliation: in Memurai CLI run `set stock:<eventId> 5`. About 1 to 2 minutes later the app log shows `Reconciled event ...`.
6. Dead-letter demo: `kafka-console-producer` a non-JSON line to `reservation-requests`; after 3 retries it appears on `reservation-requests.DLT`.

## Load test
```powershell
k6 run -e STRATEGY=redis_async load-tests/rush.js
```
The 202 responses return before Postgres is written, so compare latency with the sync `redis` run, and note that throughput of the whole pipeline is the consumer's.
