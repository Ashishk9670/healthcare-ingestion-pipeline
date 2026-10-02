package com.example.hdip.synthetic;

import com.example.hdip.parse.FhirJsonNormalizer;
import com.example.hdip.parse.Hl7V2Parser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class SyntheticDataGeneratorTest {
    final ObjectMapper mapper = new ObjectMapper();
    final SyntheticDataGenerator gen = new SyntheticDataGenerator(mapper, new Random(42));

    @Test
    void hl7RoundTripsThroughParser() {
        var p = gen.patient();
        var rec = new Hl7V2Parser().parse(null, gen.hl7Message(p));
        assertThat(rec.patient().mrn()).startsWith("SYN-").isEqualTo(p.mrn());
        assertThat(rec.patient().familyName()).isEqualTo(p.family());
        assertThat(rec.patient().birthDate()).isEqualTo(p.dob());
        assertThat(rec.patient().ssn()).startsWith("9"); // never-issued SSN area range
        assertThat(rec.observations()).hasSize(3);
        assertThat(rec.diagnoses()).hasSameSizeAs(p.diagnoses());
    }

    @Test
    void fhirRoundTripsThroughNormalizer() {
        var p = gen.patient();
        var rec = new FhirJsonNormalizer(mapper).parse(null, gen.fhirBundle(p));
        assertThat(rec.patient().mrn()).isEqualTo(p.mrn());
        assertThat(rec.patient().givenName()).isEqualTo(p.given());
        assertThat(rec.observations()).hasSize(3);
    }

    @Test
    void deterministicWithSeed() {
        var a = new SyntheticDataGenerator(mapper, new Random(1)).patient();
        var b = new SyntheticDataGenerator(mapper, new Random(1)).patient();
        assertThat(a.mrn()).isEqualTo(b.mrn());
        assertThat(a.ssn()).isEqualTo(b.ssn());
        assertThat(a.dob()).isEqualTo(b.dob());
    }
}
