package com.example.hdip.enrich;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/** Tiny in-memory code -> display lookup (ICD-10-CM / LOINC subset). A real system would call a terminology service. */
@Component
public class CodingLookup {
    private static final Map<String, String> DISPLAY = Map.ofEntries(
            Map.entry("E11.9", "Type 2 diabetes mellitus without complications"),
            Map.entry("I10", "Essential (primary) hypertension"),
            Map.entry("J45.909", "Unspecified asthma, uncomplicated"),
            Map.entry("C50.911", "Malignant neoplasm of unspecified site of right female breast"),
            Map.entry("C34.90", "Malignant neoplasm of unspecified part of unspecified bronchus or lung"),
            Map.entry("N18.3", "Chronic kidney disease, stage 3"),
            Map.entry("4548-4", "Hemoglobin A1c/Hemoglobin.total in Blood"),
            Map.entry("8480-6", "Systolic blood pressure"),
            Map.entry("8462-4", "Diastolic blood pressure"),
            Map.entry("2339-0", "Glucose [Mass/volume] in Blood"),
            Map.entry("718-7", "Hemoglobin [Mass/volume] in Blood"));

    public Optional<String> display(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(DISPLAY.get(code));
    }
}
