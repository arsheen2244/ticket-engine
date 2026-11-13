# Runs the k6 rush against every strategy and prints a markdown table for the README.
# Prerequisites: Postgres, Redis, Kafka running ; app running with $env:TICKET_ASYNC = "true" ; k6 installed.
# Run from the project root:  .\run-benchmarks.ps1
# If the app was started with ADMIN_TOKEN, set the same value in this shell first:  $env:ADMIN_TOKEN = "..."

$base = "http://127.0.0.1:8080"
$strategies = @("naive", "pessimistic", "optimistic", "redis", "redis_async")

# Stop early if the app is not reachable
$health = curl.exe -s "$base/actuator/health"
if ($health -notmatch "UP") {
    Write-Error "App is not reachable at $base. Start it first (mvn spring-boot:run)."
    exit 1
}

New-Item -ItemType Directory -Force results | Out-Null
$rows = @()

foreach ($s in $strategies) {
    Write-Host "=== $s (running, takes a minute or two) ===" -ForegroundColor Cyan
    $k6Args = @("run", "--summary-export", "results/$s.json", "-e", "STRATEGY=$s", "-e", "BASE_URL=$base")
    if ($env:ADMIN_TOKEN) { $k6Args += @("-e", "ADMIN_TOKEN=$env:ADMIN_TOKEN") }
    & k6 @k6Args load-tests/rush.js *> "results/$s.log"

    $line = Select-String -Path "results/$s.log" -Pattern "RESULT strategy" | Select-Object -Last 1
    if (-not $line) {
        Write-Warning "No RESULT line for $s. Check results/$s.log"
        continue
    }

    $json = [regex]::Match($line.ToString(), '\{.*\}').Value -replace '\\"', '"'
    $stats = $json | ConvertFrom-Json

    $sum = Get-Content "results/$s.json" -Raw | ConvertFrom-Json
    $dur = $sum.metrics.http_req_duration
    $req = $sum.metrics.http_reqs
    $p95 = if ($dur.values) { $dur.values.'p(95)' } else { $dur.'p(95)' }
    $rps = if ($req.values) { $req.values.rate }   else { $req.rate }

    $oversold = if ($stats.oversold) { "YES" } else { "no" }
    $row = "| $s | $($stats.tickets_reserved) / $($stats.total_tickets) | $oversold | $([math]::Round($p95)) ms | $([math]::Round($rps)) |"
    Write-Host $row
    $rows += $row
    Start-Sleep -Seconds 3
}

$table = @("| Strategy | Tickets reserved / total | Oversold? | p95 latency | Req/s |", "|---|---|---|---|---|") + $rows
$table | Set-Content results/table.md
Write-Host "`nCopy this into README.md:`n" -ForegroundColor Green
$table