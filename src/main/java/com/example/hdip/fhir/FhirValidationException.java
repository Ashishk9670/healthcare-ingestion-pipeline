package com.example.hdip.fhir;

import com.example.hdip.parse.MessageParseException;

/** Thrown for FHIR payloads failing R4 validation; carries the sanitized OperationOutcome JSON. */
public class FhirValidationException extends MessageParseException {
    private final String operationOutcomeJson;

    public FhirValidationException(String operationOutcomeJson) {
        super("FHIR validation failed");
        this.operationOutcomeJson = operationOutcomeJson;
    }

    public String operationOutcomeJson() {
        return operationOutcomeJson;
    }
}
