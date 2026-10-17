package com.example.hdip.extract;

import com.example.hdip.crypto.AesGcmFieldCipher;
import com.example.hdip.kafka.Topics;
import com.example.hdip.model.CanonicalPatient;
import com.example.hdip.model.CanonicalRecord;
import com.example.hdip.observability.PipelineMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExtractionStageTest {
    @Mock KafkaTemplate<String, String> kafka;
    @Mock ExtractionStore store;
    @Mock AesGcmFieldCipher cipher;
    final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    ExtractionStage stage() {
        return new ExtractionStage(mapper, kafka, new RuleBasedExtractor(), store, cipher, new PipelineMetrics(new SimpleMeterRegistry()),
                Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC));
    }

    private String normalized(List<String> notes) throws Exception {
        var p = new CanonicalPatient("SYN-1", "Faux", "Casey", LocalDate.of(1980, 1, 1), "female", null);
        return mapper.writeValueAsString(new CanonicalRecord("m1", "HL7V2", p, List.of(), List.of(), notes));
    }

    @Test
    void publishesExtractedFactsKeyedByMrnHashNotMrn() throws Exception {
        when(cipher.hmac("SYN-1")).thenReturn("hash1");
        stage().onNormalized(normalized(List.of("Breast cancer, stage IIB on tamoxifen.")));
        ArgumentCaptor<String> v = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq(Topics.EXTRACTED), eq("m1"), v.capture());
        var ev = mapper.readValue(v.getValue(), ExtractionEvent.class);
        assertThat(ev.mrnHash()).isEqualTo("hash1");
        assertThat(ev.facts()).extracting(ExtractedFact::value).contains("breast cancer", "IIB", "tamoxifen");
        assertThat(v.getValue()).doesNotContain("SYN-1", "Faux", "Casey");
    }

    @Test
    void noNotesOrNoFindingsPublishNothing() throws Exception {
        stage().onNormalized(normalized(List.of()));
        stage().onNormalized(normalized(List.of("Routine visit, all well.")));
        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void persistsExtractionEvents() throws Exception {
        var ev = new ExtractionEvent("m1", "h", List.of(new ExtractedFact(ExtractedFact.Kind.STAGE, "IV", null)), Instant.now());
        stage().onExtracted(mapper.writeValueAsString(ev));
        verify(store).persist(any(ExtractionEvent.class));
    }
}
