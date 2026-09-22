package com.bic.cloud.controlplane.filter;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
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

    private static final int MAX_TRACKED_KEYS = 10_000;

    private final Limit authLimit;
    private final Limit readLimit;
    private final Limit writeLimit;

    public RateLimitFilter(@Value("${rate.limit.auth.capacity:10}") int authCapacity,
                           @Value("${rate.limit.api.read-capacity:300}") int readCapacity,
                           @Value("${rate.limit.api.capacity:60}") int writeCapacity,
                           @Value("${rate.limit.refill-seconds:60}") int refillSeconds) {
        Duration refill = Duration.ofSeconds(refillSeconds);
        this.authLimit = new Limit(authCapacity, refill);
        this.readLimit = new Limit(readCapacity, refill);
        this.writeLimit = new Limit(writeCapacity, refill);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String path = request.getServletPath();
        boolean isRead = "GET".equals(request.getMethod());

        Limit limit = null;
        String key = null;

        if (!isRead && (path.equals("/auth/login") || path.equals("/auth/register"))) {
            limit = authLimit;
            key = "ip:" + request.getRemoteAddr();
        } else if (path.startsWith("/project") || path.startsWith("/containers")) {
            limit = isRead ? readLimit : writeLimit;
            key = currentUser();
        }

        if (key == null || limit.tryConsume(key)) {
            chain.doFilter(request, response);
            return;
        }

        log.warn("Rate limit exceeded - key={}, path={}", key, path);
        response.setStatus(429);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"Too many requests. Max "
                + limit.capacity() + " requests per " + limit.refill().toSeconds() + " seconds.\"}");
    }

    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return "user:" + auth.getName();
        }
        return null;
    }

    private record Limit(int capacity, Duration refill, Map<String, Bucket> buckets) {

        Limit(int capacity, Duration refill) {
            this(capacity, refill, Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                    return size() > MAX_TRACKED_KEYS;
                }
            }));
        }

        boolean tryConsume(String key) {
            return buckets.computeIfAbsent(key, k -> Bucket.builder()
                    .addLimit(Bandwidth.builder().capacity(capacity).refillGreedy(capacity, refill).build())
                    .build()).tryConsume(1);
        }
    }
}
