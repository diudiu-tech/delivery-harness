package com.delivery.harness.gateway.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Anonymous failures use a bounded in-memory rejection budget, never durable audit rows. */
public final class AuthenticationThrottle extends OncePerRequestFilter {
    private static final long WINDOW_MILLIS = 60_000;
    private static final int MAX_KEYS = 1024;
    private final ConcurrentHashMap<String, Bucket> failures = new ConcurrentHashMap<>();
    private final AtomicLong requests = new AtomicLong();

    private record Bucket(long startedAt, int count) {}

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = System.currentTimeMillis();
        if ((requests.incrementAndGet() & 63) == 0) {
            failures.entrySet()
                    .removeIf(entry -> now - entry.getValue().startedAt() >= WINDOW_MILLIS);
        }
        String remote = request.getRemoteAddr();
        String key =
                failures.containsKey(remote) || failures.size() < MAX_KEYS ? remote : "overflow";
        Bucket previous = failures.get(key);
        if (previous != null
                && now - previous.startedAt() < WINDOW_MILLIS
                && previous.count() >= 20) {
            response.setHeader("Retry-After", "60");
            SecurityResponse.reject(
                    response,
                    429,
                    "AUTHENTICATION_RATE_LIMITED",
                    "Authentication retry budget exceeded");
            return;
        }
        chain.doFilter(request, response);
        if (response.getStatus() == 401) {
            failures.compute(
                    key,
                    (ignored, bucket) ->
                            bucket == null || now - bucket.startedAt() >= WINDOW_MILLIS
                                    ? new Bucket(now, 1)
                                    : new Bucket(bucket.startedAt(), bucket.count() + 1));
        }
    }
}
