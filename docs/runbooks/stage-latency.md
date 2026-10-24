# Runbook: HdipStageLatencyP95High

**SLO:** p95 latency of every pipeline stage (`normalize`, `enrich`, `extract`, `persist`, `persist_extraction`) stays below 1s.

**Metric:** `hdip_pipeline_stage_duration_seconds` (labels `stage`, `outcome` only; no PHI or ids).

## Triage
1. Grafana "HDIP pipeline" dashboard, panel "Stage p95 latency": which stage? Did it start with a deploy or a load change?
2. `persist` or `persist_extraction` slow: check Postgres (connections, locks, slow queries; `hikaricp_connections_pending`).
   `normalize` or `extract` slow: look for unusually large payloads (`hdip_ingest_messages_total` rate, payload cap is 1 MiB).
3. All stages slow: JVM pressure (`jvm_memory_used_bytes`, GC pauses), CPU throttling, or Kafka broker latency.
4. Traces (Tempo, if tracing is on via `OTEL_TRACING_SAMPLING`): find slow spans. Span attributes are PHI-masked.

## Mitigate
- Scale out consumers (more replicas up to the 3 partitions per topic) if CPU bound.
- Reduce ingest pressure: the per-principal rate limit is `INGEST_RATE_LIMIT_RPM`.
- Never paste payloads or logs containing identifiers into tickets; use message ids only.
