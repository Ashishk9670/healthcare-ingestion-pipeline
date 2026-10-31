# Runbook: HdipStageErrorRateHigh and HdipStageErrorBudgetFastBurn

**SLO:** at least 99% of executions of each stage succeed. Fast burn pages at 14.4x budget consumption (5m and 1h windows).

**Metric:** `hdip_pipeline_messages_total{stage,outcome}`. A failing record is retried with backoff and then published to
`<topic>.dlt` (see `dead-letter.md`), so a sustained failure ratio means records are accumulating in dead-letter topics until
the cause is fixed. They are not dropped, but they are not processed either: treat this alert as delayed data.

## Triage
1. Which stage? `persist*` failures usually mean Postgres is unreachable or rejecting writes; `normalize` failures mean unparseable input
   that passed API validation (check recent client changes); `enrich`/`extract` failures point to a code defect.
2. Application logs (Loki, label `service="app"`) are PHI-masked, but never copy raw payloads out of Kafka into tickets.
   Search for `ERROR` with the stage logger name.
3. Postgres or Kafka outage: see `docs/` chaos test notes; after recovery consumers resume from committed offsets.

## Mitigate
- Dependency outage: restore it, then confirm the failure ratio returns to 0 and consumer lag drains.
- Defect: roll back the release (`kubectl rollout undo` or redeploy previous image).
- If messages were skipped during the incident, re-ingest from the source system (synthetic data in dev: call `/api/v1/synthetic`).
