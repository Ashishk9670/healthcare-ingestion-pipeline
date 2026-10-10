package com.example.hdip.kafka;

import com.example.hdip.enrich.EnrichmentService;
import com.example.hdip.model.CanonicalRecord;
import com.example.hdip.model.EnrichedRecord;
import com.example.hdip.model.RawMessage;
import com.example.hdip.observability.PipelineMetrics;
import com.example.hdip.observability.PipelineMetrics.Stage;
import com.example.hdip.parse.NormalizationService;
import com.example.hdip.store.PatientStoreService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Three Kafka stages: phi.raw -> (normalize) -> phi.normalized -> (enrich) -> phi.enriched -> (persist).
 * Messages are JSON strings. Payloads are never logged.
 */
@Component
public class PipelineStages {
    private static final Logger log = LoggerFactory.getLogger(PipelineStages.class);

    private final ObjectMapper mapper;
    private final KafkaTemplate<String, String> kafka;
    private final NormalizationService normalizer;
    private final EnrichmentService enricher;
    private final PatientStoreService store;
    private final PipelineMetrics metrics;

    public PipelineStages(ObjectMapper mapper, KafkaTemplate<String, String> kafka, NormalizationService normalizer,
                          EnrichmentService enricher, PatientStoreService store, PipelineMetrics metrics) {
        this.mapper = mapper;
        this.kafka = kafka;
        this.normalizer = normalizer;
        this.enricher = enricher;
        this.store = store;
        this.metrics = metrics;
    }

    @KafkaListener(topics = Topics.RAW, groupId = "hdip-normalizer")
    public void onRaw(String json) throws Exception {
        metrics.timeStage(Stage.NORMALIZE, () -> {
            RawMessage raw = mapper.readValue(json, RawMessage.class);
            CanonicalRecord canonical = normalizer.normalize(raw);
            kafka.send(Topics.NORMALIZED, raw.messageId(), mapper.writeValueAsString(canonical));
            log.info("Normalized message {} ({})", raw.messageId(), raw.format());
            return null;
        });
    }

    @KafkaListener(topics = Topics.NORMALIZED, groupId = "hdip-enricher")
    public void onNormalized(String json) throws Exception {
        metrics.timeStage(Stage.ENRICH, () -> {
            CanonicalRecord canonical = mapper.readValue(json, CanonicalRecord.class);
            EnrichedRecord enriched = enricher.enrich(canonical);
            kafka.send(Topics.ENRICHED, canonical.messageId(), mapper.writeValueAsString(enriched));
            log.info("Enriched message {}", canonical.messageId());
            return null;
        });
    }

    @KafkaListener(topics = Topics.ENRICHED, groupId = "hdip-persister")
    public void onEnriched(String json) throws Exception {
        metrics.timeStage(Stage.PERSIST, () -> {
            EnrichedRecord enriched = mapper.readValue(json, EnrichedRecord.class);
            store.persist(enriched);
            log.info("Persisted message {}", enriched.record().messageId());
            return null;
        });
    }
}
