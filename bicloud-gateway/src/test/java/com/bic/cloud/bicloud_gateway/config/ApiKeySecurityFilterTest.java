package com.bic.cloud.bicloud_gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeySecurityFilterTest {

    private ApiKeySecurityFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiKeySecurityFilter();
        ReflectionTestUtils.setField(filter, "validApiKey", "gateway-secret");
    }

    @Test
    @DisplayName("actuator health remains public")
    void actuatorHealthRemainsPublic() {
        MockServerWebExchange exchange = exchange("/actuator/health", null);
        CapturingWebFilterChain chain = new CapturingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.called()).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("actuator info remains public")
    void actuatorInfoRemainsPublic() {
        MockServerWebExchange exchange = exchange("/actuator/info", null);
        CapturingWebFilterChain chain = new CapturingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.called()).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("gateway health remains public")
    void gatewayHealthRemainsPublic() {
        MockServerWebExchange exchange = exchange("/gateway/health", null);
        CapturingWebFilterChain chain = new CapturingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.called()).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("gateway actuator endpoint requires API key")
    void gatewayActuatorEndpointRequiresApiKey() {
        MockServerWebExchange exchange = exchange("/actuator/gateway/routes", null);
        CapturingWebFilterChain chain = new CapturingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.called()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("gateway management endpoint requires API key")
    void gatewayManagementEndpointRequiresApiKey() {
        MockServerWebExchange exchange = exchange("/gateway/routes", null);
        CapturingWebFilterChain chain = new CapturingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.called()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("actuator root requires API key")
    void actuatorRootRequiresApiKey() {
        MockServerWebExchange exchange = exchange("/actuator", null);
        CapturingWebFilterChain chain = new CapturingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.called()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("gateway actuator endpoint allows valid API key")
    void gatewayActuatorEndpointAllowsValidApiKey() {
        MockServerWebExchange exchange = exchange("/actuator/gateway/routes", "gateway-secret");
        CapturingWebFilterChain chain = new CapturingWebFilterChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.called()).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    private static MockServerWebExchange exchange(String path, String apiKey) {
        var builder = MockServerHttpRequest.get(path);
        if (apiKey != null) {
            builder.header("X-Api-Key", apiKey);
        }
        return MockServerWebExchange.from(builder.build());
    }

    private static class CapturingWebFilterChain implements WebFilterChain {
        private final AtomicBoolean called = new AtomicBoolean();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            called.set(true);
            return Mono.empty();
        }

        boolean called() {
            return called.get();
        }
    }
}
