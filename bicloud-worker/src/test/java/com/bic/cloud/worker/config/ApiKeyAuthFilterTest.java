package com.bic.cloud.worker.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyAuthFilterTest {

    private final ApiKeyAuthFilter filter = new ApiKeyAuthFilter();

    ApiKeyAuthFilterTest() {
        ReflectionTestUtils.setField(filter, "apiKey", "worker-secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/containers/abc/logs", "/anything-else"})
    void everyEndpointRequiresTheKey(String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("GET", path), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/containers/abc/logs", "/anything-else"})
    void validKeyPasses(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("X-Api-Key", "worker-secret");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }
}
