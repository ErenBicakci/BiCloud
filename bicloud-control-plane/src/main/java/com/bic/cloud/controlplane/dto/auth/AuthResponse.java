package com.bic.cloud.controlplane.dto.auth;

public record AuthResponse(
        String token,
        String username,
        String role,
        long expiresInMs
) {}
