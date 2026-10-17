package com.example.hdip.extract;

import com.example.hdip.crypto.AesGcmFieldCipher;
import com.example.hdip.kafka.Topics;
import com.example.hdip.model.CanonicalRecord;
import com.example.hdip.observability.PipelineMetrics;
import com.example.hdip.observability.PipelineMetrics.Stage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Clinical-note enrichment: phi.normalized -> (extract) -> phi.extracted -> (persist structured facts).
 * Runs in its own consumer groups, parallel to the main enrichment stage. Note text is never logged or persisted.
 */
@Component
public class ExtractionStage {
    private static final Logger log = LoggerFactory.getLogger(ExtractionStage.class);

    private final ObjectMapper mapper;
    private final KafkaTemplate<String, String> kafka;
    private final Extractor extractor;
    private final ExtractionStore store;
    private final AesGcmFieldCipher cipher;
    private final PipelineMetrics metrics;
    private final Clock clock;

    public ExtractionStage(ObjectMapper mapper, KafkaTemplate<String, String> kafka, Extractor extractor, ExtractionStore store,
                           AesGcmFieldCipher cipher, PipelineMetrics metrics, Clock clock) {
        this.mapper = mapper;
        this.kafka = kafka;
        this.extractor = extractor;
        this.store = store;
        this.cipher = cipher;
        this.metrics = metrics;
        this.clock = clock;
    }

    @KafkaListener(topics = Topics.NORMALIZED, groupId = "hdip-extractor")
    public void onNormalized(String json) throws Exception {
        metrics.timeStage(Stage.EXTRACT, () -> {
            CanonicalRecord rec = mapper.readValue(json, CanonicalRecord.class);
            if (rec.notes().isEmpty() || rec.patient() == null || rec.patient().mrn() == null) return null;
            Map<String, ExtractedFact> facts = new LinkedHashMap<>();
            for (String note : rec.notes()) {
                for (ExtractedFact f : extractor.extract(note)) facts.putIfAbsent(f.kind() + ":" + f.value(), f);
            }
            if (facts.isEmpty()) return null;
            var ev = new ExtractionEvent(rec.messageId(), cipher.hmac(rec.patient().mrn()), new ArrayList<>(facts.values()), Instant.now(clock));
            kafka.send(Topics.EXTRACTED, rec.messageId(), mapper.writeValueAsString(ev));
            log.info("Extracted {} facts for message {}", facts.size(), rec.messageId());
            return null;
        });
    }

    @KafkaListener(topics = Topics.EXTRACTED, groupId = "hdip-extraction-persister")
    public void onExtracted(String json) throws Exception {
        metrics.timeStage(Stage.PERSIST_EXTRACTION, () -> {
            ExtractionEvent ev = mapper.readValue(json, ExtractionEvent.class);
            int added = store.persist(ev);
            log.info("Persisted {} extracted facts for message {}", added, ev.messageId());
            return null;
        });
    }
}
