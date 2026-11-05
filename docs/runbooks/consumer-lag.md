# Runbook: HdipConsumerLagHigh

**SLO:** no Kafka consumer is more than 1000 records behind for 10 minutes.

**Metric:** `kafka_consumer_fetch_manager_records_lag_max{client_id}` (Micrometer Kafka client metrics). If the series is absent,
check `/actuator/prometheus` on the management port for the exact name in your build.

## Triage
1. Which `client_id` / consumer group (`hdip-normalizer`, `hdip-enricher`, `hdip-persister`, `hdip-extractor`, `hdip-extraction-persister`)?
2. Lag with healthy stage latency: producer burst, wait for drain. Lag with slow stages: follow `stage-latency.md`.
3. Lag with stage errors: follow `stage-errors.md`.
4. Consumers not polling: app down or stuck in rebalance (look for repeated rebalance log lines), broker unreachable.

## Mitigate
- Add replicas (max useful = partitions, 3 per topic).
- Throttle producers (rate limit) while the backlog drains.
