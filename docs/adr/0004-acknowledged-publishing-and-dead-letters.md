# ADR 0004: Acknowledged publishing and dead-letter topics

Status: accepted

## Context
Every Kafka send in the pipeline was fire-and-forget. The ingest API answered `202` and each stage committed its input offset
before the broker had acknowledged the next message. If Kafka was unavailable for longer than the producer's delivery timeout
the send failed in the background and the record was lost although the client had been told it was accepted. A record that
kept failing in a consumer was retried a few times by the default error handler and then skipped, again without a trace.

## Decision
- `KafkaPublisher.publish` sends and blocks on the acknowledgement. The producer is idempotent with `acks=all`, bounded
  `max.block.ms` (5s) and `delivery.timeout.ms` (15s); the wait (20s) is longer than the delivery timeout so the outcome is
  never ambiguous.
- The API reports an unacknowledged publish as `503` with `Retry-After`, does not write the INGEST audit entry and counts
  `hdip_ingest_rejected_total{reason="publish_failed"}`. "Accepted" therefore means "durably in Kafka".
- A stage that cannot publish throws, so the listener does not commit its input offset and the record is redelivered.
- `DefaultErrorHandler` retries with exponential backoff (500ms to 10s, 60s total), then `DeadLetterPublishingRecoverer`
  publishes to `<topic>.dlt` (same partition). JSON, parse and illegal-argument errors skip the retries because they cannot succeed.
- Dead-letter headers keep only the root exception class. Spring's default also copies the exception message and stack trace,
  and Jackson messages can quote payload text, which would put PHI into headers.

## Consequences
- No accepted message is lost to a broker outage, and nothing is skipped silently. Both properties are covered by `ChaosIT`
  (Kafka down for longer than the delivery timeout) and `DeadLetterIT`.
- Redelivery can duplicate work; persistence is already idempotent per MRN hash.
- A stage that publishes then fails to commit its offset can emit the downstream record twice (at-least-once). Downstream stages
  are idempotent for the same reason.
- Dead-letter topics contain the original payload and need the same access controls as the source topics. Alerts:
  `HdipMessagesDeadLettered`, `HdipIngestPublishFailing`.
- The ingest request thread now waits up to the delivery timeout during an outage; callers should use client timeouts above 20s
  or rely on the `503`.
