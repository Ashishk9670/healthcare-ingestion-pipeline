package com.example.hdip.deid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Year;
import java.util.Base64;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HIPAA Safe Harbor de-identification (45 CFR 164.514(b)(2)) for FHIR JSON resources and free text.
 *
 * <p>Structural rules remove or generalise identifier-bearing FHIR elements (names, address detail, telecom,
 * identifiers, dates to year, ages over 89, photos/biometrics, device and licence data, URLs, extensions, narrative,
 * metadata, non-pseudonymous references). Every remaining string is then scrubbed with pattern rules covering all 18
 * categories in {@link IdentifierCategory}. Free-text name detection is best effort (titles, known terms); callers
 * that know the subject's names should pass them as {@code knownTerms}. Never logs content.
 */
@Component
public class SafeHarborDeidentifier {
    /** ZIP3 areas with 20,000 or fewer people (HHS guidance) must become 000. */
    static final Set<String> RESTRICTED_ZIP3 = Set.of("036", "059", "063", "102", "203", "556", "692", "790", "821", "823",
            "830", "831", "878", "879", "884", "890", "893");
    private static final Set<String> DROP_KEYS = Set.of("name", "telecom", "photo", "identifier", "contact", "text", "meta",
            "extension", "modifierExtension", "udiCarrier", "serialNumber", "lotNumber", "distinctIdentifier",
            "deviceIdentifier", "signature", "biometric", "licensePlate", "vehicleIdentifier", "url", "ssn", "mrn",
            "ipAddress", "fax", "phone", "email", "account", "accountNumber", "subscriberId", "beneficiary", "policyNumber",
            "licenseNumber", "certificateNumber", "contained", "managingOrganization", "generalPractitioner", "link");
    /** Coded/vocabulary fields: only high-confidence patterns (not URL / generic-id) so systems and codes survive. */
    private static final Set<String> VOCAB_KEYS = Set.of("resourceType", "system", "code", "status", "unit", "gender",
            "contentType", "language", "type", "version", "use", "intent", "category", "value");
    private static final Pattern UUID_RE = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern SAFE_REF = Pattern.compile(
            "^(Patient|Condition|Observation)/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern ISO_DATE = Pattern.compile("^(\\d{4})-\\d{2}(-\\d{2})?([T ].*)?$");
    private static final int MAX_AGE = 89;

    private record Rule(IdentifierCategory category, Pattern pattern, Function<Matcher, String> replacement, boolean lite) {
    }

    private static final String PHONE = "(?<![\\w])(?:\\+?1[ .-]?)?\\(?\\d{3}\\)?[ .-]?\\d{3}[ .-]?\\d{4}(?!\\d)";
    private static final String MONTHS = "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)";
    /** Identifier value following a label; must contain a digit so ordinary words after a keyword are kept. */
    private static final String VALUE = "\\s*[:#=]?\\s*(?=[A-Za-z0-9-]*\\d)[A-Za-z0-9][A-Za-z0-9-]{2,}";
    private static final String NUMLABEL = "(?:\\s*(?:number|no\\.?|num|id|#))?";

    private static final List<Rule> RULES = List.of(
            // F, N, O first: they contain characters later rules would otherwise split
            rule(IdentifierCategory.EMAIL, "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[EMAIL]", true),
            rule(IdentifierCategory.URL, "(?i)\\b(?:https?://|ftp://|www\\.)[^\\s\"'<>]+", "[URL]", false),
            rule(IdentifierCategory.IP, "(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\d.])", "[IP]", true),
            rule(IdentifierCategory.IP, "(?i)(?<![\\w:])(?:[0-9a-f]{1,4}:){2,7}[0-9a-f]{0,4}(?![\\w:])", "[IP]", true),
            rule(IdentifierCategory.SSN, "(?<!\\d)\\d{3}[- ]\\d{2}[- ]\\d{4}(?!\\d)", "[SSN]", true),
            rule(IdentifierCategory.SSN, "(?i)\\b(?:ssn|social security(?: number)?)\\s*[:#=]?\\s*\\d{9}\\b", "[SSN]", true),
            // labelled identifiers, before the generic phone/id rules
            rule(IdentifierCategory.FAX, "(?i)\\bfax" + "(?:\\s*(?:number|no\\.?|#))?\\s*[:#=]?\\s*" + PHONE, "[FAX]", true),
            rule(IdentifierCategory.MRN, "(?i)\\b(?:mrn|medical record|med rec|chart|patient id)" + NUMLABEL + VALUE, "[MRN]", false),
            rule(IdentifierCategory.MRN, "(?i)\\bSYN-[A-Z0-9-]+", "[MRN]", false),
            rule(IdentifierCategory.HEALTH_PLAN, "(?i)\\b(?:member|policy|group|beneficiary|subscriber|insurance|health plan|medicare|medicaid)\\s*(?:id|number|no\\.?|num|#)" + VALUE, "[HEALTH-PLAN-ID]", false),
            rule(IdentifierCategory.ACCOUNT, "(?i)\\b(?:account|acct)" + NUMLABEL + VALUE, "[ACCOUNT]", false),
            rule(IdentifierCategory.LICENSE, "(?i)\\b(?:driver'?s? license|license|licence|certificate|cert|dea|npi)" + NUMLABEL + VALUE, "[LICENSE]", false),
            rule(IdentifierCategory.VEHICLE, "(?i)\\b(?:license plate|licence plate|plate)" + NUMLABEL + "\\s*[:#=]?\\s*(?=[A-Za-z0-9 -]{0,8}\\d)[A-Za-z0-9]{2,4}[ -]?[A-Za-z0-9]{2,5}\\b", "[VEHICLE-ID]", false),
            rule(IdentifierCategory.VEHICLE, "(?i)\\bvehicle id(?:entification)?(?: number)?" + VALUE, "[VEHICLE-ID]", false),
            rule(IdentifierCategory.VEHICLE, "\\b[A-HJ-NPR-Z0-9]{17}\\b", "[VEHICLE-ID]", false),
            rule(IdentifierCategory.DEVICE, "(?i)\\b(?:serial|device|implant|pacemaker|pump|udi|lot)(?: serial)?" + NUMLABEL + VALUE, "[DEVICE-ID]", false),
            rule(IdentifierCategory.DEVICE, "\\(01\\)\\d{14}(?:\\(\\d{2}\\)[A-Za-z0-9]+)*", "[DEVICE-ID]", false),
            rule(IdentifierCategory.BIOMETRIC, "(?i)\\b(?:fingerprint|finger print|voiceprint|voice print|retina(?:l)? scan|iris scan|face(?:print| template)|dna profile|biometric)(?:\\s*(?:id|template|hash|data))?" + VALUE, "[BIOMETRIC]", false),
            rule(IdentifierCategory.PHOTO, "(?i)\\b[\\w.-]+\\.(?:jpe?g|png|gif|bmp|tiff?|heic|dcm|webp)\\b", "[IMAGE]", false),
            rule(IdentifierCategory.PHOTO, "(?i)\\b(?:photo|photograph|selfie|headshot|face image)\\s*(?:id|file)?\\s*[:#=]\\s*\\S+", "[IMAGE]", false),
            rule(IdentifierCategory.PHONE, PHONE, "[PHONE]", true),
            // dates: keep year only
            new Rule(IdentifierCategory.DATE, Pattern.compile("(?<!\\d)(\\d{4})[-/.](?:0?[1-9]|1[0-2])[-/.](?:0?[1-9]|[12]\\d|3[01])(?:[T ]\\d{2}:\\d{2}(?::\\d{2})?(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?)?(?!\\d)"), m -> m.group(1), true),
            new Rule(IdentifierCategory.DATE, Pattern.compile("(?<!\\d)(?:0?[1-9]|1[0-2])[/.-](?:0?[1-9]|[12]\\d|3[01])[/.-](\\d{4}|\\d{2})(?!\\d)"), m -> m.group(1).length() == 4 ? m.group(1) : "[DATE]", true),
            new Rule(IdentifierCategory.DATE, Pattern.compile("(?i)\\b" + MONTHS + "\\.?\\s+\\d{1,2}(?:st|nd|rd|th)?,?\\s+(\\d{4})\\b"), m -> m.group(1), true),
            new Rule(IdentifierCategory.DATE, Pattern.compile("(?i)\\b\\d{1,2}(?:st|nd|rd|th)?\\s+" + MONTHS + "\\.?,?\\s+(\\d{4})\\b"), m -> m.group(1), true),
            rule(IdentifierCategory.DATE, "(?i)\\b" + MONTHS + "\\.?\\s+\\d{1,2}(?:st|nd|rd|th)?\\b", "[DATE]", true),
            rule(IdentifierCategory.DATE, "(?i)\\b\\d{1,2}(?:st|nd|rd|th)?\\s+" + MONTHS + "\\b", "[DATE]", true),
            rule(IdentifierCategory.DATE, "(?<!\\d)(?:0?[1-9]|1[0-2])/(?:0?[1-9]|[12]\\d|3[01])(?!\\d)", "[DATE]", true),
            rule(IdentifierCategory.DATE, "(?i)\\b(?:9\\d|1[0-4]\\d)[- ]?(?:years?[- ]old|y/?o|yrs?)\\b", "90+ years old", true),
            // geography
            rule(IdentifierCategory.GEOGRAPHIC, "(?i)\\b\\d{1,6}\\s+(?:[A-Za-z0-9.]+\\s+){1,4}(?:street|st|avenue|ave|road|rd|boulevard|blvd|lane|ln|drive|dr|court|ct|way|place|pl|terrace|circle|highway|hwy)\\b\\.?", "[ADDRESS]", false),
            rule(IdentifierCategory.GEOGRAPHIC, "\\b[A-Z][a-z]+(?:\\s[A-Z][a-z]+)?\\s+(?:County|Parish|Township|Precinct)\\b", "[GEO]", false),
            rule(IdentifierCategory.GEOGRAPHIC, "\\b(?:City|Town|Village) of\\s+[A-Z][a-z]+(?:\\s[A-Z][a-z]+)?", "[GEO]", false),
            rule(IdentifierCategory.GEOGRAPHIC, "(?<!\\d)\\d{5}(?:-\\d{4})?(?!\\d)", "[ZIP]", false),
            // names (best effort: titles)
            rule(IdentifierCategory.NAME, "\\b(?:Mr|Mrs|Ms|Miss|Dr|Prof|Patient|Pt|Name)\\.?:?\\s+[A-Z][a-z'-]+(?:\\s[A-Z][a-z'-]+){0,2}", "[NAME]", false),
            // anything else that looks like a unique number/code: >=10 chars containing >=4 digits
            rule(IdentifierCategory.OTHER_UNIQUE_ID, "\\b(?=(?:[A-Za-z0-9-]*\\d){4})[A-Za-z0-9][A-Za-z0-9-]{9,}\\b", "[ID]", false));

    private final Clock clock;

    public SafeHarborDeidentifier(Clock clock) {
        this.clock = clock;
    }

    private static Rule rule(IdentifierCategory c, String regex, String replacement, boolean lite) {
        return new Rule(c, Pattern.compile(regex), m -> replacement, lite);
    }

    // ---------------------------------------------------------------- text

    public String scrubText(String text) {
        return scrubText(text, List.of());
    }

    /** Scrubs free text. {@code knownTerms} (e.g. the subject's names) are removed case-insensitively. */
    public String scrubText(String text, Collection<String> knownTerms) {
        return scrub(text, knownTerms, false);
    }

    private String scrub(String text, Collection<String> knownTerms, boolean lite) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        if (knownTerms != null) {
            for (String t : knownTerms) {
                if (t != null && t.strip().length() >= 2) {
                    out = Pattern.compile("(?i)(?<![A-Za-z0-9])" + Pattern.quote(t.strip()) + "(?![A-Za-z0-9])").matcher(out).replaceAll("[REDACTED]");
                }
            }
        }
        for (Rule r : RULES) {
            if (lite && !r.lite()) continue;
            Matcher m = r.pattern().matcher(out);
            StringBuilder sb = new StringBuilder();
            while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(r.replacement().apply(m)));
            m.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }

    // ---------------------------------------------------------------- FHIR JSON

    /** Returns a de-identified deep copy of a FHIR resource (or Bundle). The input is not modified. */
    public JsonNode deidentify(JsonNode resource) {
        return walk(resource, null, resource != null && "Patient".equals(resource.path("resourceType").asText()));
    }

    private JsonNode walk(JsonNode node, String key, boolean patient) {
        if (node == null || node.isNull()) return node;
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            boolean isPatient = patient || "Patient".equals(node.path("resourceType").asText());
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                var e = it.next();
                String k = e.getKey();
                JsonNode v = e.getValue();
                if (DROP_KEYS.contains(k)) continue;
                switch (k) {
                    case "address" -> {
                        JsonNode a = address(v);
                        if (a != null) out.set(k, a);
                    }
                    case "birthDate" -> {
                        String y = isPatient ? birthYear(v.asText("")) : null;
                        if (y != null) out.put(k, y);
                    }
                    case "attachment" -> {
                        JsonNode a = attachment(v);
                        if (a != null) out.set(k, a);
                    }
                    case "id" -> {
                        if (v.isTextual() && UUID_RE.matcher(v.asText()).matches()) out.set(k, v); // random surrogate ids only
                    }
                    case "reference", "fullUrl" -> {
                        if (v.isTextual() && (SAFE_REF.matcher(v.asText()).matches() || v.asText().startsWith("urn:uuid:"))) out.set(k, v);
                    }
                    default -> out.set(k, walk(v, k, isPatient));
                }
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (JsonNode n : node) {
                JsonNode w = walk(n, key, patient);
                if (w != null && !(w.isObject() && w.isEmpty())) out.add(w);
            }
            return out;
        }
        if (node.isTextual()) {
            String s = node.asText();
            Matcher d = ISO_DATE.matcher(s);
            if (d.matches()) return JsonNodeFactory.instance.textNode(d.group(1)); // FHIR date: year only is valid
            return JsonNodeFactory.instance.textNode(scrub(s, List.of(), key != null && VOCAB_KEYS.contains(key)));
        }
        return node;
    }

    /** Keep state and (if safe) ZIP3 only. */
    private JsonNode address(JsonNode a) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        for (JsonNode n : a.isArray() ? a : List.of(a)) {
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            String state = n.path("state").asText(null);
            if (state != null && state.length() <= 2 + 30) o.put("state", scrub(state, List.of(), false));
            String zip = n.path("postalCode").asText("");
            Matcher m = Pattern.compile("^(\\d{3})\\d{2}(?:-\\d{4})?$").matcher(zip.strip());
            if (m.matches()) o.put("postalCode", RESTRICTED_ZIP3.contains(m.group(1)) ? "000" : m.group(1));
            String country = n.path("country").asText(null);
            if (country != null) o.put("country", scrub(country, List.of(), false));
            if (!o.isEmpty()) out.add(o);
        }
        return out.isEmpty() ? null : out;
    }

    /** Year only; dropped entirely when the subject would be over 89. */
    private String birthYear(String birthDate) {
        Matcher m = ISO_DATE.matcher(birthDate);
        if (!m.matches()) return null;
        int year = Integer.parseInt(m.group(1));
        LocalDate today = LocalDate.now(clock);
        int ageUpperBound = Year.from(today).getValue() - year;
        // conservative: use calendar-year difference, so anyone possibly >89 is dropped
        return ageUpperBound > MAX_AGE ? null : m.group(1);
    }

    /** Non-text attachments (images, audio, video, biometrics) are dropped; text is scrubbed. */
    private JsonNode attachment(JsonNode a) {
        if (!a.path("contentType").asText("").toLowerCase().startsWith("text/") || !a.hasNonNull("data")) return null;
        try {
            String text = new String(Base64.getDecoder().decode(a.get("data").asText()), StandardCharsets.UTF_8);
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            o.put("contentType", a.get("contentType").asText());
            o.put("data", Base64.getEncoder().encodeToString(scrubText(text).getBytes(StandardCharsets.UTF_8)));
            return o;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** For documentation/tests: the structural + pattern rules registered per category. */
    static Map<IdentifierCategory, Integer> patternRulesPerCategory() {
        Map<IdentifierCategory, Integer> m = new LinkedHashMap<>();
        for (Rule r : RULES) m.merge(r.category(), 1, Integer::sum);
        return m;
    }
}
