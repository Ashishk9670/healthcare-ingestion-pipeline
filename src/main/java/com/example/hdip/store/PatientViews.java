package com.example.hdip.store;

import java.util.List;

/** Read models returned by the GraphQL API. */
public final class PatientViews {
    private PatientViews() {
    }

    public record DiagnosisView(String code, String system, String display) {
    }

    public record ObservationView(String code, String display, String valueText, Double valueNumeric, String unit,
                                  String effectiveAt) {
    }

    public record PatientView(String mrn, String familyName, String givenName, String birthDate, String gender,
                              String ageBand, List<DiagnosisView> diagnoses, List<ObservationView> observations) {
    }

    /**
     * {@code extractedDiagnosis} (ICD-10 prefix), {@code medication} and {@code stage} match facts extracted from
     * clinical notes by the enrichment stage (phi.extracted).
     */
    public record CohortFilter(String diagnosisCode, String ageBand, String gender, String extractedDiagnosis,
                               String medication, String stage) {
    }

    public record Bucket(String key, int count) {
    }

    /** De-identified cohort member (no names, MRN, DOB). */
    public record CohortMember(String patientRef, String ageBand, String gender, List<String> diagnosisCodes) {
    }

    public record CohortSummary(int totalPatients, List<Bucket> byAgeBand, List<Bucket> byGender,
                                List<Bucket> byDiagnosis, List<CohortMember> members) {
    }
}
