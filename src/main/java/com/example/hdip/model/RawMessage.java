package com.example.hdip.model;

import java.time.Instant;

/** Envelope published to phi.raw. */
public record RawMessage(String messageId, String format, String payload, Instant receivedAt, String submittedBy) {
    public static final String HL7V2 = "HL7V2";
    public static final String FHIR_JSON = "FHIR_JSON";
}
