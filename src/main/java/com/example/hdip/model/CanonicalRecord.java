package com.example.hdip.model;

import java.util.List;

/**
 * Canonical patient + observations + diagnoses (+ free-text clinical notes); published to phi.normalized.
 * Notes are transient: only structured facts extracted from them are persisted.
 */
public record CanonicalRecord(String messageId, String source, CanonicalPatient patient,
                              List<CanonicalObservation> observations, List<CanonicalDiagnosis> diagnoses,
                              List<String> notes) {
    public CanonicalRecord {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public CanonicalRecord(String messageId, String source, CanonicalPatient patient,
                           List<CanonicalObservation> observations, List<CanonicalDiagnosis> diagnoses) {
        this(messageId, source, patient, observations, diagnoses, List.of());
    }
}
