package com.example.hdip.model;

import java.math.BigDecimal;
import java.time.Instant;

public record CanonicalObservation(String code, String system, String display, String valueText,
                                   BigDecimal valueNumeric, String unit, Instant effectiveAt) {
}
