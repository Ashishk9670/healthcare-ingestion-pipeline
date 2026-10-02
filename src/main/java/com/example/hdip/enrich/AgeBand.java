package com.example.hdip.enrich;

import java.time.LocalDate;
import java.time.Period;

public final class AgeBand {
    private AgeBand() {
    }

    public static final String UNKNOWN = "unknown";

    public static String of(LocalDate birthDate, LocalDate today) {
        if (birthDate == null || birthDate.isAfter(today)) return UNKNOWN;
        int age = Period.between(birthDate, today).getYears();
        if (age < 18) return "0-17";
        if (age < 35) return "18-34";
        if (age < 50) return "35-49";
        if (age < 65) return "50-64";
        if (age < 80) return "65-79";
        return "80+";
    }
}
