package com.bic.cloud.controlplane.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Slf4j
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-Api-Key";
    private static final List<GrantedAuthority> WORKER_AUTHORITIES = List.of(new SimpleGrantedAuthority("ROLE_WORKER"));

    private final byte[] apiKey;

    public ApiKeyAuthFilter(String apiKey) {
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String providedKey = request.getHeader(API_KEY_HEADER);

        if (providedKey != null && MessageDigest.isEqual(providedKey.getBytes(StandardCharsets.UTF_8), apiKey)) {
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated("worker", null, WORKER_AUTHORITIES));
        } else {
            log.warn("Unauthorized request to {} from IP: {}", request.getRequestURI(), request.getRemoteAddr());
        }

        filterChain.doFilter(request, response);
    }
}
