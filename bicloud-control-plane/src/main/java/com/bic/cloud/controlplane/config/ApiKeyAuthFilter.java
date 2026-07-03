package com.bic.cloud.controlplane.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Slf4j
@Component
@Order(1)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    @Value("${bicloud.api-key}")
    private String apiKey;

    private static final String API_KEY_HEADER = "X-Api-Key";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        if (path.startsWith("/api/workers")) {
            String providedKey = request.getHeader(API_KEY_HEADER);

            // constant-time comparison - String.equals leaks the match length via timing
            if (providedKey == null || !MessageDigest.isEqual(
                    providedKey.getBytes(StandardCharsets.UTF_8),
                    apiKey.getBytes(StandardCharsets.UTF_8))) {
                log.warn("Unauthorized request to {} from IP: {}", path, request.getRemoteAddr());
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Invalid or missing API key\"}");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}
