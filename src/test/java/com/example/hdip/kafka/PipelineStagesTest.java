package com.example.hdip.kafka;

import com.example.hdip.enrich.CodingLookup;
import com.example.hdip.enrich.EnrichmentService;
import com.example.hdip.model.CanonicalRecord;
import com.example.hdip.model.EnrichedRecord;
import com.example.hdip.model.RawMessage;
import com.example.hdip.parse.FhirJsonNormalizer;
import com.example.hdip.parse.Hl7V2Parser;
import com.example.hdip.parse.NormalizationService;
import com.example.hdip.store.PatientStoreService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PipelineStagesTest {
    @Mock KafkaTemplate<String, String> kafka;
    @Mock PatientStoreService store;
    final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    PipelineStages stages() {
        var norm = new NormalizationService(new Hl7V2Parser(), new FhirJsonNormalizer(mapper));
        var enrich = new EnrichmentService(new CodingLookup(), Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), java.time.ZoneOffset.UTC));
        return new PipelineStages(mapper, kafka, norm, enrich, store);
    }

    @Test
    void rawToNormalizedToEnrichedToStore() throws Exception {
        String hl7 = "MSH|^~\\&|A|B|C|D|2026||ORU^R01|1|P|2.5\rPID|1||SYN-9^^^S^MR||Faux^Casey||19800101|F\rDG1|1||E11.9";
        String rawJson = mapper.writeValueAsString(new RawMessage("m1", RawMessage.HL7V2, hl7, Instant.now(), "t"));
        var stages = stages();

        stages.onRaw(rawJson);
        ArgumentCaptor<String> norm = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq(Topics.NORMALIZED), eq("m1"), norm.capture());
        CanonicalRecord canonical = mapper.readValue(norm.getValue(), CanonicalRecord.class);
        assertThat(canonical.patient().mrn()).isEqualTo("SYN-9");

        stages.onNormalized(norm.getValue());
        ArgumentCaptor<String> enr = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq(Topics.ENRICHED), eq("m1"), enr.capture());
        EnrichedRecord enriched = mapper.readValue(enr.getValue(), EnrichedRecord.class);
        assertThat(enriched.ageBand()).isEqualTo("35-49");
        assertThat(enriched.record().diagnoses().get(0).display()).contains("diabetes");

        stages.onEnriched(enr.getValue());
        verify(store).persist(org.mockito.ArgumentMatchers.any(EnrichedRecord.class));
    }
}
