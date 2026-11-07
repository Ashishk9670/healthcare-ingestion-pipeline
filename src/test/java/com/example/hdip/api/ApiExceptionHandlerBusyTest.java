package com.example.hdip.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hdip.fhir.FhirValidatorBusyException;
import org.junit.jupiter.api.Test;

class ApiExceptionHandlerBusyTest {
    @Test
    void validatorBusyIs503WithRetryAfterAndNoPayloadContent() {
        var res = new ApiExceptionHandler().validatorBusy(new FhirValidatorBusyException());
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(res.getBody().getDetail()).isEqualTo("Validation is busy, retry shortly");
    }
}
