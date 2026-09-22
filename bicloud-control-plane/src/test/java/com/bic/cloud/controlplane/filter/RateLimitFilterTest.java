package com.bic.cloud.controlplane.filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private final RateLimitFilter filter = new RateLimitFilter(2, 3, 1, 60);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("login attempts are limited per client address")
    void loginAttemptsAreLimited() throws Exception {
        assertThat(status("POST", "/auth/login")).isEqualTo(200);
        assertThat(status("POST", "/auth/login")).isEqualTo(200);
        assertThat(status("POST", "/auth/login")).isEqualTo(429);
    }

    @Test
    @DisplayName("reads and writes use separate budgets, so polling cannot block user actions")
    void readsAndWritesHaveSeparateBudgets() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("alice", null, "ROLE_USER"));

        for (int i = 0; i < 3; i++) {
            assertThat(status("GET", "/project/1")).isEqualTo(200);
        }
        assertThat(status("GET", "/project/1")).isEqualTo(429);

        assertThat(status("POST", "/project/1/deploy")).isEqualTo(200);
        assertThat(status("POST", "/project/1/deploy")).isEqualTo(429);
    }

    private int status(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
