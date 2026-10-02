package com.example.hdip.model;

import java.util.List;

/** Canonical patient + observations + diagnoses; published to phi.normalized. */
public record CanonicalRecord(String messageId, String source, CanonicalPatient patient,
                              List<CanonicalObservation> observations, List<CanonicalDiagnosis> diagnoses) {
}
