package com.example.hdip.ratelimit;

import com.example.hdip.observability.PipelineMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class IngestGuardFilterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PipelineMetrics metrics = new PipelineMetrics(registry);

    private IngestGuardFilter filter(long maxBytes, int rpm, int burst) {
        var props = new IngestProperties(maxBytes, 500, new IngestProperties.RateLimit(true, rpm, burst));
        return new IngestGuardFilter(props, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), metrics);
    }

    private MockHttpServletResponse call(IngestGuardFilter f, String uri, byte[] body) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setRequestURI(uri);
        req.setContent(body);
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    void rejectsOversizedPayloadWith413AndNeverEchoesContent() throws Exception {
        var res = call(filter(10, 600, 100), "/api/v1/ingest/hl7", "0123456789ABCDEF".getBytes());
        assertEquals(413, res.getStatus());
        assertFalse(res.getContentAsString().contains("0123456789"));
        assertEquals(1.0, registry.counter(PipelineMetrics.INGEST_REJECTED, "reason", "payload_too_large").count());
    }

    @Test
    void allowsPayloadWithinLimit() throws Exception {
        assertEquals(200, call(filter(1024, 600, 100), "/api/v1/ingest/hl7", "MSH|x".getBytes()).getStatus());
    }

    @Test
    void rateLimitsAfterBurstWith429AndRetryAfter() throws Exception {
        var f = filter(1024, 60, 2);
        assertEquals(200, call(f, "/api/v1/ingest/hl7", new byte[0]).getStatus());
        assertEquals(200, call(f, "/api/v1/ingest/hl7", new byte[0]).getStatus());
        var res = call(f, "/api/v1/ingest/hl7", new byte[0]);
        assertEquals(429, res.getStatus());
        assertNotNull(res.getHeader("Retry-After"));
        assertEquals(1.0, registry.counter(PipelineMetrics.INGEST_REJECTED, "reason", "rate_limited").count());
    }

    @Test
    void ignoresNonIngestPaths() throws Exception {
        var f = filter(1, 60, 1);
        assertEquals(200, call(f, "/api/v1/other", "way too large".getBytes()).getStatus());
    }

    @Test
    void tokenBucketRefillsOverTime() {
        long[] now = {0};
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(now[0]); }
        };
        var l = new TokenBucketLimiter(60, 1, clock);
        assertEquals(0, l.tryAcquire("a"));
        assertTrue(l.tryAcquire("a") > 0);
        assertEquals(0, l.tryAcquire("b"));   // independent key
        now[0] += 1500;
        assertEquals(0, l.tryAcquire("a"));
    }
}
