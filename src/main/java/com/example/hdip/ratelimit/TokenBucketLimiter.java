package com.example.hdip.ratelimit;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Small in-memory token bucket per key (per JVM). For multi-replica deployments also rate-limit at the gateway. */
public class TokenBucketLimiter {
    private static final int MAX_KEYS = 10_000;

    private final double refillPerSecond;
    private final int burst;
    private final Clock clock;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketLimiter(int requestsPerMinute, int burst, Clock clock) {
        this.refillPerSecond = requestsPerMinute / 60.0;
        this.burst = burst;
        this.clock = clock;
    }

    /** Returns 0 if the request is allowed, otherwise the number of seconds to wait before retrying. */
    public long tryAcquire(String key) {
        if (buckets.size() > MAX_KEYS) buckets.clear(); // crude bound against key-flooding
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(burst, clock.millis()));
        synchronized (b) {
            long now = clock.millis();
            b.tokens = Math.min(burst, b.tokens + (now - b.last) / 1000.0 * refillPerSecond);
            b.last = now;
            if (b.tokens >= 1) {
                b.tokens -= 1;
                return 0;
            }
            return Math.max(1, (long) Math.ceil((1 - b.tokens) / refillPerSecond));
        }
    }

    private static final class Bucket {
        double tokens;
        long last;

        Bucket(double tokens, long last) {
            this.tokens = tokens;
            this.last = last;
        }
    }
}
