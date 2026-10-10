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
