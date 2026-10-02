package com.example.hdip.parse;

import com.example.hdip.model.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Minimal HL7 v2.x parser (ORU/ADT style) handling MSH, PID, OBX and DG1 segments.
 * Field numbering follows the HL7 spec (1-based; MSH-1 is the field separator).
 */
@Component
public class Hl7V2Parser {

    public CanonicalRecord parse(String messageId, String message) {
        if (message == null || message.isBlank()) {
            throw new MessageParseException("Empty HL7 message");
        }
        String[] segments = message.strip().split("\\r\\n|\\r|\\n");
        if (!segments[0].startsWith("MSH") || segments[0].length() < 4) {
            throw new MessageParseException("HL7 message must start with an MSH segment");
        }
        char fieldSep = segments[0].charAt(3);
        String[] msh = split(segments[0], fieldSep);
        char compSep = msh.length > 1 && !msh[1].isEmpty() ? msh[1].charAt(0) : '^';
        char repSep = msh.length > 1 && msh[1].length() > 1 ? msh[1].charAt(1) : '~';

        String id = messageId;
        if (id == null || id.isBlank()) {
            id = msh.length > 9 ? msh[9] : null; // MSH-10
        }

        CanonicalPatient patient = null;
        List<CanonicalObservation> observations = new ArrayList<>();
        List<CanonicalDiagnosis> diagnoses = new ArrayList<>();

        for (int i = 1; i < segments.length; i++) {
            String seg = segments[i];
            if (seg.length() < 3) continue;
            String[] f = split(seg, fieldSep);
            switch (f[0]) {
                case "PID" -> patient = parsePid(f, compSep, repSep);
                case "OBX" -> observations.add(parseObx(f, compSep));
                case "DG1" -> diagnoses.add(parseDg1(f, compSep));
                default -> { /* ignored */ }
            }
        }
        if (patient == null) throw new MessageParseException("HL7 message has no PID segment");
        if (patient.mrn() == null || patient.mrn().isBlank()) {
            throw new MessageParseException("PID-3 (patient identifier) is required");
        }
        return new CanonicalRecord(id, RawMessage.HL7V2, patient, observations, diagnoses);
    }

    private CanonicalPatient parsePid(String[] f, char compSep, char repSep) {
        String ident = get(f, 3);
        if (ident != null) ident = first(ident, repSep);
        String mrn = comp(ident, 0, compSep);
        String name = get(f, 5);
        if (name != null) name = first(name, repSep);
        String family = comp(name, 0, compSep);
        String given = comp(name, 1, compSep);
        LocalDate dob = parseDate(get(f, 7));
        String gender = mapGender(get(f, 8));
        String ssn = get(f, 19);
        return new CanonicalPatient(mrn, family, given, dob, gender, blankToNull(ssn));
    }

    private CanonicalObservation parseObx(String[] f, char compSep) {
        String code = get(f, 3);
        String type = get(f, 2);
        String value = get(f, 5);
        BigDecimal numeric = null;
        String text = value;
        if (value != null && ("NM".equals(type) || "SN".equals(type) || isNumber(value))) {
            try {
                numeric = new BigDecimal(value.trim());
                text = null;
            } catch (NumberFormatException ignored) {
                // keep as text
            }
        }
        return new CanonicalObservation(comp(code, 0, compSep), comp(code, 2, compSep), comp(code, 1, compSep),
                text, numeric, comp(get(f, 6), 0, compSep), parseTimestamp(get(f, 14)));
    }

    private CanonicalDiagnosis parseDg1(String[] f, char compSep) {
        String code = get(f, 3);
        return new CanonicalDiagnosis(comp(code, 0, compSep), comp(code, 2, compSep), comp(code, 1, compSep));
    }

    // ---- helpers ----

    private static String[] split(String seg, char sep) {
        return seg.split(Pattern.quote(String.valueOf(sep)), -1);
    }

    private static String get(String[] f, int idx) {
        return idx < f.length ? blankToNull(f[idx]) : null;
    }

    private static String first(String s, char repSep) {
        int i = s.indexOf(repSep);
        return i < 0 ? s : s.substring(0, i);
    }

    private static String comp(String field, int idx, char compSep) {
        if (field == null) return null;
        String[] parts = split(field, compSep);
        return idx < parts.length ? blankToNull(parts[idx]) : null;
    }

    private static boolean isNumber(String s) {
        return s.trim().matches("-?\\d+(\\.\\d+)?");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static String mapGender(String hl7) {
        if (hl7 == null) return null;
        return switch (hl7.toUpperCase()) {
            case "M" -> "male";
            case "F" -> "female";
            case "O" -> "other";
            default -> "unknown";
        };
    }

    static LocalDate parseDate(String s) {
        if (s == null || s.length() < 8) return null;
        try {
            return LocalDate.parse(s.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
        } catch (Exception e) {
            throw new MessageParseException("Invalid HL7 date: " + s.length() + " chars", e);
        }
    }

    static Instant parseTimestamp(String s) {
        if (s == null || s.length() < 8) return null;
        try {
            LocalDate d = LocalDate.parse(s.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
            int h = s.length() >= 10 ? Integer.parseInt(s.substring(8, 10)) : 0;
            int m = s.length() >= 12 ? Integer.parseInt(s.substring(10, 12)) : 0;
            int sec = s.length() >= 14 ? Integer.parseInt(s.substring(12, 14)) : 0;
            return d.atTime(h, m, sec).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            throw new MessageParseException("Invalid HL7 timestamp", e);
        }
    }
}
