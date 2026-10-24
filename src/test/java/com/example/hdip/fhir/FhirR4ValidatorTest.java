package com.example.hdip.fhir;

import com.example.hdip.synthetic.SyntheticDataGenerator;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.hl7.fhir.r4.model.OperationOutcome;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class FhirR4ValidatorTest {
    static FhirR4Validator validator;
    static SyntheticDataGenerator gen;

    @BeforeAll
    static void init() {
        validator = new FhirR4Validator();
        gen = new SyntheticDataGenerator(JsonMapper.builder().findAndAddModules().build(), new Random(42));
    }

    @Test
    void syntheticBundlesAreValid() {
        for (int i = 0; i < 60; i++) {
            OperationOutcome oo = validator.validate(gen.fhirBundle());
            assertThat(oo).as("synthetic bundle %d", i).isNull();
        }
    }

    @Test
    void missingRequiredElementsYieldOperationOutcome() {
        String bad = "{\"resourceType\":\"Observation\",\"id\":\"o1\"}";
        OperationOutcome oo = validator.validate(bad);
        assertThat(oo).isNotNull();
        assertThat(oo.getIssue()).isNotEmpty();
        assertThat(oo.getIssueFirstRep().getSeverity()).isIn(OperationOutcome.IssueSeverity.ERROR, OperationOutcome.IssueSeverity.FATAL);
        assertThat(validator.toJson(oo)).contains("\"resourceType\":\"OperationOutcome\"").contains("Observation");
    }

    @Test
    void invalidCodeValueIsReportedWithoutEchoingPayloadValues() {
        String bad = "{\"resourceType\":\"Patient\",\"gender\":\"SECRET-VALUE-XYZ\",\"birthDate\":\"not-a-date\"}";
        OperationOutcome oo = validator.validate(bad);
        assertThat(oo).isNotNull();
        String json = validator.toJson(oo);
        assertThat(json).doesNotContain("SECRET-VALUE-XYZ").doesNotContain("not-a-date");
    }

    @Test
    void unknownResourceTypeIsRejected() {
        assertThat(validator.validate("{\"resourceType\":\"Nonsense\"}")).isNotNull();
    }

    @Test
    void redactReplacesQuotedValues() {
        assertThat(FhirR4Validator.redact("The value 'abc' and \"def\" bad")).isEqualTo("The value '***' and '***' bad");
    }
}
