package com.bic.cloud.controlplane.security;

import com.bic.cloud.controlplane.model.BicloudUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    @Test
    @DisplayName("a secret shorter than 32 bytes fails at startup instead of at the first login")
    void rejectsShortSecret() {
        assertThatThrownBy(() -> jwtUtil("CHANGE_ME_MIN_32_CHARS"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a token stays bound to the user id it was issued for")
    void tokenIsBoundToUserId() {
        JwtUtil jwtUtil = jwtUtil("test-jwt-secret-with-at-least-thirty-two-chars");
        BicloudUser original = user();
        BicloudUser sameNameLater = user();

        String token = jwtUtil.generateToken("alice", "USER", original.getId());

        assertThat(jwtUtil.isTokenValid(token, new BicloudUserDetails(original))).isTrue();
        assertThat(jwtUtil.isTokenValid(token, new BicloudUserDetails(sameNameLater))).isFalse();
    }

    private static JwtUtil jwtUtil(String secret) {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", secret);
        ReflectionTestUtils.setField(jwtUtil, "expiration", 60_000L);
        jwtUtil.initSigningKey();
        return jwtUtil;
    }

    private static BicloudUser user() {
        return BicloudUser.builder().id(UUID.randomUUID()).username("alice").password("x").role("USER").build();
    }
}
