# ADR 0001: FHIR R4 validation with HAPI FHIR

- Status: accepted
- Context: synthetic data only; the design must still behave correctly for PHI.

## Context

`POST /api/v1/ingest/fhir` used to check only that the body was JSON with a `resourceType`. Malformed bundles (wrong
types, bad `dateTime` formats, missing required elements) were accepted onto `phi.raw` and failed later in the
normalizer, far from the caller and without a useful error.

## Decision

Validate FHIR R4 payloads at the API boundary with the HAPI FHIR instance validator (`FhirR4Validator`):

- Profile validation against the base R4 definitions bundled with HAPI (`hapi-fhir-validation-resources-r4`),
  with an in-memory terminology server. No network access and no external terminology service.
- Unknown profiles are not an error and extensions are allowed, so real-world producers are not rejected for
  using local extensions.
- Failure returns `422 Unprocessable Entity` with `application/fhir+json` and an `OperationOutcome`.
- The outcome keeps severity, issue type and element path. Quoted values in the validator message text are
  redacted, because those values come from the payload and may be PHI. The raw payload is never echoed or logged.
- The validator is created lazily and cached (building the validation chain is expensive).
- Validation is synchronous in the ingestion service, before anything is published to Kafka.

## Consequences

- Bad input is rejected early with a standard, machine-readable error; downstream stages can assume valid FHIR.
- Adds HAPI to the dependency set and some start-up/first-request latency (mitigated by lazy init and caching).
  Trivy/Dependabot watch the extra dependencies.
- Redaction makes messages less specific; callers use the element path to locate the problem.
- Only R4 is supported. Other versions would need a second `FhirContext` and an explicit version switch.
- Alternatives considered: hand-written checks (incomplete, drift from the spec); JSON-schema validation (does not
  capture FHIR invariants); an external validator service (adds a network hop that would carry PHI).
