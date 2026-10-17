package com.example.hdip.api;

import com.example.hdip.audit.AuditService;
import com.example.hdip.kafka.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {
    @Mock KafkaTemplate<String, String> kafka;
    @Mock AuditService audit;
    final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void publishesToRawTopicAndAudits() {
        var svc = new IngestionService(kafka, mapper, audit, new com.example.hdip.observability.PipelineMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), new com.example.hdip.fhir.FhirR4Validator());
        String id = svc.submit("HL7V2", "MSH|^~\\&|x");
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq(Topics.RAW), eq(id), value.capture());
        assertThat(value.getValue()).contains("\"format\":\"HL7V2\"").contains(id);
        verify(audit).record(eq("INGEST"), eq("Message"), eq(id), argThat(d -> d.contains("format=HL7V2") && !d.contains("MSH")));
    }

    @Test
    void rejectsInvalidPayloadsWithoutPublishing() {
        var svc = new IngestionService(kafka, mapper, audit, new com.example.hdip.observability.PipelineMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), new com.example.hdip.fhir.FhirR4Validator());
        assertThatThrownBy(() -> svc.submit("HL7V2", "PID|1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.submit("FHIR_JSON", "{\"a\":1}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.submit("FHIR_JSON", "nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.submit("CSV", "a,b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.submit("HL7V2", " ")).isInstanceOf(IllegalArgumentException.class);
        verify(kafka, never()).send(anyString(), anyString(), anyString());
        verifyNoInteractions(audit);
    }

    @Test
    void rejectsInvalidFhirWithOperationOutcome() {
        var svc = new IngestionService(kafka, mapper, audit, new com.example.hdip.observability.PipelineMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), new com.example.hdip.fhir.FhirR4Validator());
        assertThatThrownBy(() -> svc.submit("FHIR_JSON", "{\"resourceType\":\"Observation\"}"))
                .isInstanceOf(com.example.hdip.fhir.FhirValidationException.class)
                .satisfies(e -> assertThat(((com.example.hdip.fhir.FhirValidationException) e).operationOutcomeJson()).contains("OperationOutcome"));
        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }
}
