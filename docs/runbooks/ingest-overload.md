# Runbook: HdipIngestShedding

**Meaning:** more ingest requests are in flight than the service can process, so it answers `503` with `Retry-After: 1`
immediately instead of queueing them (`hdip_ingest_rejected_total{reason="overloaded"}`). Clients that honour `Retry-After`
simply retry; nothing was accepted, so no data is lost.

## Why it happens
Ingestion is CPU-bound (FHIR validation, field encryption, Kafka acknowledgement) and runs in the same JVM as the pipeline
consumers. Measured on one CPU core, a burst of heavy requests drives the process to 100% CPU for minutes. Before the
concurrency limit existed, requests then queued until they timed out (about 60 s) and Prometheus scrapes failed, so the
lag metric went blind. The limit keeps latency bounded and the scrape endpoint responsive.

## Triage
1. Is it a burst or sustained? A short burst after a batch load is expected; the alert needs 2 minutes of shedding.
2. CPU: `process_cpu_usage`, and `kafka_consumer_fetch_manager_records_lag_max` for consumer backlog.
3. Who is sending? The ingest rate limit is per principal (`INGEST_RATE_LIMIT_RPM`); one principal can still saturate a
   small instance below that limit.

## Mitigate
- Scale out (more replicas) or raise the CPU limit; the concurrency limit scales with CPUs (`max(4, 2 x CPUs)`).
- Tune `INGEST_MAX_CONCURRENT` (0 = automatic). Higher values only add latency on a CPU-bound service.
- Ask the sender to batch less aggressively and honour `Retry-After`.
