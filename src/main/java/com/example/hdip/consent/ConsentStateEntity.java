package com.example.hdip.consent;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "consent_state")
public class ConsentStateEntity {
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
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ConsentStateEntity() {
    }

    public ConsentStateEntity(String mrnHash, ConsentScope scope) {
        this.mrnHash = mrnHash;
        this.scope = scope;
    }

    public void apply(ConsentStatus status, Instant effectiveAt, Instant now) {
        this.status = status;
        this.effectiveAt = effectiveAt;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getMrnHash() { return mrnHash; }
    public ConsentScope getScope() { return scope; }
    public ConsentStatus getStatus() { return status; }
    public Instant getEffectiveAt() { return effectiveAt; }
}
