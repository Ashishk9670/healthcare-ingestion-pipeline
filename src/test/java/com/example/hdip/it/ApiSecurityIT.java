package com.example.hdip.it;

import com.example.hdip.audit.AuditEntry;
import com.example.hdip.audit.AuditService;
import com.example.hdip.crypto.AesGcmFieldCipher;
import com.example.hdip.model.*;
import com.example.hdip.store.PatientStoreService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** REST + GraphQL role matrix against real Keycloak-issued JWTs, with audit-log assertions. */
class ApiSecurityIT extends AbstractIntegrationTest {
    static final String MRN = "SYN-API-0001";
    static final String QUERY_PATIENT = "{ patient(mrn:\"" + MRN + "\"){ mrn familyName givenName ageBand diagnoses{code} observations{code valueNumeric} } }";
    static final String QUERY_COHORT = "{ cohort(filter:{diagnosisCode:\"E11\", ageBand:\"50-64\"}){ totalPatients byAgeBand{key count} members{patientRef ageBand diagnosisCodes} } }";
    static final String QUERY_AUDIT = "{ auditLog(limit: 200){ actor action resourceType detail } }";

    @Autowired PatientStoreService store;
    @Autowired AuditService audit;
    @Autowired AesGcmFieldCipher cipher;
    static boolean seeded;

    @BeforeAll
    static void noop() {
    }

    private void seed() {
        if (seeded) return;
        var p = new CanonicalPatient(MRN, "Testerson", "Alex", LocalDate.of(1965, 3, 15), "male", "900-00-0001");
        var rec = new CanonicalRecord("seed-1", "HL7V2", p,
                List.of(new CanonicalObservation("4548-4", "LN", "A1c", null, new java.math.BigDecimal("7.2"), "%", Instant.now())),
                List.of(new CanonicalDiagnosis("E11.9", "I10", "Type 2 diabetes")));
        store.persist(new EnrichedRecord(rec, "50-64", Instant.now()));
        seeded = true;
    }

    private static boolean denied(Resp r) {
        if (r.status() == 401 || r.status() == 403) return true;
        JsonNode errors = r.json().get("errors");
        if (errors == null || errors.isEmpty()) return false;
        JsonNode data = r.json().path("data");
        if (data.isMissingNode() || data.isNull()) return true;
        for (JsonNode v : data) if (!v.isNull()) return false;
        return true;
    }

    // ---- REST ----

    @Test
    void restIngestRoleMatrix() {
        String hl7 = "MSH|^~\\&|A|B|C|D|2026||ORU^R01|1|P|2.5\rPID|1||SYN-API-R1^^^S^MR||Faux^Casey||19800101|F";
        assertThat(http("POST", "/api/v1/ingest/hl7", null, "text/plain", hl7).status()).isEqualTo(401);
        for (String user : List.of("clinician1", "analyst1", "auditor1")) {
            assertThat(http("POST", "/api/v1/ingest/hl7", token(user), "text/plain", hl7).status()).as(user).isEqualTo(403);
            assertThat(http("POST", "/api/v1/synthetic?count=1", token(user), null, null).status()).as(user).isEqualTo(403);
        }
        assertThat(http("POST", "/api/v1/ingest/hl7", token("ingestor1"), "text/plain", hl7).status()).isEqualTo(202);
    }

    @Test
    void tamperedTokenRejected() {
        String t = token("ingestor1");
        String bad = t.substring(0, t.length() - 3) + (t.endsWith("AAA") ? "BBB" : "AAA");
        assertThat(http("POST", "/api/v1/ingest/hl7", bad, "text/plain", "MSH|x").status()).isEqualTo(401);
    }

    @Test
    void actuatorOnlyOnManagementPort() throws Exception {
        assertThat(mgmt("/actuator/health/readiness")).isEqualTo(200);
        assertThat(mgmt("/actuator/prometheus")).isEqualTo(200);
        // not reachable through the public API port
        assertThat(http("GET", "/actuator/prometheus", null, null, null).status()).isIn(401, 404);
    }

    private int mgmt(String path) throws Exception {
        var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + MANAGEMENT_PORT + path)).GET().build();
        return HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    // ---- GraphQL ----

    @Test
    void graphqlRequiresAuthentication() {
        assertThat(graphql(null, QUERY_COHORT).status()).isEqualTo(401);
    }

    @Test
    void clinicianSeesPatientDetailAndCohort() {
        seed();
        var r = graphql(token("clinician1"), QUERY_PATIENT);
        assertThat(r.status()).isEqualTo(200);
        JsonNode p = r.json().at("/data/patient");
        assertThat(p.get("familyName").asText()).isEqualTo("Testerson");
        assertThat(p.get("ageBand").asText()).isEqualTo("50-64");
        assertThat(p.at("/diagnoses/0/code").asText()).isEqualTo("E11.9");
        assertThat(p.at("/observations/0/valueNumeric").asDouble()).isEqualTo(7.2);

        JsonNode c = graphql(token("clinician1"), QUERY_COHORT).json().at("/data/cohort");
        assertThat(c.get("totalPatients").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void cohortFiltersApply() {
        seed();
        String none = "{ cohort(filter:{diagnosisCode:\"C50\", ageBand:\"80+\"}){ totalPatients } }";
        assertThat(graphql(token("analyst1"), none).json().at("/data/cohort/totalPatients").asInt()).isZero();
        String female = "{ cohort(filter:{gender:\"female\", diagnosisCode:\"E11\", ageBand:\"50-64\"}){ totalPatients } }";
        assertThat(graphql(token("analyst1"), female).json().at("/data/cohort/totalPatients").asInt()).isZero();
    }

    @Test
    void analystGetsDeidentifiedCohortButNoPatientDetail() {
        seed();
        String t = token("analyst1");
        var cohort = graphql(t, QUERY_COHORT);
        assertThat(cohort.status()).isEqualTo(200);
        assertThat(cohort.body()).doesNotContain("Testerson", MRN);
        assertThat(cohort.json().at("/data/cohort/totalPatients").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(denied(graphql(t, QUERY_PATIENT))).isTrue();
        assertThat(denied(graphql(t, QUERY_AUDIT))).isTrue();
    }

    @Test
    void ingestorHasNoGraphqlDataAccess() {
        seed();
        String t = token("ingestor1");
        assertThat(denied(graphql(t, QUERY_PATIENT))).isTrue();
        assertThat(denied(graphql(t, QUERY_COHORT))).isTrue();
        assertThat(denied(graphql(t, QUERY_AUDIT))).isTrue();
    }

    @Test
    void auditorReadsAuditLogOnly() {
        seed();
        graphql(token("clinician1"), QUERY_PATIENT); // generate an access entry
        String t = token("auditor1");
        var r = graphql(t, QUERY_AUDIT);
        assertThat(r.status()).isEqualTo(200);
        JsonNode log = r.json().at("/data/auditLog");
        assertThat(log.isArray()).isTrue();
        boolean sawView = false;
        for (JsonNode e : log) {
            if ("PATIENT_VIEW".equals(e.get("action").asText()) && "clinician1".equals(e.get("actor").asText())) sawView = true;
        }
        assertThat(sawView).isTrue();
        assertThat(r.body()).doesNotContain(MRN, "Testerson"); // audit log carries no PHI
        assertThat(denied(graphql(t, QUERY_PATIENT))).isTrue();
        assertThat(denied(graphql(t, QUERY_COHORT))).isTrue();
    }

    @Test
    void everyAccessIsAudited() {
        seed();
        graphql(token("clinician1"), QUERY_PATIENT);
        graphql(token("analyst1"), QUERY_COHORT);
        List<AuditEntry> entries = audit.latest(500);
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.getAction()).isEqualTo("PATIENT_VIEW");
            assertThat(e.getActor()).isEqualTo("clinician1");
            assertThat(e.getResourceId()).isEqualTo(cipher.hmac(MRN));
            assertThat(e.getRoles()).contains("ROLE_CLINICIAN");
        });
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.getAction()).isEqualTo("COHORT_QUERY");
            assertThat(e.getActor()).isEqualTo("analyst1");
            assertThat(e.getDetail()).doesNotContain(MRN);
        });
    }

    @Test
    void deidentifiedNdjsonExportIsAnalystOnlyAndAudited() {
        seed();
        assertThat(http("GET", "/api/v1/export/fhir/ndjson", null, null, null).status()).isEqualTo(401);
        for (String u : List.of("ingestor1", "clinician1", "auditor1")) {
            assertThat(http("GET", "/api/v1/export/fhir/ndjson", token(u), null, null).status()).as(u).isEqualTo(403);
        }
        var r = http("GET", "/api/v1/export/fhir/ndjson?limit=500", token("analyst1"), null, null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("\"resourceType\":\"Patient\"").contains("ANONYED");
        assertThat(r.body()).doesNotContain("Testerson", "Alex", MRN, "900-00-0001", "1965-03-15");
        assertThat(audit.latest(500)).anySatisfy(e -> {
            assertThat(e.getAction()).isEqualTo("EXPORT_FHIR_NDJSON_DONE");
            assertThat(e.getActor()).isEqualTo("analyst1");
        });
    }
}
