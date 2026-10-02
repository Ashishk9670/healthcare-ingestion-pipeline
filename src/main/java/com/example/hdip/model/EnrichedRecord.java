package com.example.hdip.model;

import java.time.Instant;

/** Canonical record plus derived attributes; published to phi.enriched. */
public record EnrichedRecord(CanonicalRecord record, String ageBand, Instant enrichedAt) {
}
