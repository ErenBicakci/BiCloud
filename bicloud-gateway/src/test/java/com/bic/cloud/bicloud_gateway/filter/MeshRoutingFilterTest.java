package com.bic.cloud.bicloud_gateway.filter;

import com.bic.cloud.bicloud_gateway.client.ControlPlaneDiscoveryClient;
import com.bic.cloud.bicloud_gateway.docker.GatewayNetworkManager;
import com.bic.cloud.bicloud_gateway.dto.MeshEndpointDto;
import com.bic.cloud.bicloud_gateway.model.ServiceInstance;
import com.bic.cloud.bicloud_gateway.registry.RouteRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeshRoutingFilterTest {

    private RouteRegistry registry;
    private ControlPlaneDiscoveryClient discoveryClient;
    private GatewayNetworkManager networkManager;
    private MeshRoutingFilter filter;

    @BeforeEach
    void setUp() {
        registry = mock(RouteRegistry.class);
        discoveryClient = mock(ControlPlaneDiscoveryClient.class);
        networkManager = mock(GatewayNetworkManager.class);
        filter = new MeshRoutingFilter(registry, discoveryClient, networkManager);
        ReflectionTestUtils.setField(filter, "remoteGatewayPort", 9000);
        ReflectionTestUtils.setField(filter, "gatewayApiKey", "gateway-secret");
    }

    @Test
    @DisplayName("tenant source cannot forge a gateway hop even with internal headers")
    void tenantSourceCannotForgeGatewayHop() {
        when(networkManager.projectForIp("172.20.0.7")).thenReturn(Optional.of("alpha"));

        ServerWebExchange exchange = exchange("/_bicloud/mesh/alpha/api/users", "172.20.0.7")
                .mutate()
                .request(r -> r.headers(h -> {
                    h.set(MeshRoutingFilter.HOPS_HEADER, "1");
                    h.set(MeshRoutingFilter.GATEWAY_KEY_HEADER, "gateway-secret");
                    h.set(MeshRoutingFilter.CALLER_PROJECT_HEADER, "alpha");
                }))
                .build();

        StepVerifier.create(filter.filter(exchange, new CapturingChain())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(registry, never()).resolve("alpha", "api");
    }

    @Test
    @DisplayName("first-hop tenant request cannot cross project boundaries")
    void firstHopTenantCannotCrossProjects() {
        when(networkManager.projectForIp("172.20.0.7")).thenReturn(Optional.of("alpha"));

        MockServerWebExchange exchange = exchange("/_bicloud/mesh/beta/api/users", "172.20.0.7");

        StepVerifier.create(filter.filter(exchange, new CapturingChain())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(registry, never()).resolve("beta", "api");
    }

    @Test
    @DisplayName("trusted remote gateway hop delivers locally and strips internal headers")
    void trustedRemoteGatewayHopDeliversLocallyAndStripsHeaders() {
        when(networkManager.projectForIp("10.0.0.9")).thenReturn(Optional.empty());
        when(registry.resolve("alpha", "api")).thenReturn(Optional.of(instance("172.20.0.8", 8080)));

        ServerWebExchange exchange = exchange("/_bicloud/mesh/alpha/api/users?id=42", "10.0.0.9")
                .mutate()
                .request(r -> r.headers(h -> {
                    h.set(MeshRoutingFilter.HOPS_HEADER, "1");
                    h.set(MeshRoutingFilter.GATEWAY_KEY_HEADER, "gateway-secret");
                    h.set(MeshRoutingFilter.CALLER_PROJECT_HEADER, "alpha");
                }))
                .build();
        CapturingChain chain = new CapturingChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.targetUri().toString()).isEqualTo("http://172.20.0.8:8080/users?id=42");
        assertThat(chain.exchange().getRequest().getHeaders().containsKey(MeshRoutingFilter.HOPS_HEADER)).isFalse();
        assertThat(chain.exchange().getRequest().getHeaders().containsKey(MeshRoutingFilter.GATEWAY_KEY_HEADER)).isFalse();
        assertThat(chain.exchange().getRequest().getHeaders().containsKey(MeshRoutingFilter.CALLER_PROJECT_HEADER)).isFalse();
    }

    @Test
    @DisplayName("first-hop remote forwarding stamps only gateway-owned mesh headers")
    void firstHopRemoteForwardingStampsGatewayHeaders() {
        when(networkManager.projectForIp("172.20.0.7")).thenReturn(Optional.of("alpha"));
        when(registry.resolve("alpha", "api")).thenReturn(Optional.empty());
        MeshEndpointDto endpoint = new MeshEndpointDto();
        endpoint.setWorkerIp("10.0.0.8");
        when(discoveryClient.discover("alpha", "api")).thenReturn(Mono.just(List.of(endpoint)));

        ServerWebExchange exchange = exchange("/_bicloud/mesh/alpha/api/users", "172.20.0.7")
                .mutate()
                .request(r -> r.headers(h -> {
                    h.set(MeshRoutingFilter.GATEWAY_KEY_HEADER, "tenant-supplied");
                    h.set(MeshRoutingFilter.CALLER_PROJECT_HEADER, "beta");
                }))
                .build();
        CapturingChain chain = new CapturingChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.targetUri().toString())
                .isEqualTo("http://10.0.0.8:9000/_bicloud/mesh/alpha/api/users");
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(MeshRoutingFilter.HOPS_HEADER)).isEqualTo("1");
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(MeshRoutingFilter.GATEWAY_KEY_HEADER))
                .isEqualTo("gateway-secret");
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(MeshRoutingFilter.CALLER_PROJECT_HEADER))
                .isEqualTo("alpha");
    }

    @Test
    @DisplayName("reserved project names are rejected in mesh paths")
    void reservedProjectNamesAreRejected() {
        MockServerWebExchange exchange = exchange("/_bicloud/mesh/egress-alpha/api/users", "172.20.0.7");

        StepVerifier.create(filter.filter(exchange, new CapturingChain())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(registry, never()).resolve("egress-alpha", "api");
    }

    @Test
    @DisplayName("single-character project and service names are rejected in mesh paths")
    void singleCharacterNamesAreRejected() {
        MockServerWebExchange exchange = exchange("/_bicloud/mesh/a/b/users", "172.20.0.7");

        StepVerifier.create(filter.filter(exchange, new CapturingChain())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(registry, never()).resolve("a", "b");
    }

    private static ServiceInstance instance(String ip, int port) {
        return ServiceInstance.builder()
                .ip(ip)
                .port(port)
                .build();
    }

    private static MockServerWebExchange exchange(String path, String remoteIp) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path)
                .remoteAddress(new InetSocketAddress(remoteIp, 45678))
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
