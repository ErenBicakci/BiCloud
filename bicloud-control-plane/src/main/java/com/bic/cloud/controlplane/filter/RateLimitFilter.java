package com.bic.cloud.controlplane.filter;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@Order(2)
public class RateLimitFilter extends OncePerRequestFilter {

    // gateway traffic - per IP
    @Value("${rate.limit.capacity:100}")
    private int gatewayCapacity;

    @Value("${rate.limit.refill-seconds:60}")
    private int gatewayRefillSeconds;

    // user APIs - per user
    @Value("${rate.limit.api.capacity:60}")
    private int apiCapacity;

    @Value("${rate.limit.api.refill-seconds:60}")
    private int apiRefillSeconds;

    /** Cap per map: past this the least-recently-used bucket is evicted. */
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final Map<String, Bucket> gatewayBuckets = createLruBucketMap();
    private final Map<String, Bucket> apiBuckets     = createLruBucketMap();

    /**
     * Bounded LRU: without eviction every distinct IP/user leaves a permanent
     * Bucket entry behind - a slow memory leak. An evicted key simply starts
     * over with a fresh (full) bucket, which is acceptable for rate limiting.
     */
    private static Map<String, Bucket> createLruBucketMap() {
        return Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                return size() > MAX_TRACKED_KEYS;
            }
        });
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        if (path.startsWith("/route/")) {
            applyLimit(gatewayBuckets, resolveIp(request), gatewayCapacity, gatewayRefillSeconds,
                    response, chain, request);
            return;
        }

        if (path.startsWith("/project") || path.startsWith("/containers")) {
            String userKey = resolveUser();
            if (userKey == null) {
                // without a JWT Spring Security will return 401, let it pass here
                chain.doFilter(request, response);
                return;
            }
            applyLimit(apiBuckets, userKey, apiCapacity, apiRefillSeconds,
                    response, chain, request);
            return;
        }

        chain.doFilter(request, response);
    }

    private void applyLimit(Map<String, Bucket> buckets, String key,
                            int capacity, int refillSeconds,
                            HttpServletResponse response, FilterChain chain,
                            HttpServletRequest request) throws IOException, ServletException {

        Bucket bucket = buckets.computeIfAbsent(key, k ->
                Bucket.builder()
                        .addLimit(Bandwidth.classic(capacity,
                                Refill.greedy(capacity, Duration.ofSeconds(refillSeconds))))
                        .build());

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            log.warn("Rate limit exceeded - key={}, path={}", key, request.getRequestURI());
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"Too many requests. Max "
                            + capacity + " requests per " + refillSeconds + " seconds.\"}"
            );
        }
    }

    private String resolveUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return "user:" + auth.getName();
        }
        return null;
    }

    private String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
