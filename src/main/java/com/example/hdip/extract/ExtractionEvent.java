package com.example.hdip.extract;

import java.time.Instant;
import java.util.List;

/** Published to phi.extracted. Carries the HMAC of the MRN (never the MRN or note text). */
public record ExtractionEvent(String messageId, String mrnHash, List<ExtractedFact> facts, Instant extractedAt) {
}
