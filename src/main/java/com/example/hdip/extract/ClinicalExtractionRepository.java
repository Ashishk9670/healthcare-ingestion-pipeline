package com.example.hdip.extract;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ClinicalExtractionRepository extends JpaRepository<ClinicalExtractionEntity, UUID> {
    boolean existsByMrnHashAndKindAndValue(String mrnHash, ExtractedFact.Kind kind, String value);

    List<ClinicalExtractionEntity> findByMrnHash(String mrnHash);
}
