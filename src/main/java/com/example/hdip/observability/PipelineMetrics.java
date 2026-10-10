package com.example.hdip.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;

/**
 * Pipeline metrics. Tag values are restricted to fixed, low-cardinality enums (stage, outcome, format, reason):
 * never message ids, MRNs, names, user names or any payload-derived text. SYNTHETIC DATA ONLY, but built as if real.
 */
@Component
public class PipelineMetrics {
    public static final String STAGE_COUNT = "hdip.pipeline.messages";
    public static final String STAGE_DURATION = "hdip.pipeline.stage.duration";
    public static final String INGEST_COUNT = "hdip.ingest.messages";
    public static final String INGEST_REJECTED = "hdip.ingest.rejected";

    public enum Stage { NORMALIZE, ENRICH, PERSIST }

    private final MeterRegistry registry;

    public PipelineMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Runs a stage, recording latency and a success/failure counter, and rethrows any failure unchanged. */
    public <T> T timeStage(Stage stage, Callable<T> work) throws Exception {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "success";
        try {
            return work.call();
        } catch (Exception | Error e) {
            outcome = "failure";
            throw e;
        } finally {
            sample.stop(Timer.builder(STAGE_DURATION).description("Latency of a pipeline stage")
                    .tag("stage", stage.name().toLowerCase()).tag("outcome", outcome).register(registry));
            registry.counter(STAGE_COUNT, "stage", stage.name().toLowerCase(), "outcome", outcome).increment();
        }
    }

    public void ingestAccepted(String format) {
        registry.counter(INGEST_COUNT, "format", safeFormat(format)).increment();
    }

    public void ingestRejected(String reason) {
        registry.counter(INGEST_REJECTED, "reason", reason).increment();
    }

    private static String safeFormat(String f) {
        return "HL7V2".equals(f) ? "hl7v2" : "FHIR_JSON".equals(f) ? "fhir_json" : "other";
    }
}
