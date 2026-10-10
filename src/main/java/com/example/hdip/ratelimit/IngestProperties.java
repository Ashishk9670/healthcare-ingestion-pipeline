package com.example.hdip.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Limits for the ingestion endpoints ({@code app.ingest.*}). */
@ConfigurationProperties(prefix = "app.ingest")
public record IngestProperties(long maxPayloadBytes, int maxSyntheticCount, RateLimit rateLimit) {
    public IngestProperties {
        if (maxPayloadBytes <= 0) maxPayloadBytes = 1_048_576;
        if (maxSyntheticCount <= 0) maxSyntheticCount = 500;
        if (rateLimit == null) rateLimit = new RateLimit(true, 600, 100);
    }

    public record RateLimit(boolean enabled, int requestsPerMinute, int burst) {
        public RateLimit {
            if (requestsPerMinute <= 0) requestsPerMinute = 600;
            if (burst <= 0) burst = 100;
        }
    }
}
