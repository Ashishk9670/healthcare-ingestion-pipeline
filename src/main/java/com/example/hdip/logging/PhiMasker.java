package com.example.hdip.logging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Masks PHI-looking content (names, SSN, DOB, MRN, HL7 PID segments) in free text. Never log raw payloads anyway. */
public final class PhiMasker {
    private PhiMasker() {
    }

    public static final String MASK = "***";

    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern HL7_PHI_SEGMENT =
            Pattern.compile("(?m)\\b(PID|NK1|IN1|GT1)\\|[^\\r\\n]*");
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)(\"?\\b(?:family[_ ]?name|given[_ ]?name|first[_ ]?name|last[_ ]?name|patient[_ ]?name|name|family|given"
                    + "|ssn|social[_ ]?security[_ ]?number|dob|birth[_ ]?date|date[_ ]?of[_ ]?birth|mrn|medical[_ ]?record[_ ]?number)\\b\"?"
                    + "\\s*[:=]\\s*)(\"[^\"]*\"|\\[[^\\]]*\\]|[^,;}\\s\\]\\r\\n]+(?: [A-Za-z'-]+(?![\\w'-]*\\s*[=:]))?)");
    private static final Pattern MRN_TOKEN = Pattern.compile("\\b(?:MRN|SYN)[-_]?\\d{3,}\\b", Pattern.CASE_INSENSITIVE);

    public static String mask(String input) {
        if (input == null || input.isEmpty()) return input;
        String out = HL7_PHI_SEGMENT.matcher(input).replaceAll(m -> m.group(1) + "|" + MASK);
        out = SSN.matcher(out).replaceAll(MASK);
        Matcher m = KEY_VALUE.matcher(out);
        out = m.replaceAll(r -> {
            String value = r.group(2);
            String replacement = value.startsWith("\"") ? "\"" + MASK + "\"" : value.startsWith("[") ? "[" + MASK + "]" : MASK;
            return Matcher.quoteReplacement(r.group(1) + replacement);
        });
        return MRN_TOKEN.matcher(out).replaceAll(MASK);
    }
}
