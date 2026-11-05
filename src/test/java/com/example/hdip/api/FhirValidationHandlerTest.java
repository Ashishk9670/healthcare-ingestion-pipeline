package com.example.hdip.api;

import com.example.hdip.fhir.FhirValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FhirValidationHandlerTest {
    @Test
    void returns422WithFhirJsonOperationOutcome() {
        var res = new ApiExceptionHandler().fhirInvalid(new FhirValidationException("{\"resourceType\":\"OperationOutcome\"}"));
        assertThat(res.getStatusCode().value()).isEqualTo(422);
        assertThat(res.getHeaders().getContentType().toString()).isEqualTo("application/fhir+json");
        assertThat(res.getBody()).contains("OperationOutcome");
    }

    @Test
    void invalidBodyIs400WithFixedText() {
        var pd = new ApiExceptionHandler().invalidBody(new IllegalStateException("secret payload"));
        assertThat(pd.getStatus()).isEqualTo(400);
        assertThat(pd.getDetail()).isEqualTo("Invalid request body");
    }
}
