package com.example.hdip.deid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts every one of the 18 Safe Harbor identifier categories is removed, both from free text and from a FHIR
 * Observation carrying the identifier in a string field. All values are SYNTHETIC.
 */
class SafeHarborDeidentifierTest {
    static final ObjectMapper M = new ObjectMapper();
    final SafeHarborDeidentifier d = new SafeHarborDeidentifier(Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC));

    /** category -> (text containing the identifier, the sensitive fragment that must not survive). */
    static final Map<IdentifierCategory, String[]> SAMPLES = new EnumMap<>(IdentifierCategory.class);

    static {
        SAMPLES.put(IdentifierCategory.NAME, new String[]{"Seen today with Mr. Alexander Testerson for follow-up", "Testerson"});
        SAMPLES.put(IdentifierCategory.GEOGRAPHIC, new String[]{"Lives at 4242 Elm Street near the clinic", "Elm Street"});
        SAMPLES.put(IdentifierCategory.DATE, new String[]{"Admitted on 03/14/2026 and discharged March 18, 2026", "03/14"});
        SAMPLES.put(IdentifierCategory.PHONE, new String[]{"Call (555) 867-5309 to reschedule", "867-5309"});
        SAMPLES.put(IdentifierCategory.FAX, new String[]{"Send records, fax: 555-222-9876", "222-9876"});
        SAMPLES.put(IdentifierCategory.EMAIL, new String[]{"Contact casey.faux@example.invalid for results", "casey.faux@example.invalid"});
        SAMPLES.put(IdentifierCategory.SSN, new String[]{"SSN on file 900-12-3456", "900-12-3456"});
        SAMPLES.put(IdentifierCategory.MRN, new String[]{"MRN: SYN-554433 verified", "554433"});
        SAMPLES.put(IdentifierCategory.HEALTH_PLAN, new String[]{"Insurance member id: ZXC987654321", "ZXC987654321"});
        SAMPLES.put(IdentifierCategory.ACCOUNT, new String[]{"Billing account number 77881122 is overdue", "77881122"});
        SAMPLES.put(IdentifierCategory.LICENSE, new String[]{"Driver's license D1234567 presented", "D1234567"});
        SAMPLES.put(IdentifierCategory.VEHICLE, new String[]{"Arrived by car, license plate ABC-1234", "ABC-1234"});
        SAMPLES.put(IdentifierCategory.DEVICE, new String[]{"Pacemaker serial number PM-5566-77 implanted", "PM-5566-77"});
        SAMPLES.put(IdentifierCategory.URL, new String[]{"Portal at https://portal.example.invalid/u/faux", "portal.example.invalid"});
        SAMPLES.put(IdentifierCategory.IP, new String[]{"Logged in from 203.0.113.42 yesterday", "203.0.113.42"});
        SAMPLES.put(IdentifierCategory.BIOMETRIC, new String[]{"Fingerprint template: a1b2c3d4e5 stored", "a1b2c3d4e5"});
        SAMPLES.put(IdentifierCategory.PHOTO, new String[]{"Attached face image patient_face_001.jpg", "patient_face_001"});
        SAMPLES.put(IdentifierCategory.OTHER_UNIQUE_ID, new String[]{"Research participant code QX7741ZZ90K assigned", "QX7741ZZ90K"});
    }

    @Test
    void samplesCoverEveryCategoryExceptStructuralOnes() {
        // no category may be added to the enum without a sample here
        assertThat(SAMPLES.keySet()).containsAll(List.of(IdentifierCategory.values()));
        assertThat(IdentifierCategory.values()).hasSize(18);
    }

    @ParameterizedTest
    @EnumSource(IdentifierCategory.class)
    void freeTextIsScrubbedForEveryCategory(IdentifierCategory c) {
        String[] s = SAMPLES.get(c);
        String out = d.scrubText(s[0]);
        assertThat(out).as(c.name()).doesNotContain(s[1]);
    }

    @ParameterizedTest
    @EnumSource(IdentifierCategory.class)
    void fhirStringFieldsAreScrubbedForEveryCategory(IdentifierCategory c) throws Exception {
        String[] s = SAMPLES.get(c);
        var node = M.createObjectNode();
        node.put("resourceType", "Observation");
        node.putObject("code").put("text", s[0]);
        node.put("valueString", s[0]);
        String out = d.deidentify(node).toString();
        assertThat(out).as(c.name()).doesNotContain(s[1]);
    }

    @Test
    void dateKeepsOnlyYear() {
        assertThat(d.scrubText("On 2025-11-30 and 1/2/2024 and Jan 5, 2023 and 7 July 2022")).isEqualTo("On 2025 and 2024 and 2023 and 2022");
        assertThat(d.scrubText("seen March 4 again")).isEqualTo("seen [DATE] again");
    }

    @Test
    void agesOver89AreAggregated() {
        assertThat(d.scrubText("a 93-year-old man")).contains("90+ years old").doesNotContain("93");
        assertThat(d.scrubText("a 45-year-old man")).contains("45-year-old");
    }

    @Test
    void knownTermsAreRemoved() {
        assertThat(d.scrubText("alex went home; ALEX is stable", List.of("Alex"))).doesNotContainIgnoringCase("alex");
    }

    @Test
    void ordinaryClinicalTextIsPreserved() {
        String t = "Assessment: type 2 diabetes mellitus, stage 3. Current therapy: metformin 500 mg twice daily. The device used was fine.";
        assertThat(d.scrubText(t)).isEqualTo(t);
    }

    @Test
    void fhirPatientStructuralRules() throws Exception {
        String patient = """
                {"resourceType":"Patient","id":"6f9a1d2e-1111-4222-8333-444455556666","meta":{"lastUpdated":"2026-01-01T00:00:00Z"},
                 "text":{"div":"<div>Alex Testerson</div>"},
                 "identifier":[{"system":"urn:synthetic:mrn","value":"SYN-1"},{"system":"urn:synthetic:ssn","value":"900-11-2222"}],
                 "name":[{"family":"Testerson","given":["Alex"]}],
                 "telecom":[{"system":"phone","value":"555-111-2222"},{"system":"email","value":"a@example.invalid"}],
                 "gender":"male","birthDate":"1980-03-04",
                 "address":[{"line":["1 Main Street"],"city":"Faketown","district":"Fake County","state":"MA","postalCode":"02139-1234","country":"US"}],
                 "photo":[{"contentType":"image/jpeg","data":"AAAA"}],
                 "extension":[{"url":"http://x","valueString":"secret"}],
                 "contact":[{"name":{"family":"Kin"}}]}
                """;
        JsonNode out = d.deidentify(M.readTree(patient));
        String s = out.toString();
        assertThat(s).doesNotContain("Testerson", "Alex", "SYN-1", "900-11-2222", "555-111-2222", "example.invalid", "1 Main", "Faketown",
                "Fake County", "02139", "AAAA", "secret", "Kin", "lastUpdated", "1980-03-04", "03-04");
        assertThat(out.path("birthDate").asText()).isEqualTo("1980");
        assertThat(out.path("gender").asText()).isEqualTo("male");
        assertThat(out.at("/address/0/state").asText()).isEqualTo("MA");
        assertThat(out.at("/address/0/postalCode").asText()).isEqualTo("021");
        assertThat(out.path("id").asText()).isEqualTo("6f9a1d2e-1111-4222-8333-444455556666");
        assertThat(out.has("name") || out.has("telecom") || out.has("identifier")).isFalse();
    }

    @Test
    void over89BirthDateDroppedAndRestrictedZip3() throws Exception {
        JsonNode out = d.deidentify(M.readTree("{\"resourceType\":\"Patient\",\"birthDate\":\"1930-05-05\",\"address\":[{\"postalCode\":\"03601\"}]}"));
        assertThat(out.has("birthDate")).isFalse();
        assertThat(out.at("/address/0/postalCode").asText()).isEqualTo("000");
        // exactly 89 (born 1937, now 2026) stays as year
        JsonNode edge = d.deidentify(M.readTree("{\"resourceType\":\"Patient\",\"birthDate\":\"1937-05-05\"}"));
        assertThat(edge.path("birthDate").asText()).isEqualTo("1937");
        JsonNode edge90 = d.deidentify(M.readTree("{\"resourceType\":\"Patient\",\"birthDate\":\"1936-05-05\"}"));
        assertThat(edge90.has("birthDate")).isFalse();
    }

    @Test
    void referencesAndIdsKeepOnlyPseudonymousValues() throws Exception {
        JsonNode out = d.deidentify(M.readTree("""
                {"resourceType":"Observation","id":"SYN-12345","subject":{"reference":"Patient/SYN-12345","display":"Alex Testerson"},
                 "performer":[{"reference":"Patient/6f9a1d2e-1111-4222-8333-444455556666"}],"effectiveDateTime":"2026-02-03T10:15:00Z"}"""));
        assertThat(out.has("id")).isFalse();
        assertThat(out.at("/subject").has("reference")).isFalse();
        assertThat(out.at("/performer/0/reference").asText()).startsWith("Patient/6f9a");
        assertThat(out.path("effectiveDateTime").asText()).isEqualTo("2026");
    }

    @Test
    void codingVocabularySurvives() throws Exception {
        JsonNode out = d.deidentify(M.readTree("""
                {"resourceType":"Observation","status":"final","code":{"coding":[{"system":"http://loinc.org","code":"4548-4","display":"Hemoglobin A1c"}]},
                 "valueQuantity":{"value":7.2,"unit":"%"}}"""));
        assertThat(out.at("/code/coding/0/system").asText()).isEqualTo("http://loinc.org");
        assertThat(out.at("/code/coding/0/code").asText()).isEqualTo("4548-4");
        assertThat(out.at("/valueQuantity/value").asDouble()).isEqualTo(7.2);
    }

    @Test
    void vocabularyFieldsStillScrubHighConfidenceIdentifiers() throws Exception {
        JsonNode out = d.deidentify(M.readTree("{\"resourceType\":\"Observation\",\"code\":{\"coding\":[{\"code\":\"900-12-3456\"}]}}"));
        assertThat(out.toString()).doesNotContain("900-12-3456");
    }

    @Test
    void imageAttachmentsDroppedTextAttachmentsScrubbed() throws Exception {
        String note = java.util.Base64.getEncoder().encodeToString("Call 555-867-5309 about breast cancer".getBytes());
        JsonNode out = d.deidentify(M.readTree("{\"resourceType\":\"DocumentReference\",\"content\":["
                + "{\"attachment\":{\"contentType\":\"image/png\",\"data\":\"AAAA\"}},"
                + "{\"attachment\":{\"contentType\":\"text/plain\",\"data\":\"" + note + "\",\"url\":\"http://h/x\"}}]}"));
        String s = out.toString();
        assertThat(s).doesNotContain("AAAA", "http://h");
        String decoded = new String(java.util.Base64.getDecoder().decode(out.at("/content/0/attachment/data").asText()));
        assertThat(decoded).isEqualTo("Call [PHONE] about breast cancer");
    }

    @Test
    void inputIsNotMutated() throws Exception {
        JsonNode in = M.readTree("{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Testerson\"}]}");
        String before = in.toString();
        d.deidentify(in);
        assertThat(in.toString()).isEqualTo(before);
    }

    @Test
    void everyCategoryHasAtLeastOnePatternRuleOrIsStructural() {
        var perCat = SafeHarborDeidentifier.patternRulesPerCategory();
        for (IdentifierCategory c : IdentifierCategory.values()) {
            assertThat(perCat).as("pattern rule for " + c).containsKey(c);
        }
    }
}
