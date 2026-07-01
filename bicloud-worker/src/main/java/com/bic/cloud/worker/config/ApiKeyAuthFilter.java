package com.bic.cloud.worker.config;

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

        if (path.startsWith("/api/containers")) {
            String providedKey = request.getHeader(API_KEY_HEADER);

            if (providedKey == null || !providedKey.equals(apiKey)) {
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
