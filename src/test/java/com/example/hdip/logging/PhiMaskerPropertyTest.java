package com.example.hdip.logging;

import net.jqwik.api.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Property tests: masked output never contains generated (SYNTHETIC) SSN / MRN / DOB values. */
class PhiMaskerPropertyTest {

    @Provide
    Arbitrary<String> ssns() {
        return Arbitraries.integers().between(100, 999).flatMap(a ->
                Arbitraries.integers().between(10, 99).flatMap(b ->
                        Arbitraries.integers().between(1000, 9999).map(c -> a + "-" + b + "-" + c)));
    }

    @Provide
    Arbitrary<String> mrns() {
        return Arbitraries.integers().between(100000, 99999999).map(n -> "SYN-" + n);
    }

    @Provide
    Arbitrary<String> dobs() {
        return Arbitraries.integers().between(1920, 2025).flatMap(y ->
                Arbitraries.integers().between(1, 12).flatMap(m ->
                        Arbitraries.integers().between(1, 28).map(d -> String.format("%04d-%02d-%02d", y, m, d))));
    }

    @Provide
    Arbitrary<String> words() {
        return Arbitraries.of("received", "message", "for", "processed", "normalized", "queue", "stage", "ok", "retry");
    }

    @Property(tries = 500)
    void ssnNeverSurvivesInFreeText(@ForAll("ssns") String ssn, @ForAll("words") String pre, @ForAll("words") String post) {
        String out = PhiMasker.mask(pre + " " + ssn + " " + post);
        assertThat(out).doesNotContain(ssn);
    }

    @Property(tries = 500)
    void mrnTokenNeverSurvivesInFreeText(@ForAll("mrns") String mrn, @ForAll("words") String pre, @ForAll("words") String post) {
        String out = PhiMasker.mask(pre + " " + mrn + " " + post);
        assertThat(out).doesNotContain(mrn);
    }

    @Property(tries = 500)
    void keyedFieldsAreMasked(@ForAll("ssns") String ssn, @ForAll("mrns") String mrn, @ForAll("dobs") String dob,
                              @ForAll("words") String word) {
        String kv = "word=" + word + " ssn=" + ssn + " mrn=" + mrn + " dob=" + dob;
        String json = "{\"note\":\"" + word + "\",\"ssn\":\"" + ssn + "\",\"mrn\":\"" + mrn + "\",\"birthDate\":\"" + dob + "\"}";
        for (String input : new String[]{kv, json}) {
            String out = PhiMasker.mask(input);
            assertThat(out).doesNotContain(ssn).doesNotContain(mrn).doesNotContain(dob);
        }
    }

    @Property(tries = 500)
    void pidSegmentIsMasked(@ForAll("ssns") String ssn, @ForAll("mrns") String mrn, @ForAll("dobs") String dob) {
        String compactDob = dob.replace("-", "");
        String msg = "MSH|^~\\&|A|B|C|D|2026||ADT^A01|1|P|2.5\rPID|1||" + mrn + "^^^S^MR||Faux^Casey||" + compactDob + "|F|||||||||||" + ssn
                + "\rOBX|1|NM|1^x||5";
        String out = PhiMasker.mask(msg);
        assertThat(out).doesNotContain(ssn).doesNotContain(mrn).doesNotContain(compactDob).doesNotContain("Faux")
                .contains("OBX|1|NM");
    }

    @Property(tries = 300)
    void maskingIsIdempotentAndNullSafe(@ForAll("ssns") String ssn, @ForAll("words") String word) {
        String once = PhiMasker.mask(word + " " + ssn);
        assertThat(PhiMasker.mask(once)).isEqualTo(once);
        assertThat(PhiMasker.mask(null)).isNull();
    }

    @Property(tries = 500)
    void neverThrowsOnArbitraryInput(@ForAll String any) {
        assertThat(PhiMasker.mask(any)).isNotNull().satisfies(o -> assertThat(o.length()).isGreaterThanOrEqualTo(0));
    }
}
