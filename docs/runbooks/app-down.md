# Runbook: HdipAppDown

**Meaning:** Prometheus cannot scrape any `hdip-app` target (`max(up) == 0`) for 2 minutes.

## Triage
1. Is the process or pod running? `docker compose ps` / `kubectl get pods -n hdip`.
2. Readiness: `/actuator/health/readiness` on the management port (8081). Common causes: Postgres or Kafka unreachable at startup,
   missing `PHI_*` secrets (the app refuses to start without keys), Flyway migration failure.
3. Scrape path only: the management port is not exposed outside the cluster/compose network by design; check the Prometheus target
   page for the error message.

## Mitigate
- Restore the failed dependency or secret and restart; roll back a bad release.
- While the app is down ingestion is unavailable (clients get connection errors); Kafka keeps already-accepted messages.
