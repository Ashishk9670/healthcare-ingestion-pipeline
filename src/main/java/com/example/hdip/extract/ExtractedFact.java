package com.example.hdip.extract;

/** One structured fact extracted from a clinical note. {@code code} is an optional normalized code (e.g. ICD-10 prefix). */
public record ExtractedFact(Kind kind, String value, String code) {
    public enum Kind { DIAGNOSIS, MEDICATION, STAGE }
}
