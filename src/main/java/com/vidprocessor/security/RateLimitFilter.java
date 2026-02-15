package com.vidprocessor.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple sliding-window rate limiter per client IP.
 * Rejects requests with HTTP 429 when the limit is exceeded.
 */
@Component
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    @Value("${rate.limit.requests-per-minute:30}")
    private int maxRequestsPerMinute;

    private final ConcurrentMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Skip rate limiting for health checks
        String path = request.getRequestURI();
        if (path.equals("/api/health") || path.equals("/api/ping")) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);
        WindowCounter counter = counters.computeIfAbsent(clientIp, k -> new WindowCounter());

        if (!counter.tryAcquire(maxRequestsPerMinute)) {
            log.warn("Rate limit exceeded for IP: {}", clientIp);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"Rate limit exceeded\",\"message\":\"Too many requests. Please try again later.\"}"
            );
            return;
        }

        response.setHeader("X-RateLimit-Limit", String.valueOf(maxRequestsPerMinute));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, maxRequestsPerMinute - counter.getCount())));

        filterChain.doFilter(request, response);
    }

    private String resolveClientIp(HttpServletRequest request) {
        // Check common proxy headers
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Simple 1-minute sliding window counter.
     * Resets automatically when the current minute changes.
     */
    private static class WindowCounter {
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile long windowStart = System.currentTimeMillis();

        boolean tryAcquire(int limit) {
            long now = System.currentTimeMillis();
            if (now - windowStart > 60_000) {
                // Reset window
                synchronized (this) {
                    if (now - windowStart > 60_000) {
                        count.set(0);
                        windowStart = now;
                    }
                }
            }
            return count.incrementAndGet() <= limit;
        }

        int getCount() {
            return count.get();
        }
    }
}
