package com.example.hdip.fhir;

/**
 * The FHIR validator could not reach a verdict because its internal regex time limit fired (HAPI hard-codes 500 ms and
 * does not let us change it). That happens when the JVM is starved of CPU, so it says nothing about the payload: it is
 * reported as a retryable server condition (503), never as a client validation error (422).
 */
public class FhirValidatorBusyException extends RuntimeException {
    public FhirValidatorBusyException() {
        super("FHIR validator is busy, retry shortly", null, false, false);
    }
}
