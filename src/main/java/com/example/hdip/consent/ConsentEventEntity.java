package com.example.hdip.consent;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "consent_event")
public class ConsentEventEntity {
    @Id
    private UUID id = UUID.randomUUID();
    @Column(name = "mrn_hash", nullable = false, length = 64)
    private String mrnHash;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ConsentScope scope;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ConsentStatus status;
    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;
    @Column(name = "recorded_by", nullable = false)
    private String recordedBy;
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt = Instant.now();

    protected ConsentEventEntity() {
    }

    public ConsentEventEntity(String mrnHash, ConsentScope scope, ConsentStatus status, Instant effectiveAt, String recordedBy, Instant recordedAt) {
        this.mrnHash = mrnHash;
        this.scope = scope;
        this.status = status;
        this.effectiveAt = effectiveAt;
        this.recordedBy = recordedBy;
        this.recordedAt = recordedAt;
    }

    public UUID getId() { return id; }
    public String getMrnHash() { return mrnHash; }
    public ConsentScope getScope() { return scope; }
    public ConsentStatus getStatus() { return status; }
    public Instant getEffectiveAt() { return effectiveAt; }
    public String getRecordedBy() { return recordedBy; }
    public Instant getRecordedAt() { return recordedAt; }
}
