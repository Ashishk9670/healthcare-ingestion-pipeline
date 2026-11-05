-- Structured facts extracted from free-text notes. Linked to a patient by MRN HMAC (no FK: the extraction topic and the
-- patient persist stage are independent consumers with no ordering guarantee). No note text is stored.
CREATE TABLE clinical_extraction (
    id           UUID PRIMARY KEY,
    mrn_hash     VARCHAR(64)  NOT NULL,
    message_id   VARCHAR(64),
    kind         VARCHAR(16)  NOT NULL,
    value        VARCHAR(255) NOT NULL,
    code         VARCHAR(64),
    extracted_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_extraction UNIQUE (mrn_hash, kind, value)
);
CREATE INDEX idx_extraction_mrn_hash ON clinical_extraction (mrn_hash);
CREATE INDEX idx_extraction_kind_value ON clinical_extraction (kind, value);
