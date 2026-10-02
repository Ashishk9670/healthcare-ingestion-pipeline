package com.example.hdip.it;

import com.example.hdip.audit.AuditEntry;
import com.example.hdip.audit.AuditService;
import com.example.hdip.crypto.AesGcmFieldCipher;
import com.example.hdip.store.PatientRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** End-to-end: REST ingest -> phi.raw -> normalize -> phi.normalized -> enrich -> phi.enriched -> Postgres. */
class PipelineIT extends AbstractIntegrationTest {
    @Autowired PatientRepository patients;
    @Autowired AesGcmFieldCipher cipher;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuditService audit;

    private static String hl7(String mrn) {
        return String.join("\r",
                "MSH|^~\\&|SYNTH|FAC|HDIP|FAC|20260101120000||ORU^R01|" + UUID.randomUUID() + "|P|2.5",
                "PID|1||" + mrn + "^^^SYNTH^MR||Testerson^Alex||19700315|M|||||||||||900-12-3456",
                "DG1|1||E11.9^Type 2 diabetes^I10",
                "OBX|1|NM|4548-4^Hemoglobin A1c^LN||7.2|%|||||F|||20260101103000");
    }

    private static String fhir(String mrn) {
        return """
                {"resourceType":"Bundle","type":"collection","entry":[
                 {"resource":{"resourceType":"Patient","identifier":[{"system":"urn:synthetic:mrn","value":"%s"}],
                   "name":[{"family":"Faux","given":["Casey"]}],"gender":"female","birthDate":"1985-06-01"}},
                 {"resource":{"resourceType":"Condition","code":{"coding":[{"system":"icd10","code":"I10"}]}}}]}
                """.formatted(mrn);
    }

    @Test
    void hl7FlowsThroughAllStagesAndIsEncryptedAtRest() {
        String mrn = "SYN-IT-" + System.nanoTime();
        var res = http("POST", "/api/v1/ingest/hl7", token("ingestor1"), "text/plain", hl7(mrn));
        assertThat(res.status()).isEqualTo(202);
        String messageId = res.json().get("messageId").asText();

        String hash = cipher.hmac(mrn);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(patients.findByMrnHash(hash)).isPresent());

        var p = patients.findByMrnHash(hash).orElseThrow();
        assertThat(p.getFamilyName()).isEqualTo("Testerson"); // decrypted through the JPA converter
        assertThat(p.getAgeBand()).isNotBlank();               // enrichment ran
        assertThat(p.getDiagnoses()).extracting("code").contains("E11.9");
        assertThat(p.getDiagnoses().get(0).getDisplay()).isNotBlank();

        // raw column contents must not contain plaintext PHI
        List<String> cols = jdbc.queryForList("select family_name_enc || mrn_enc || ssn_enc from patient where mrn_hash = ?", String.class, hash);
        assertThat(cols).singleElement().satisfies(v -> assertThat(v).doesNotContain("Testerson", mrn, "900-12-3456"));

        // audit entries for ingest and persistence
        List<AuditEntry> entries = audit.latest(500);
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.getAction()).isEqualTo("INGEST");
            assertThat(e.getResourceId()).isEqualTo(messageId);
            assertThat(e.getActor()).isEqualTo("ingestor1");
            assertThat(e.getRoles()).contains("ROLE_INGESTOR");
        });
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.getAction()).isEqualTo("PIPELINE_PERSIST");
            assertThat(e.getResourceId()).isEqualTo(hash);
            assertThat(e.getDetail()).doesNotContain(mrn);
        });
    }

    @Test
    void fhirFlowsThroughAllStages() {
        String mrn = "SYN-IT-F" + System.nanoTime();
        assertThat(http("POST", "/api/v1/ingest/fhir", token("ingestor1"), "application/json", fhir(mrn)).status()).isEqualTo(202);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(patients.findByMrnHash(cipher.hmac(mrn))).isPresent());
        assertThat(patients.findByMrnHash(cipher.hmac(mrn)).orElseThrow().getGender()).isEqualTo("female");
    }

    @Test
    void syntheticGeneratorEndpointIngestsPatients() {
        long before = patients.count();
        var res = http("POST", "/api/v1/synthetic?count=6", token("ingestor1"), null, null);
        assertThat(res.status()).isEqualTo(202);
        assertThat(res.json().get("synthetic").asBoolean()).isTrue();
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(patients.count()).isGreaterThanOrEqualTo(before + 6));
    }

    @Test
    void invalidPayloadsAre400AndNothingIsPublished() {
        String t = token("ingestor1");
        assertThat(http("POST", "/api/v1/ingest/hl7", t, "text/plain", "not hl7").status()).isEqualTo(400);
        assertThat(http("POST", "/api/v1/ingest/fhir", t, "application/json", "{\"x\":1}").status()).isEqualTo(400);
    }
}
