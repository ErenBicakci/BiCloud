package com.bic.cloud.bicloud_gateway.filter;

import com.bic.cloud.bicloud_gateway.client.ControlPlaneDiscoveryClient;
import com.bic.cloud.bicloud_gateway.dto.MeshEndpointDto;
import com.bic.cloud.bicloud_gateway.model.ServiceInstance;
import com.bic.cloud.bicloud_gateway.registry.RouteRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicRoutingFilterTest {

    private RouteRegistry registry;
    private ControlPlaneDiscoveryClient discoveryClient;
    private DynamicRoutingFilter filter;

    @BeforeEach
    void setUp() {
        registry = mock(RouteRegistry.class);
        discoveryClient = mock(ControlPlaneDiscoveryClient.class);
        filter = new DynamicRoutingFilter(registry, discoveryClient);
        ReflectionTestUtils.setField(filter, "remoteGatewayPort", 9000);
        ReflectionTestUtils.setField(filter, "gatewayApiKey", "gateway-secret");
    }

    @Test
    @DisplayName("local external delivery strips gateway-internal headers before tenant upstream")
    void localExternalDeliveryStripsInternalHeaders() {
        when(registry.resolve("alpha", "web")).thenReturn(Optional.of(instance("172.20.0.5", 8080)));
        when(registry.isExternallyExposed("alpha", "web")).thenReturn(true);

        ServerWebExchange exchange = exchange("/api/users?q=1", "web.alpha.bicloud.local:9000")
                .mutate()
                .request(r -> r.headers(h -> {
                    h.set(MeshRoutingFilter.HOPS_HEADER, "9");
                    h.set(MeshRoutingFilter.GATEWAY_KEY_HEADER, "client-supplied");
                    h.set(MeshRoutingFilter.CALLER_PROJECT_HEADER, "beta");
                }))
                .build();
        CapturingChain chain = new CapturingChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        URI target = chain.targetUri();
        assertThat(target.toString()).isEqualTo("http://172.20.0.5:8080/api/users?q=1");
        assertThat(chain.exchange().getRequest().getHeaders().containsKey(MeshRoutingFilter.HOPS_HEADER)).isFalse();
        assertThat(chain.exchange().getRequest().getHeaders().containsKey(MeshRoutingFilter.GATEWAY_KEY_HEADER)).isFalse();
        assertThat(chain.exchange().getRequest().getHeaders().containsKey(MeshRoutingFilter.CALLER_PROJECT_HEADER)).isFalse();
    }

    @Test
    @DisplayName("untrusted client hop header is ignored and overwritten during remote external forwarding")
    void untrustedClientHopHeaderIsIgnoredDuringRemoteForward() {
        when(registry.resolve("alpha", "web")).thenReturn(Optional.empty());
        MeshEndpointDto endpoint = new MeshEndpointDto();
        endpoint.setWorkerIp("10.0.0.8");
        endpoint.setExposeExternally(true);
        when(discoveryClient.discover("alpha", "web")).thenReturn(Mono.just(List.of(endpoint)));

        ServerWebExchange exchange = exchange("/api/users", "web.alpha.bicloud.local")
                .mutate()
                .request(r -> r.headers(h -> {
                    h.set(MeshRoutingFilter.HOPS_HEADER, "7");
                    h.set(MeshRoutingFilter.CALLER_PROJECT_HEADER, "beta");
                }))
                .build();
        CapturingChain chain = new CapturingChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.targetUri().toString()).isEqualTo("http://10.0.0.8:9000/api/users");
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(MeshRoutingFilter.HOPS_HEADER)).isEqualTo("1");
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(MeshRoutingFilter.GATEWAY_KEY_HEADER))
                .isEqualTo("gateway-secret");
        assertThat(chain.exchange().getRequest().getHeaders().containsKey(MeshRoutingFilter.CALLER_PROJECT_HEADER))
                .isFalse();
        assertThat(chain.exchange().getAttribute(ServerWebExchangeUtils.PRESERVE_HOST_HEADER_ATTRIBUTE))
                .isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("trusted remote external hop is not forwarded again when no local instance exists")
    void trustedRemoteExternalHopStopsAtSecondGateway() {
        when(registry.resolve("alpha", "web")).thenReturn(Optional.empty());

        ServerWebExchange exchange = exchange("/api/users", "web.alpha.bicloud.local")
                .mutate()
                .request(r -> r.headers(h -> {
                    h.set(MeshRoutingFilter.HOPS_HEADER, "1");
                    h.set(MeshRoutingFilter.GATEWAY_KEY_HEADER, "gateway-secret");
                }))
                .build();

        StepVerifier.create(filter.filter(exchange, new CapturingChain())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(discoveryClient, never()).discover("alpha", "web");
    }

    @Test
    @DisplayName("invalid host route names are rejected before registry lookup")
    void invalidHostRouteNamesAreRejected() {
        MockServerWebExchange exchange = exchange("/api/users", "web.egress-alpha.bicloud.local");

        StepVerifier.create(filter.filter(exchange, new CapturingChain())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(registry, never()).resolve("egress-alpha", "web");
    }

    @Test
    @DisplayName("single-character host route names are rejected before registry lookup")
    void singleCharacterHostRouteNamesAreRejected() {
        MockServerWebExchange exchange = exchange("/api/users", "w.a.bicloud.local");

        StepVerifier.create(filter.filter(exchange, new CapturingChain())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(registry, never()).resolve("a", "w");
    }

    private static ServiceInstance instance(String ip, int port) {
        return ServiceInstance.builder()
                .ip(ip)
                .port(port)
                .build();
    }

    private static MockServerWebExchange exchange(String path, String host) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path)
                .header(HttpHeaders.HOST, host)
                .build());
    }

    private static class CapturingChain implements GatewayFilterChain {
        private final AtomicReference<ServerWebExchange> exchange = new AtomicReference<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            this.exchange.set(exchange);
            return Mono.empty();
        }

        ServerWebExchange exchange() {
            return exchange.get();
        }

        URI targetUri() {
            return exchange().getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        }
    }
}
