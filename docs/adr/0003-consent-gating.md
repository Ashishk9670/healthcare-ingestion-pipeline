# ADR 0003: Per-patient consent gating

- Status: accepted

## Context

Cohort queries and bulk exports use patient data beyond direct care. Patients may withdraw (or never give) permission
for such secondary use, and that has to be enforced in one place, auditable, and without storing extra PHI.

## Decision

- Consent is recorded per patient and per scope: `COHORT` (inclusion in cohort aggregates) or `EXPORT` (inclusion in
  bulk de-identified exports). Status is `GRANTED` or `WITHDRAWN`.
- Storage (Flyway V3): `consent_event` is an append-only history (who recorded, when, effective time);
  `consent_state` holds the effective status per (patient, scope) and is what queries join against. Patients are
  referenced by the MRN HMAC (`mrn_hash`), never by plaintext MRN.
- Recording consent is a `CLINICIAN` action (`POST /api/v1/consent`) and writes an audit entry.
- Enforcement is a JPA `Specification` (`ConsentService.permitted(scope)`) applied inside the same query that selects
  patients, in both the cohort query (`PatientQueryService`) and the NDJSON export. There is no post-filtering and no code path that reads
  patients first and checks consent afterwards.
- Patients with no record are handled by `app.consent.default-policy`: `OPT_OUT` (default; included unless they
  withdrew) or `REQUIRE_EXPLICIT` (excluded unless they granted). The record with the latest `effective_at` wins:
  an event with an older effective time than the current state is kept in the history but not applied.
- Direct-care access (`patient(mrn)` for clinicians) is not consent-gated; consent covers secondary use only.

## Consequences

- Withdrawal takes effect on the next query/export; data already exported cannot be recalled (documented limitation).
- `REQUIRE_EXPLICIT` is the safer setting for research deployments; `OPT_OUT` keeps cohort counts useful by default.
- The append-only history supports audit questions ("who withdrew consent and when"); the state table keeps the hot
  path to a single indexed join.
- Alternatives considered: consent flag on the patient row (loses history, couples tables); filtering in application
  code after the query (easy to forget in a new endpoint); FHIR `Consent` resources (richer, but out of scope here).
