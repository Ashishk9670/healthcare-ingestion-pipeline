-- Per-patient consent. consent_event is the append-only history; consent_state holds the effective status per
-- (patient, scope) and is what cohort/export queries join against. Patients are referenced by MRN HMAC (no plaintext MRN).
CREATE TABLE consent_event (
    id           UUID PRIMARY KEY,
    mrn_hash     VARCHAR(64)  NOT NULL,
    scope        VARCHAR(16)  NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    effective_at TIMESTAMPTZ  NOT NULL,
    recorded_by  VARCHAR(255) NOT NULL,
    recorded_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_consent_event_mrn ON consent_event (mrn_hash);

CREATE TABLE consent_state (
    id           UUID PRIMARY KEY,
    mrn_hash     VARCHAR(64) NOT NULL,
    scope        VARCHAR(16) NOT NULL,
    status       VARCHAR(16) NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_consent_state UNIQUE (mrn_hash, scope)
);
