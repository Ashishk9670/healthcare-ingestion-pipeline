package com.example.hdip.fhir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.junit.jupiter.api.Test;

/**
 * HAPI's regex time limit is hard-coded to 500 ms. Under CPU starvation it fires on valid payloads; that must never
 * surface as a 422 "your data is invalid". Found by running the stack under load (about 10% of valid requests failed).
 */
class FhirValidatorTimeLimitTest {
    private static final FhirContext CTX = FhirContext.forR4Cached();

    private static SingleValidationMessage message(String text, ResultSeverityEnum severity) {
        SingleValidationMessage m = new SingleValidationMessage();
        m.setMessage(text);
        m.setSeverity(severity);
        m.setLocationString("Bundle.entry[1].fullUrl");
        return m;
    }

    private static ValidationResult timeout() {
        return new ValidationResult(CTX, List.of(message("Exception evaluating regex '^urn:uuid:.*' on type uri: Regex evaluation timed out after 500ms", ResultSeverityEnum.ERROR)));
    }

    private static ValidationResult clean() {
        return new ValidationResult(CTX, List.of());
    }

    private static FhirR4Validator scripted(AtomicInteger calls, IntFunction<ValidationResult> script) {
        return new FhirR4Validator() {
            @Override
            ValidationResult runOnce(String json) {
                return script.apply(calls.incrementAndGet());
            }
        };
    }

    @Test
    void aTransientTimeLimitIsRetriedAndTheValidPayloadAccepted() {
        var calls = new AtomicInteger();
        var v = scripted(calls, n -> n == 1 ? timeout() : clean());
        assertThat(v.validate("{}")).isNull();
        assertThat(calls).hasValue(2);
    }

    @Test
    void aPersistentTimeLimitIsReportedAsBusyNotAsInvalid() {
        var calls = new AtomicInteger();
        var v = scripted(calls, n -> timeout());
        assertThatThrownBy(() -> v.validate("{}")).isInstanceOf(FhirValidatorBusyException.class);
        assertThat(calls).hasValue(FhirR4Validator.MAX_ATTEMPTS);
    }

    @Test
    void realValidationErrorsAreReturnedImmediatelyWithoutRetry() {
        var calls = new AtomicInteger();
        var v = scripted(calls, n -> new ValidationResult(CTX, List.of(message("Observation.status: minimum required = 1, but only found 0", ResultSeverityEnum.ERROR))));
        OperationOutcome oo = v.validate("{}");
        assertThat(oo).isNotNull();
        assertThat(oo.getIssueFirstRep().getDiagnostics()).contains("minimum required");
        assertThat(calls).hasValue(1);
    }

    @Test
    void timeoutMessagesAreOnlyRecognisedByTheirText() {
        assertThat(FhirR4Validator.hitInternalTimeLimit(timeout())).isTrue();
        assertThat(FhirR4Validator.hitInternalTimeLimit(clean())).isFalse();
    }
}
