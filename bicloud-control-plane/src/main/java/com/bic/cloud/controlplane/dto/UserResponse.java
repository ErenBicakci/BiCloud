package com.bic.cloud.controlplane.dto;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String username,
        String role,
        Instant createdAt
) {}
