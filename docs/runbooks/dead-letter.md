# Runbook: HdipMessagesDeadLettered and HdipIngestPublishFailing

## How messages are protected
- **Ingest:** `POST /api/v1/ingest/*` answers `202` only after Kafka acknowledged the message (`acks=all`, idempotent producer).
  If Kafka does not acknowledge within the delivery timeout the API answers `503` with `Retry-After`, nothing is audited as
  accepted, and `hdip_ingest_rejected_total{reason="publish_failed"}` increments. Clients must retry.
- **Stages:** each stage publishes to the next topic and waits for the acknowledgement before its input offset is committed. A failure
  is retried with exponential backoff (500ms up to 10s, for at most 60s). Nothing is skipped silently.
- **Dead letters:** a record that still fails, or can never succeed (unparseable JSON), is published to `<topic>.dlt`
  (`phi.raw.dlt`, `phi.normalized.dlt`, `phi.enriched.dlt`, `phi.extracted.dlt`) and counted in
  `hdip_pipeline_dead_lettered_total{topic}`. Headers carry the original topic, partition, offset and the exception **class** only.

**PHI warning:** dead-letter topics hold the original payload, so they contain the same PHI as the source topic. Apply identical
access controls, never copy records into tickets or chat, and delete them once handled.

## HdipIngestPublishFailing
Kafka is down or unreachable from the app. Check broker health and network policy; ingest recovers on its own once Kafka returns.
No accepted data is lost, because rejected requests were never accepted.

## HdipMessagesDeadLettered
1. Which topic? The label names the *source* topic (`phi.raw` = normalize stage failed, and so on).
2. Why? The `kafka_dlt-exception-fqcn` header names the exception class. A JSON or parse exception means bad input that passed
   API validation; anything else usually means a defect or a dependency outage that outlasted the 60s retry window.
3. Fix the cause first (roll back a release, restore Postgres or Kafka).
4. Reprocess: re-publish the dead-lettered records to the source topic (preserving the key). Persistence is idempotent per MRN hash,
   so re-delivery cannot create duplicate patients. Records that are genuinely malformed should be dropped and re-sent by the source.
