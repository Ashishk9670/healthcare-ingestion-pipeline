package com.example.hdip.parse;

import com.example.hdip.model.CanonicalRecord;
import net.jqwik.api.*;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.NumericChars;
import net.jqwik.api.constraints.StringLength;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Property tests for the HL7 v2 parser. All generated values are SYNTHETIC. */
class Hl7V2ParserPropertyTest {
    private final Hl7V2Parser parser = new Hl7V2Parser();
    private static final String MSH = "MSH|^~\\&|SRC|FAC|DST|FAC|20260101120000||ORU^R01|MSG1|P|2.5";

    @Provide
    Arbitrary<String> segment() {
        Arbitrary<String> name = Arbitraries.of("PID", "OBX", "DG1", "NTE", "NK1", "ZZZ", "MSH", "", "P");
        Arbitrary<String> body = Arbitraries.strings().withChars("|^~\\&-.:+ ABCxyz019").ofMaxLength(60);
        return Combinators.combine(name, body).as((n, b) -> n + "|" + b);
    }

    @Property(tries = 500)
    void neverThrowsAnythingButParseExceptionOnArbitrarySegments(@ForAll("segments") List<String> segs) {
        String msg = MSH + "\r" + String.join("\r", segs);
        try {
            CanonicalRecord r = parser.parse("id", msg);
            assertThat(r).isNotNull();
        } catch (MessageParseException expected) {
            // acceptable: malformed input is rejected with a typed exception
        }
    }

    @Provide
    Arbitrary<List<String>> segments() {
        return segment().list().ofMaxSize(8);
    }

    @Property(tries = 300)
    void neverThrowsOnArbitraryText(@ForAll String text) {
        try {
            parser.parse("id", text);
        } catch (MessageParseException expected) {
            // fine
        }
    }

    @Property(tries = 300)
    void neverThrowsOnArbitraryTextAfterMshHeader(@ForAll @StringLength(max = 200) String text) {
        try {
            parser.parse(null, MSH + "\r" + text);
        } catch (MessageParseException expected) {
            // fine
        }
    }

    @Property(tries = 300)
    void roundTripsKeyPidFields(@ForAll @AlphaChars @StringLength(min = 3, max = 12) String mrn,
                                @ForAll @AlphaChars @StringLength(min = 2, max = 12) String family,
                                @ForAll @AlphaChars @StringLength(min = 2, max = 12) String given,
                                @ForAll("dobs") LocalDate dob,
                                @ForAll("genders") String gender,
                                @ForAll("ssns") String ssn) {
        String pid = "PID|1||" + mrn + "^^^SYN^MR||" + family + "^" + given + "||"
                + dob.format(DateTimeFormatter.BASIC_ISO_DATE) + "|" + gender + "|||||||||||" + ssn;
        CanonicalRecord r = parser.parse("rt-1", MSH + "\r" + pid);

        assertThat(r.messageId()).isEqualTo("rt-1");
        assertThat(r.patient().mrn()).isEqualTo(mrn);
        assertThat(r.patient().familyName()).isEqualTo(family);
        assertThat(r.patient().givenName()).isEqualTo(given);
        assertThat(r.patient().birthDate()).isEqualTo(dob);
        assertThat(r.patient().gender()).isEqualTo(Hl7V2Parser.mapGender(gender));
        assertThat(r.patient().ssn()).isEqualTo(ssn);
    }

    @Property(tries = 200)
    void roundTripsObservationValues(@ForAll @AlphaChars @StringLength(min = 3, max = 8) String mrn,
                                     @ForAll @NumericChars @StringLength(min = 3, max = 6) String loinc,
                                     @ForAll("decimals") String value) {
        String msg = MSH + "\rPID|1||" + mrn + "\rOBX|1|NM|" + loinc + "^Test^LN||" + value + "|mg/dL|||||F|||20260101120000";
        CanonicalRecord r = parser.parse("obs-1", msg);
        assertThat(r.observations()).hasSize(1);
        assertThat(r.observations().get(0).valueNumeric()).isEqualByComparingTo(value);
    }

    @Provide
    Arbitrary<LocalDate> dobs() {
        return Arbitraries.integers().between(1920, 2025).flatMap(y ->
                Arbitraries.integers().between(1, 12).flatMap(m ->
                        Arbitraries.integers().between(1, 28).map(d -> LocalDate.of(y, m, d))));
    }

    @Provide
    Arbitrary<String> genders() {
        return Arbitraries.of("M", "F", "O", "U", "A");
    }

    @Provide
    Arbitrary<String> ssns() {
        return Arbitraries.integers().between(900, 999).flatMap(a ->
                Arbitraries.integers().between(10, 99).flatMap(b ->
                        Arbitraries.integers().between(1000, 9999).map(c -> a + "-" + b + "-" + c)));
    }

    @Provide
    Arbitrary<String> decimals() {
        return Arbitraries.bigDecimals().between(java.math.BigDecimal.ZERO, java.math.BigDecimal.valueOf(100000))
                .ofScale(2).map(java.math.BigDecimal::toPlainString);
    }
}
