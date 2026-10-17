package com.example.hdip.export;

import com.example.hdip.deid.SafeHarborDeidentifier;
import com.example.hdip.store.DiagnosisEntity;
import com.example.hdip.store.ObservationEntity;
import com.example.hdip.store.PatientEntity;
import com.example.hdip.store.PatientRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FhirNdjsonExportServiceTest {
    @Mock PatientRepository repo;
    @Mock PlatformTransactionManager tm;
    final ObjectMapper mapper = new ObjectMapper();

    private PatientEntity phiLadenPatient() {
        var p = new PatientEntity();
        p.setMrnHash("h");
        p.setMrn("SYN-778899");
        p.setFamilyName("Testerson");
        p.setGivenName("Alex");
        p.setSsn("900-11-2222");
        p.setBirthDate("1975-08-09");
        p.setGender("male");
        var d = new DiagnosisEntity();
        d.setPatient(p);
        d.setCode("E11.9");
        d.setSystem("http://hl7.org/fhir/sid/icd-10-cm");
        d.setDisplay("Type 2 diabetes, call 555-867-5309");
        p.getDiagnoses().add(d);
        var o = new ObservationEntity();
        o.setPatient(p);
        o.setCode("4548-4");
        o.setSystem("http://loinc.org");
        o.setDisplay("Hemoglobin A1c");
        o.setValueNumeric(new BigDecimal("7.2"));
        o.setUnit("%");
        o.setEffectiveAt(Instant.parse("2026-03-04T10:00:00Z"));
        p.getObservations().add(o);
        var o2 = new ObservationEntity();
        o2.setPatient(p);
        o2.setCode("note");
        o2.setValueText("Contact alex@example.invalid, SSN 900-11-2222, seen on 03/04/2026");
        p.getObservations().add(o2);
        return p;
    }

    @Test
    void exportsOnlyDeidentifiedResourcesAsNdjson() throws Exception {
        PatientEntity p = phiLadenPatient();
        when(repo.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(p))).thenReturn(new PageImpl<>(List.of()));
        var svc = new FhirNdjsonExportService(repo, new SafeHarborDeidentifier(Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC)), mapper, tm);
        var out = new ByteArrayOutputStream();
        var res = svc.export(out, 100);

        String body = out.toString(StandardCharsets.UTF_8);
        assertThat(res.patients()).isEqualTo(1);
        assertThat(res.resources()).isEqualTo(4);
        assertThat(body).doesNotContain("Testerson", "Alex", "SYN-778899", "900-11-2222", "555-867-5309", "example.invalid", "1975-08-09",
                "03/04/2026", "2026-03-04");
        String[] lines = body.split("\n");
        assertThat(lines).hasSize(4);
        for (String l : lines) {
            JsonNode n = mapper.readTree(l);
            assertThat(n.at("/meta/security/0/code").asText()).isEqualTo("ANONYED");
        }
        JsonNode patient = mapper.readTree(lines[0]);
        assertThat(patient.path("resourceType").asText()).isEqualTo("Patient");
        assertThat(patient.path("id").asText()).isEqualTo(p.getId().toString());
        assertThat(patient.path("birthDate").asText()).isEqualTo("1975");
        assertThat(patient.has("name") || patient.has("identifier")).isFalse();
        JsonNode cond = mapper.readTree(lines[1]);
        assertThat(cond.at("/subject/reference").asText()).isEqualTo("Patient/" + p.getId());
        assertThat(cond.at("/code/coding/0/code").asText()).isEqualTo("E11.9");
        assertThat(cond.at("/code/coding/0/system").asText()).isEqualTo("http://hl7.org/fhir/sid/icd-10-cm");
        assertThat(mapper.readTree(lines[2]).at("/valueQuantity/value").asDouble()).isEqualTo(7.2);
    }

    @Test
    void honoursPatientLimit() throws Exception {
        when(repo.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(phiLadenPatient(), phiLadenPatient())));
        var svc = new FhirNdjsonExportService(repo, new SafeHarborDeidentifier(Clock.systemUTC()), mapper, tm);
        var res = svc.export(new ByteArrayOutputStream(), 1);
        assertThat(res.patients()).isEqualTo(1);
    }
}
