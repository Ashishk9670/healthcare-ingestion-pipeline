# Healthcare Data Ingestion Pipeline

> **SYNTHETIC DATA ONLY.** This project is a reference/demo implementation. Never load real PHI into it.
> All sample data comes from the built-in synthetic generator (`SyntheticDataGenerator`): invented names,
> `SYN-` MRNs, SSNs from the never-issued 9xx range. The dev encryption keys and Keycloak users in this
> repo are public placeholders, not secrets.

Spring Boot 3.5 / Java 21 / Maven. Ingests HL7 v2 and FHIR-like JSON, runs it through three Kafka stages,
stores it encrypted in PostgreSQL and serves aggregated patient/cohort views over GraphQL, with audit logging,
RBAC (Keycloak) and PHI-masked logs.

## Architecture

```
 HL7 v2 / FHIR JSON                    +-------------------------------------------+
 POST /api/v1/ingest/{hl7,fhir}  --->  | IngestionService: validate, audit, send   |
 (role INGESTOR, JWT)                  +---------------------+---------------------+
                                                             v
                                                       [ phi.raw ]            RawMessage envelope
                                                             v  PipelineStages.onRaw  (Hl7V2Parser / FhirJsonNormalizer)
                                                    [ phi.normalized ]        canonical patient/observation/diagnosis
                                                             v  PipelineStages.onNormalized (EnrichmentService)
                                                     [ phi.enriched ]         + age band, code displays
                                                             v  PipelineStages.onEnriched (PatientStoreService)
                                          PostgreSQL (Flyway, JPA): AES-GCM encrypted PHI columns, audit_entry
                                                             ^
 GraphQL  POST /graphql  (CLINICIAN | ANALYST | AUDITOR) ----+   every access -> audit_entry
 Dashboard UI  GET /  (Keycloak login, auth code + PKCE)
```

| Concern | Implementation |
|---|---|
| Ingestion | `api/IngestController`, `api/IngestionService` (also `POST /api/v1/synthetic?count=N` to generate + ingest fake patients) |
| Kafka stages | `kafka/PipelineStages` (3 `@KafkaListener`s), topics `phi.raw`, `phi.normalized`, `phi.enriched` |
| Parsing | `parse/Hl7V2Parser` (MSH/PID/OBX/DG1), `parse/FhirJsonNormalizer` (Bundle: Patient/Observation/Condition) |
| Enrichment | `enrich/EnrichmentService`: age band (`0-17 ... 80+`), ICD-10/LOINC display lookup |
| Storage | `store/*`, `db/migration/V1__init.sql` (Flyway) |
| Field encryption | `crypto/AesGcmFieldCipher` + JPA `EncryptedStringConverter`; MRN lookup via keyed HMAC (`mrn_hash`) |
| Audit | `audit/AuditService` persists an entry for every ingest, pipeline persist, patient view, cohort query and audit-log read (no PHI in entries; MRNs are HMACed) |
| RBAC | `security/SecurityConfig`: OAuth2 resource server, Keycloak `realm_access.roles` -> `ROLE_*` |
| Log masking | `logging/PhiMasker`, logback converters registered in `logback-spring.xml` |
| GraphQL | `graphql/PatientGraphQlController`, `resources/graphql/schema.graphqls` |
| UI | `resources/static/*` (vanilla JS dashboard) |

### Roles

| Role | REST ingest + synthetic | `patient(mrn)` | `cohort(...)` (de-identified) | `auditLog` |
|---|:-:|:-:|:-:|:-:|
| INGESTOR | yes | no | no | no |
| CLINICIAN | no | yes | yes | no |
| ANALYST | no | no | yes | no |
| AUDITOR | no | no | no | yes |

Dev users (realm `healthcare`, see `docker/keycloak/healthcare-realm.json`): `ingestor1`, `clinician1`,
`analyst1`, `auditor1`; password is `<username>-dev`. Client `pipeline-client` (public; auth code + PKCE, and
password grant for tests/scripts).

### Compliance notes / limits
- Sensitive columns (MRN, names, DOB, SSN, free-text observation values) are AES-256-GCM encrypted
  (`base64(iv || ciphertext || tag)`). Keys come from `app.crypto.*` / `PHI_ENCRYPTION_KEY`, `PHI_HMAC_KEY`
  (base64). Use a KMS/secret manager and key rotation for anything beyond a demo.
- Diagnosis codes, age band, gender and numeric observation values are stored in clear to enable cohort queries.
- Kafka payloads carry PHI in clear JSON; production would need TLS + encryption/ACLs on the brokers.
- Logs: all messages and exception text pass through `PhiMasker` (SSN pattern, name/DOB/MRN key-value pairs,
  HL7 `PID|` segments, `MRN-`/`SYN-` tokens). Payloads are never logged deliberately. Masking is best-effort.
- This is not a certified HIPAA implementation.

## Run it

Prereqs: JDK 21+, Maven 3.9+, Docker.

```bash
docker compose up -d --wait         # Postgres :5432, Kafka :9092, Keycloak :8180 (realm auto-imported)
mvn spring-boot:run                 # app on :8080 (Flyway migrates the DB, topics are auto-created)
```

Get a token and ingest synthetic data:

```bash
TOKEN=$(curl -s -d grant_type=password -d client_id=pipeline-client -d username=ingestor1 -d password=ingestor1-dev \
  http://localhost:8180/realms/healthcare/protocol/openid-connect/token | jq -r .access_token)

curl -X POST -H "Authorization: Bearer $TOKEN" "localhost:8080/api/v1/synthetic?count=25"       # generator
curl -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: text/plain' --data-binary @sample.hl7 localhost:8080/api/v1/ingest/hl7
curl -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d @bundle.json localhost:8080/api/v1/ingest/fhir
```

Query as `clinician1` / `analyst1` (GraphQL):

```bash
curl -s -H "Authorization: Bearer $CLIN_TOKEN" -H 'Content-Type: application/json' localhost:8080/graphql -d '{
  "query":"{ cohort(filter:{diagnosisCode:\"E11\", ageBand:\"50-64\"}){ totalPatients byAgeBand{key count} byDiagnosis{key count} } }"}'
```

Or open http://localhost:8080/ and sign in (cohort dashboard; panels depend on role).

## Tests

| Layer | What | Command | Needs Docker |
|---|---|---|:-:|
| Unit | HL7/FHIR parsing, masking, AES-GCM, enrichment, synthetic generator, Kafka stage wiring (Mockito), ingestion service, store service, role converter, REST security with mock JWT (`@WebMvcTest`), GraphQL RBAC (`@GraphQlTest`) | `mvn test` (or `make test`) | no |
| Coverage | JaCoCo report in `target/site/jacoco/index.html` (unit) and `jacoco-it` (integration) | `mvn verify -DskipITs` / `mvn verify` | no / yes |
| Integration + API | `*IT` classes (Failsafe) with Testcontainers Postgres, Kafka and Keycloak (same realm export): full pipeline REST->Kafka->Postgres, encrypted-at-rest check, REST + GraphQL role matrix with real Keycloak JWTs, audit-log assertions | `mvn verify` (or `make it`) | yes |
| E2E UI | Playwright (`e2e/`): Keycloak login, per-role panels, cohort filters, audit view, synthetic ingest | `make up && make run`, then `make e2e` (first `make e2e-install`); or `make e2e-local` / `scripts/e2e.sh` for all-in-one | yes |

CI (`.github/workflows/ci.yml`): build + unit + JaCoCo, then integration tests, then docker-compose + Playwright e2e
with the HTML report uploaded as an artifact.

Note: the unit tests set `-Dnet.bytebuddy.experimental=true` so Mockito works on newer JDKs than the one it was built for.

## Layout

```
docker-compose.yml, docker/keycloak/healthcare-realm.json
src/main/java/com/example/hdip/{api,audit,config,crypto,enrich,graphql,kafka,logging,model,parse,security,store,synthetic}
src/main/resources/{application.yml,logback-spring.xml,db/migration,graphql,static}
src/test/java/...            unit tests (*Test) and Testcontainers integration tests (it/*IT)
e2e/                         Playwright tests
```
