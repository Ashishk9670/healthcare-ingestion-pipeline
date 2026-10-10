package com.example.hdip.ratelimit;

import com.example.hdip.observability.PipelineMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;

/**
 * Protects the ingestion endpoints: per-principal rate limit (429 + Retry-After) and a hard request-body size
 * limit (413). Runs after Spring Security, so only authenticated callers consume rate-limit buckets. Responses never
 * echo request content.
 */
public class IngestGuardFilter extends OncePerRequestFilter {
    private final IngestProperties props;
    private final TokenBucketLimiter limiter;
    private final PipelineMetrics metrics;

    public IngestGuardFilter(IngestProperties props, Clock clock, PipelineMetrics metrics) {
        this.props = props;
        this.metrics = metrics;
        this.limiter = new TokenBucketLimiter(props.rateLimit().requestsPerMinute(), props.rateLimit().burst(), clock);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return !(p.startsWith("/api/v1/ingest/") || p.startsWith("/api/v1/synthetic"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (props.rateLimit().enabled()) {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            String key = a != null && a.isAuthenticated() ? "u:" + a.getName() : "ip:" + request.getRemoteAddr();
            long retryAfter = limiter.tryAcquire(key);
            if (retryAfter > 0) {
                metrics.ingestRejected("rate_limited");
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader("Retry-After", String.valueOf(retryAfter));
                response.setContentType("application/problem+json");
                response.getWriter().write("{\"title\":\"Too Many Requests\",\"status\":429,\"detail\":\"Ingestion rate limit exceeded\"}");
                return;
            }
        }
        long max = props.maxPayloadBytes();
        if (request.getContentLengthLong() > max) {
            tooLarge(response);
            return;
        }
        try {
            chain.doFilter(new LimitedRequest(request, max), response);
        } catch (PayloadTooLargeException e) {
            if (response.isCommitted()) throw e;
            tooLarge(response);
        } catch (ServletException e) {
            if (e.getRootCause() instanceof PayloadTooLargeException && !response.isCommitted()) {
                tooLarge(response);
            } else {
                throw e;
            }
        }
    }

    private void tooLarge(HttpServletResponse response) throws IOException {
        metrics.ingestRejected("payload_too_large");
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"title\":\"Payload Too Large\",\"status\":413,\"detail\":\"Message exceeds the maximum allowed size\"}");
    }

    static class PayloadTooLargeException extends RuntimeException {
        PayloadTooLargeException() {
            super("payload too large", null, false, false);
        }
    }

    /** Counts bytes as they are read so chunked bodies without Content-Length are limited too. */
    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long max;

        LimitedRequest(HttpServletRequest request, long max) {
            super(request);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream in = super.getInputStream();
            return new ServletInputStream() {
                long count;

                private void add(long n) {
                    if (n > 0 && (count += n) > max) throw new PayloadTooLargeException();
                }

                @Override
                public int read() throws IOException {
                    int b = in.read();
                    if (b >= 0) add(1);
                    return b;
                }

                @Override
                public int read(byte[] buf, int off, int len) throws IOException {
                    int n = in.read(buf, off, len);
                    add(n);
                    return n;
                }

                @Override
                public boolean isFinished() {
                    return in.isFinished();
                }

                @Override
                public boolean isReady() {
                    return in.isReady();
                }

                @Override
                public void setReadListener(ReadListener l) {
                    in.setReadListener(l);
                }
            };
        }
    }
}
