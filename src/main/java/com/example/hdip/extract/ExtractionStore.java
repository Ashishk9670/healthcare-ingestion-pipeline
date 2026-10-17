package com.example.hdip.extract;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Idempotent persistence of extraction events (Kafka delivery is at-least-once). */
@Service
public class ExtractionStore {
    private final ClinicalExtractionRepository repo;

    public ExtractionStore(ClinicalExtractionRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public int persist(ExtractionEvent ev) {
        int added = 0;
        for (ExtractedFact f : ev.facts()) {
            if (!repo.existsByMrnHashAndKindAndValue(ev.mrnHash(), f.kind(), f.value())) {
                repo.save(new ClinicalExtractionEntity(ev.mrnHash(), ev.messageId(), f, ev.extractedAt()));
                added++;
            }
        }
        return added;
    }
}
