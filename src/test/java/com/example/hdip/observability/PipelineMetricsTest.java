package com.example.hdip.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PipelineMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PipelineMetrics metrics = new PipelineMetrics(registry);

    @Test
    void recordsSuccessAndFailureOutcomes() throws Exception {
        assertEquals("ok", metrics.timeStage(PipelineMetrics.Stage.NORMALIZE, () -> "ok"));
        assertThrows(IllegalStateException.class, () -> metrics.timeStage(PipelineMetrics.Stage.NORMALIZE, () -> {
            throw new IllegalStateException("MRN-12345 John Doe");
        }));
        assertEquals(1.0, registry.counter(PipelineMetrics.STAGE_COUNT, "stage", "normalize", "outcome", "success").count());
        assertEquals(1.0, registry.counter(PipelineMetrics.STAGE_COUNT, "stage", "normalize", "outcome", "failure").count());
    }

    @Test
    void tagValuesAreFixedEnumsNeverPayloadText() throws Exception {
        metrics.ingestAccepted("weird MRN-999 format");
        metrics.ingestAccepted("HL7V2");
        try {
            metrics.timeStage(PipelineMetrics.Stage.PERSIST, () -> {
                throw new IllegalStateException("SSN 123-45-6789");
            });
        } catch (IllegalStateException ignored) {
        }
        for (Meter m : registry.getMeters()) {
            m.getId().getTags().forEach(t -> {
                assertFalse(t.getValue().contains("MRN"), t.toString());
                assertFalse(t.getValue().contains("123-45"), t.toString());
            });
        }
        assertEquals(1.0, registry.counter(PipelineMetrics.INGEST_COUNT, "format", "other").count());
        assertEquals(1.0, registry.counter(PipelineMetrics.INGEST_COUNT, "format", "hl7v2").count());
    }
}
