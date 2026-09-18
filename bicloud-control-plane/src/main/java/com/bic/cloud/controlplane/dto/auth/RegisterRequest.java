package com.bic.cloud.controlplane.dto.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;

public record RegisterRequest(

        @NotBlank(message = "Username cannot be blank")
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
