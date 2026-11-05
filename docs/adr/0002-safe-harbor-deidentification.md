# ADR 0002: Safe Harbor de-identification approach

- Status: accepted
- This is engineering documentation, not a legal determination. Whether an output is de-identified is decided by
  the organisation's Privacy Officer.

## Context

Analysts need bulk data (FHIR NDJSON) without direct identifiers. HIPAA offers Expert Determination and Safe Harbor
(45 CFR 164.514(b)). Expert Determination needs a statistician per dataset; Safe Harbor is a fixed, testable list of
18 identifier categories.

## Decision

Implement Safe Harbor in `SafeHarborDeidentifier`, with `IdentifierCategory` enumerating the 18 categories (A to R):

- Structured FHIR: an allow-by-walk transformation that returns a deep copy.
  - Whole elements that are identifiers are dropped (`name`, `telecom`, `identifier`, `photo`, `url`, device and
    account fields, `text`, `meta`, `extension`, `contained`, `link`, ...).
  - `address` is reduced to state (and ZIP3, only if the area has more than 20,000 people; otherwise `000`).
  - Dates are reduced to year; `birthDate` is dropped entirely when the subject would be over 89, and free-text
    ages of 90 or more become `90+ years old`.
  - Resource ids and references survive only if they are random surrogate UUIDs; anything else is dropped.
  - Image attachments are dropped; text attachments are decoded, scrubbed and re-encoded.
- Free text: ordered regex rules, one family per category (email, URL, IP, SSN, labelled MRN/plan/account/license/
  vehicle/device/biometric ids, phone/fax, dates, addresses, ZIP, titled names, generic long codes). Callers can pass
  `knownTerms` (the subject's own names) which are always removed. Coded vocabulary fields only get the
  high-confidence rules, so LOINC/ICD codes and systems survive.
- Defence in depth: the scrubber never logs content, and the export is audited (see ADR 0003 for gating).

## Verification

- `SafeHarborDeidentifierTest`: one sample per category, in free text and inside a FHIR resource; the test fails if a
  category is added to the enum without a sample.
- `SafeHarborDeidentifierPropertyTest` (jqwik): generated identifiers for every category, in text and in whole
  bundles, must not appear in the output.

## Consequences

- Free-text name detection is best effort (titles and known terms). Untitled names in notes can be missed, which is
  why notes are excluded from the default export resources and why `knownTerms` exists.
- Over-scrubbing is accepted: false positives reduce utility but never leak.
- Utility loss (year-only dates, no geography below state) is inherent to Safe Harbor. Studies needing more must use
  a separate Expert Determination process.
- Alternatives considered: pseudonymisation with a keyed hash (still identifiers under HIPAA); NLP/ML NER for names
  (heavier dependency, non-deterministic, harder to test; can be added behind the same interface later).
