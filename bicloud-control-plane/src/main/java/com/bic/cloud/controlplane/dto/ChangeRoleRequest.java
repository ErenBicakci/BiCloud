package com.bic.cloud.controlplane.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ChangeRoleRequest(
        @NotBlank
        @Pattern(regexp = "^(ADMIN|USER)$", message = "role must be ADMIN or USER")
        String role
) {}
