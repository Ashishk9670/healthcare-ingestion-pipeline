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

    public enum Stage { NORMALIZE, ENRICH, EXTRACT, PERSIST_EXTRACTION, PERSIST }

    private final MeterRegistry registry;

    /** Every reason {@link #ingestRejected} is called with. */
    static final java.util.List<String> REJECT_REASONS = java.util.List.of("invalid", "publish_failed", "rate_limited", "payload_too_large", "overloaded", "validator_busy");
    static final java.util.List<String> FORMATS = java.util.List.of("hl7v2", "fhir_json");

    public PipelineMetrics(MeterRegistry registry) {
        this.registry = registry;
        preRegister();
    }

    /**
     * Registers every fixed-label counter at zero. A Prometheus counter that first appears with value 1 has no earlier
     * sample, so {@code increase()} reports 0 and the FIRST failure after a restart would never trigger an alert. With the
     * series present from startup, that first increment is visible.
     */
    private void preRegister() {
        for (String r : REJECT_REASONS) registry.counter(INGEST_REJECTED, "reason", r);
        for (String f : FORMATS) registry.counter(INGEST_COUNT, "format", f);
        for (Stage s : Stage.values()) {
            for (String outcome : java.util.List.of("success", "failure")) {
                registry.counter(STAGE_COUNT, "stage", s.name().toLowerCase(), "outcome", outcome);
            }
        }
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
