CREATE TABLE patient (
    id               UUID PRIMARY KEY,
    mrn_hash         VARCHAR(64) NOT NULL UNIQUE,
    mrn_enc          TEXT        NOT NULL,
    family_name_enc  TEXT,
    given_name_enc   TEXT,
    birth_date_enc   TEXT,
    ssn_enc          TEXT,
    gender           VARCHAR(16),
    age_band         VARCHAR(16),
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_patient_age_band ON patient (age_band);
CREATE INDEX idx_patient_gender ON patient (gender);

CREATE TABLE observation (
    id             UUID PRIMARY KEY,
    patient_id     UUID NOT NULL REFERENCES patient (id) ON DELETE CASCADE,
    code           VARCHAR(64),
    system         VARCHAR(255),
    display        VARCHAR(512),
    value_text_enc TEXT,
    value_numeric  NUMERIC(18, 4),
    unit           VARCHAR(64),
    effective_at   TIMESTAMPTZ
);
CREATE INDEX idx_observation_patient ON observation (patient_id);

CREATE TABLE diagnosis (
    id         UUID PRIMARY KEY,
    patient_id UUID NOT NULL REFERENCES patient (id) ON DELETE CASCADE,
    code       VARCHAR(64),
    system     VARCHAR(255),
    display    VARCHAR(512)
);
CREATE INDEX idx_diagnosis_patient ON diagnosis (patient_id);
CREATE INDEX idx_diagnosis_code ON diagnosis (code);

CREATE TABLE audit_entry (
    id            UUID PRIMARY KEY,
    timestamp     TIMESTAMPTZ  NOT NULL,
    actor         VARCHAR(255) NOT NULL,
    roles         VARCHAR(512),
    action        VARCHAR(64)  NOT NULL,
    resource_type VARCHAR(64),
    resource_id   VARCHAR(255),
    detail        TEXT
);
CREATE INDEX idx_audit_timestamp ON audit_entry (timestamp DESC);
