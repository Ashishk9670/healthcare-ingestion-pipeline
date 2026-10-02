package com.example.hdip.enrich;

import com.example.hdip.model.*;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

@Service
public class EnrichmentService {
    private final CodingLookup lookup;
    private final Clock clock;

    public EnrichmentService(CodingLookup lookup, Clock clock) {
        this.lookup = lookup;
        this.clock = clock;
    }

    public EnrichedRecord enrich(CanonicalRecord rec) {
        var obs = rec.observations().stream().map(o -> o.display() != null ? o
                : new CanonicalObservation(o.code(), o.system(), lookup.display(o.code()).orElse(null),
                o.valueText(), o.valueNumeric(), o.unit(), o.effectiveAt())).toList();
        var dx = rec.diagnoses().stream().map(d -> d.display() != null ? d
                : new CanonicalDiagnosis(d.code(), d.system(), lookup.display(d.code()).orElse(null))).toList();
        var canonical = new CanonicalRecord(rec.messageId(), rec.source(), rec.patient(), obs, dx);
        String band = AgeBand.of(rec.patient().birthDate(), LocalDate.now(clock));
        return new EnrichedRecord(canonical, band, Instant.now(clock));
    }
}
