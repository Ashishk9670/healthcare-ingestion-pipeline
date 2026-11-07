# Load test (k6)

Synthetic data only. Needs the compose stack and the app running (`make up && make run`) and k6 installed.

```bash
set -a; source .env; set +a
k6 run loadtest/ingest.js                       # defaults: 5 req/s for ~1.5 min
RATE=50 DURATION=2m k6 run loadtest/ingest.js   # heavier; expect 429s once the per-user limit is hit
```

Env: `APP_URL`, `KEYCLOAK_URL`, `RATE`, `DURATION`, `HDIP_PW_INGESTOR1`. The per-principal limit is
`INGEST_RATE_LIMIT_RPM` / `INGEST_RATE_LIMIT_BURST` (default 600/min, burst 100); raise it to measure raw throughput.
Watch the Grafana "HDIP pipeline" dashboard (`docker compose --profile observability up -d`) while it runs.

## What the load test showed (single container, 1 CPU core of headroom)
- `RATE=8`: 495 requests, 0 failures, p95 about 75 ms, all checks green.
- `RATE=60`: far beyond what one core can process (each iteration also generates and ingests synthetic patients, with HAPI FHIR
  validation). Before the concurrency limit, requests queued for up to 60 s, Prometheus scrapes timed out and the backlog took
  about 3 minutes to drain. With the limit, excess requests get an immediate `503` + `Retry-After` (`reason="overloaded"`),
  which the script counts as an expected, handled response. Use `HdipIngestShedding` and the lag alert to see it.

