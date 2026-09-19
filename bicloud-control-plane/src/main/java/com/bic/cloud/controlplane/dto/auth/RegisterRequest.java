package com.bic.cloud.controlplane.dto.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;

public record RegisterRequest(

        @NotBlank(message = "Username cannot be blank")
        @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
        @Pattern(regexp = "^[A-Za-z0-9._-]+$",
                message = "Username may only contain letters, digits, dots, underscores and hyphens")
        String username,

        @NotBlank(message = "Password cannot be blank")
        @Size(min = 8, message = "Password must be at least 8 characters")
        String password
) {

    public static final int MAX_PASSWORD_BYTES = 72;

    public static boolean exceedsPasswordLimit(String password) {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES;
    }

    @AssertTrue(message = "Password must be at most 72 bytes.")
    public boolean isPasswordWithinLimit() {
        return !exceedsPasswordLimit(password);
    }
}
