package com.example.hdip.deid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.jqwik.api.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * For generated SYNTHETIC inputs, no output field may contain any of the generated identifiers, one generator per
 * Safe Harbor category (45 CFR 164.514(b)(2)(i)(A)-(R)).
 */
class SafeHarborDeidentifierPropertyTest {
    private static final ObjectMapper M = new ObjectMapper();
    private final SafeHarborDeidentifier d = new SafeHarborDeidentifier(Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC));

    /** An identifier as it would appear in text, the sensitive string that must not survive, and its category. */
    record Sample(IdentifierCategory category, String text, String sensitive) {
    }

    private static Arbitrary<String> digits(int n) {
        return Arbitraries.strings().numeric().ofLength(n);
    }

    private static Arbitrary<String> upper(int min, int max) {
        return Arbitraries.strings().withCharRange('A', 'Z').ofMinLength(min).ofMaxLength(max);
    }

    private static Arbitrary<Sample> of(IdentifierCategory c, Arbitrary<String> sensitive, java.util.function.Function<String, String> text) {
        return sensitive.map(s -> new Sample(c, text.apply(s), s));
    }

    private static Arbitrary<Integer> between(int a, int b) {
        return Arbitraries.integers().between(a, b);
    }

    @Provide
    Arbitrary<Sample> samples() {
        Arbitrary<String> firstNames = Arbitraries.of("Alexander", "Morgan", "Riley", "Jordan", "Casey", "Taylor");
        Arbitrary<String> lastNames = Arbitraries.of("Testerson", "Faux", "Synthman", "Placeholder", "Mockley", "Dummerton");
        Arbitrary<String> streets = Arbitraries.of("Elm Street", "Oak Avenue", "Maple Road", "Cedar Lane", "Pine Drive");
        Arbitrary<String> ssn = Combinators.combine(between(100, 999), between(10, 99), between(1000, 9999)).as((a, b, c) -> a + "-" + b + "-" + c);
        Arbitrary<String> phone = Combinators.combine(between(200, 999), between(200, 999), between(1000, 9999)).as((a, b, c) -> a + "-" + b + "-" + c);
        Arbitrary<String> ip = Combinators.combine(between(1, 254), between(0, 255), between(0, 255), between(1, 254)).as((a, b, c, e) -> a + "." + b + "." + c + "." + e);
        Arbitrary<String> mdy = Combinators.combine(between(1, 12), between(1, 28), between(1930, 2025)).as((m, day, y) -> String.format("%02d/%02d/%04d", m, day, y));
        Arbitrary<String> email = Combinators.combine(Arbitraries.strings().withCharRange('a', 'z').ofMinLength(4).ofMaxLength(10), Arbitraries.of("example.invalid", "test.invalid"))
                .as((u, h) -> u + "@" + h);
        Arbitrary<String> url = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(4).ofMaxLength(10).map(h -> "https://" + h + ".example.invalid/u/faux");
        Arbitrary<String> address = Combinators.combine(between(10, 99999), streets).as((n, s) -> n + " " + s);

        return Arbitraries.oneOf(
                of(IdentifierCategory.NAME, lastNames, s -> "Seen today with Mr. Alexander " + s + " for follow-up"),
                of(IdentifierCategory.GEOGRAPHIC, address, s -> "Lives at " + s + " near the clinic"),
                of(IdentifierCategory.GEOGRAPHIC, digits(5), s -> "Resident of area " + s + " per intake"),
                of(IdentifierCategory.DATE, mdy, s -> "Admitted on " + s + " for observation"),
                of(IdentifierCategory.PHONE, phone, s -> "Call " + s + " to reschedule"),
                of(IdentifierCategory.FAX, phone, s -> "Send records, fax: " + s),
                of(IdentifierCategory.EMAIL, email, s -> "Contact " + s + " for results"),
                of(IdentifierCategory.SSN, ssn, s -> "SSN on file " + s),
                of(IdentifierCategory.MRN, digits(6), s -> "MRN: SYN-" + s + " verified"),
                of(IdentifierCategory.HEALTH_PLAN, digits(9), s -> "Insurance member id: ZXC" + s),
                of(IdentifierCategory.ACCOUNT, digits(8), s -> "Billing account number " + s + " is overdue"),
                of(IdentifierCategory.LICENSE, digits(7), s -> "Driver's license D" + s + " presented"),
                of(IdentifierCategory.VEHICLE, between(1000, 9999).map(String::valueOf), s -> "Arrived by car, license plate ABC-" + s),
                of(IdentifierCategory.DEVICE, digits(6), s -> "Pacemaker serial number PM-" + s),
                of(IdentifierCategory.URL, url, s -> "Portal at " + s),
                of(IdentifierCategory.IP, ip, s -> "Logged in from " + s + " yesterday"),
                of(IdentifierCategory.BIOMETRIC, Arbitraries.strings().withCharRange('a', 'f').numeric().ofLength(10).filter(s -> s.chars().anyMatch(Character::isDigit)),
                        s -> "Fingerprint template: " + s + " stored"),
                of(IdentifierCategory.PHOTO, digits(6), s -> "Attached face image patient_face_" + s + ".jpg"),
                of(IdentifierCategory.OTHER_UNIQUE_ID, Combinators.combine(upper(2, 3), digits(5), upper(1, 2)).as((a, b, c) -> a + b + c),
                        s -> "Research participant code " + s + " assigned"));
    }

    @Property(tries = 1000)
    void freeTextNeverRetainsGeneratedIdentifier(@ForAll("samples") Sample s) {
        String out = d.scrubText(s.text());
        assertThat(out).as("category %s input '%s'", s.category(), s.text()).doesNotContain(s.sensitive());
    }

    @Property(tries = 500)
    void knownTermsAreAlwaysRemoved(@ForAll("names") String given, @ForAll("names") String family) {
        String text = "Follow-up for " + given + " " + family + ". " + family + " is doing well.";
        String out = d.scrubText(text, java.util.List.of(given, family));
        assertThat(out).doesNotContainIgnoringCase(family);
        assertThat(out).doesNotContainIgnoringCase(given);
    }

    @Provide
    Arbitrary<String> names() {
        return Arbitraries.strings().withCharRange('A', 'Z').ofMinLength(1).ofMaxLength(1)
                .flatMap(f -> Arbitraries.strings().withCharRange('a', 'z').ofMinLength(5).ofMaxLength(10).map(r -> f + r));
    }

    /** Whole FHIR Patient + Observation bundle carrying generated identifiers in many fields. */
    @Property(tries = 500)
    void fhirBundleOutputContainsNoGeneratedIdentifier(@ForAll("samples") Sample s1, @ForAll("samples") Sample s2,
                                                       @ForAll("names") String family, @ForAll("names") String given,
                                                       @ForAll("ymd") String birthDate) {
        ObjectNode patient = M.createObjectNode().put("resourceType", "Patient").put("id", java.util.UUID.randomUUID().toString());
        ArrayNode name = patient.putArray("name");
        name.addObject().put("family", family).putArray("given").add(given);
        patient.putArray("telecom").addObject().put("system", "phone").put("value", "555-010-0199");
        patient.putArray("identifier").addObject().put("value", s1.sensitive());
        patient.putArray("address").addObject().put("city", "Faketown").put("postalCode", "12345").put("state", "MA");
        patient.put("birthDate", birthDate);

        ObjectNode obs = M.createObjectNode().put("resourceType", "Observation").put("status", "final");
        obs.putObject("code").put("text", s1.text());
        obs.put("valueString", s2.text());
        obs.putArray("note").addObject().put("text", s1.text() + " " + s2.text());

        ObjectNode bundle = M.createObjectNode().put("resourceType", "Bundle").put("type", "collection");
        ArrayNode e = bundle.putArray("entry");
        e.addObject().set("resource", patient);
        e.addObject().set("resource", obs);

        JsonNode out = d.deidentify(bundle);
        // surrogate UUID ids are intentionally retained; strip them so short digit runs cannot collide with them
        String json = out.toString().replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "<uuid>");

        assertThat(json).doesNotContain(s1.sensitive()).doesNotContain(s2.sensitive())
                .doesNotContain(family).doesNotContain(given)
                .doesNotContain("555-010-0199").doesNotContain("Faketown").doesNotContain("12345")
                .doesNotContain(birthDate);
        // input untouched
        assertThat(bundle.toString()).contains(family);
    }

    @Provide
    Arbitrary<String> ymd() {
        return Combinators.combine(between(1930, 2025), between(1, 12), between(1, 28)).as((y, m, day) -> String.format("%04d-%02d-%02d", y, m, day));
    }
}
