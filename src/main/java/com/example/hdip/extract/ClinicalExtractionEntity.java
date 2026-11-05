package com.example.hdip.extract;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "clinical_extraction")
public class ClinicalExtractionEntity {
    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "mrn_hash", nullable = false, length = 64)
    private String mrnHash;

    @Column(name = "message_id", length = 64)
    private String messageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExtractedFact.Kind kind;

    @Column(nullable = false)
    private String value;

    @Column(length = 64)
    private String code;

    @Column(name = "extracted_at", nullable = false)
    private Instant extractedAt = Instant.now();

    protected ClinicalExtractionEntity() {
    }

    public ClinicalExtractionEntity(String mrnHash, String messageId, ExtractedFact f, Instant at) {
        this.mrnHash = mrnHash;
        this.messageId = messageId;
        this.kind = f.kind();
        this.value = f.value();
        this.code = f.code();
        this.extractedAt = at;
    }

    public UUID getId() { return id; }
    public String getMrnHash() { return mrnHash; }
    public String getMessageId() { return messageId; }
    public ExtractedFact.Kind getKind() { return kind; }
    public String getValue() { return value; }
    public String getCode() { return code; }
    public Instant getExtractedAt() { return extractedAt; }
}
