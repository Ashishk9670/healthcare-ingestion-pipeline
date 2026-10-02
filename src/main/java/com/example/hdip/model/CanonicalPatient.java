package com.example.hdip.model;

import java.time.LocalDate;

public record CanonicalPatient(String mrn, String familyName, String givenName, LocalDate birthDate,
                               String gender, String ssn) {
}
