package com.example.hdip.enrich;

import com.example.hdip.model.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EnrichmentServiceTest {
    static final LocalDate TODAY = LocalDate.of(2026, 6, 15);
    final EnrichmentService svc = new EnrichmentService(new CodingLookup(),
            Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void ageBands() {
        assertThat(AgeBand.of(TODAY.minusYears(5), TODAY)).isEqualTo("0-17");
        assertThat(AgeBand.of(TODAY.minusYears(18), TODAY)).isEqualTo("18-34");
        assertThat(AgeBand.of(TODAY.minusYears(35).plusDays(1), TODAY)).isEqualTo("18-34");
        assertThat(AgeBand.of(TODAY.minusYears(49), TODAY)).isEqualTo("35-49");
        assertThat(AgeBand.of(TODAY.minusYears(64), TODAY)).isEqualTo("50-64");
        assertThat(AgeBand.of(TODAY.minusYears(79), TODAY)).isEqualTo("65-79");
        assertThat(AgeBand.of(TODAY.minusYears(80), TODAY)).isEqualTo("80+");
        assertThat(AgeBand.of(null, TODAY)).isEqualTo("unknown");
        assertThat(AgeBand.of(TODAY.plusDays(1), TODAY)).isEqualTo("unknown");
    }

    @Test
    void enrichesDisplaysAndBand() {
        var rec = new CanonicalRecord("m", "HL7V2",
                new CanonicalPatient("M1", "F", "G", TODAY.minusYears(70), "male", null),
                List.of(new CanonicalObservation("4548-4", "LN", null, null, null, null, null),
                        new CanonicalObservation("ZZZ", null, "Kept", null, null, null, null)),
                List.of(new CanonicalDiagnosis("E11.9", null, null), new CanonicalDiagnosis("UNKNOWN", null, null)));
        EnrichedRecord e = svc.enrich(rec);
        assertThat(e.ageBand()).isEqualTo("65-79");
        assertThat(e.enrichedAt()).isEqualTo(Instant.parse("2026-06-15T00:00:00Z"));
        assertThat(e.record().observations().get(0).display()).contains("Hemoglobin A1c");
        assertThat(e.record().observations().get(1).display()).isEqualTo("Kept");
        assertThat(e.record().diagnoses().get(0).display()).contains("diabetes");
        assertThat(e.record().diagnoses().get(1).display()).isNull();
    }
}
