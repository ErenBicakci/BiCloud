package com.bic.cloud.bicloud_gateway.filter;

import com.bic.cloud.bicloud_gateway.client.ControlPlaneDiscoveryClient;
import com.bic.cloud.bicloud_gateway.dto.MeshEndpointDto;
import com.bic.cloud.bicloud_gateway.model.ServiceInstance;
import com.bic.cloud.bicloud_gateway.registry.RouteRegistry;
import com.bic.cloud.bicloud_gateway.routing.RouteNameRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;


@Component
@RequiredArgsConstructor
@Slf4j
public class DynamicRoutingFilter implements GlobalFilter, Ordered {

    private static final String HOST_SUFFIX = ".bicloud.local";
    private static final int ORDER = 10001;

    private final RouteRegistry registry;
    private final ControlPlaneDiscoveryClient discoveryClient;

    @Value("${bicloud.gateway.port:9000}")
    private int remoteGatewayPort;

    @Value("${bicloud.gateway.api-key}")
    private String gatewayApiKey;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // east-west mesh traffic is MeshRoutingFilter's job
        if (exchange.getRequest().getURI().getRawPath().startsWith(MeshRoutingFilter.MESH_PREFIX)) {
            return chain.filter(exchange);
        }

        String rawHost = exchange.getRequest().getHeaders().getFirst(HttpHeaders.HOST);
        ParsedHost host = parseHost(rawHost);

        if (host == null) {
            log.warn("Invalid/missing Host header -> 404 | raw='{}'", rawHost);
            return writeError(exchange, HttpStatus.NOT_FOUND,
                    "ROUTE_NOT_FOUND",
                    "Host header is not in the expected format. Expected: {service}.{project}.bicloud.local");
        }

        Optional<ServiceInstance> resolved = registry.resolve(host.project(), host.service());

        if (resolved.isEmpty()) {
            return forwardToRemoteGateway(exchange, chain, host);
        }

        if (!registry.isExternallyExposed(host.project(), host.service())) {
            log.warn("External route blocked by service policy -> 403 | route=[{}:{}]",
                    host.project(), host.service());
            return writeServiceNotExposed(exchange, host);
        }

        ServiceInstance instance = resolved.get();
        URI targetUri = buildTargetUri(instance, exchange.getRequest().getURI());

        log.debug("[{}:{}] {} {} -> {}",
                host.project(), host.service(),
                exchange.getRequest().getMethod(),
                exchange.getRequest().getURI().getRawPath(),
                targetUri);

        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, targetUri);

        return chain.filter(stripInternalHeaders(exchange));
    }

    private Mono<Void> forwardToRemoteGateway(ServerWebExchange exchange,
                                              GatewayFilterChain chain,
                                              ParsedHost host) {
        String hopsValue = exchange.getRequest().getHeaders().getFirst(MeshRoutingFilter.HOPS_HEADER);
        if (hopsValue != null && isTrustedGatewayHop(exchange)) {
            log.warn("No local instance for a remote request -> 503 | route=[{}:{}]",
                    host.project(), host.service());
            return writeError(exchange, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "No active instance found for service '%s' in project '%s'."
                            .formatted(host.service(), host.project()));
        }

        return discoveryClient.discover(host.project(), host.service())
                .flatMap(endpoints -> {
                    List<MeshEndpointDto> usable = endpoints.stream()
                            .filter(MeshEndpointDto::isExposeExternally)
                            .filter(e -> e.getWorkerIp() != null && !e.getWorkerIp().isBlank())
                            .toList();

                    if (!endpoints.isEmpty() && usable.isEmpty()) {
                        log.warn("External route blocked by remote service policy -> 403 | route=[{}:{}]",
                                host.project(), host.service());
                        return writeServiceNotExposed(exchange, host);
                    }

                    if (usable.isEmpty()) {
                        log.warn("No instance found (CP included) -> 503 | route=[{}:{}]",
                                host.project(), host.service());
                        return writeError(exchange, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                                "No active instance found for service '%s' in project '%s'."
                                        .formatted(host.service(), host.project()));
                    }

                    MeshEndpointDto chosen = usable.size() == 1
                            ? usable.get(0)
                            : usable.get(ThreadLocalRandom.current().nextInt(usable.size()));

                    String query = exchange.getRequest().getURI().getRawQuery();
                    URI targetUri = URI.create("http://" + chosen.getWorkerIp() + ":" + remoteGatewayPort
                            + exchange.getRequest().getURI().getRawPath()
                            + (query != null ? "?" + query : ""));

                    log.info("[NS-remote] {} {} -> {} | route=[{}:{}]",
                            exchange.getRequest().getMethod(),
                            exchange.getRequest().getURI().getRawPath(), targetUri,
                            host.project(), host.service());

                    ServerWebExchange mutated = exchange.mutate()
                            .request(r -> r.headers(h -> {
                                h.remove(MeshRoutingFilter.HOPS_HEADER);
                                h.remove(MeshRoutingFilter.GATEWAY_KEY_HEADER);
                                h.remove(MeshRoutingFilter.CALLER_PROJECT_HEADER);
                                h.set(MeshRoutingFilter.HOPS_HEADER, "1");
                                h.set(MeshRoutingFilter.GATEWAY_KEY_HEADER, gatewayApiKey);
                            }))
                            .build();
                    mutated.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, targetUri);
                    mutated.getAttributes().put(ServerWebExchangeUtils.PRESERVE_HOST_HEADER_ATTRIBUTE, true);

                    return chain.filter(mutated);
                });
    }

    private URI buildTargetUri(ServiceInstance instance, URI original) {
        String path  = original.getRawPath();
        String query = original.getRawQuery();
        String full  = instance.toUri()
                + (path != null && !path.isEmpty() ? path : "/")
                + (query != null ? "?" + query : "");
        return URI.create(full);
    }

    private ParsedHost parseHost(String rawHost) {
        if (rawHost == null || rawHost.isBlank()) return null;

        String h = rawHost.contains(":") ? rawHost.substring(0, rawHost.indexOf(':')) : rawHost;
        h = h.toLowerCase(Locale.ROOT);

        if (!h.endsWith(HOST_SUFFIX)) return null;

        String stripped = h.substring(0, h.length() - HOST_SUFFIX.length());

        int dot = stripped.indexOf('.');
        if (dot <= 0 || dot == stripped.length() - 1) return null;

        String service = stripped.substring(0, dot);
        String project = stripped.substring(dot + 1);

        if (!RouteNameRules.isProjectName(project) || !RouteNameRules.isServiceName(service)) {
            return null;
        }

        return new ParsedHost(project, service);
    }

    private boolean isTrustedGatewayHop(ServerWebExchange exchange) {
        String hops = exchange.getRequest().getHeaders().getFirst(MeshRoutingFilter.HOPS_HEADER);
        if (parseHops(hops) <= 0) return false;

        String key = exchange.getRequest().getHeaders().getFirst(MeshRoutingFilter.GATEWAY_KEY_HEADER);
        return key != null && MessageDigest.isEqual(
                key.getBytes(StandardCharsets.UTF_8),
                gatewayApiKey.getBytes(StandardCharsets.UTF_8));
    }

    private int parseHops(String value) {
        try {
            return Integer.parseInt(value);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private ServerWebExchange stripInternalHeaders(ServerWebExchange exchange) {
        return exchange.mutate()
                .request(r -> r.headers(h -> {
                    h.remove(MeshRoutingFilter.GATEWAY_KEY_HEADER);
                    h.remove(MeshRoutingFilter.CALLER_PROJECT_HEADER);
                    h.remove(MeshRoutingFilter.HOPS_HEADER);
                }))
                .build();
    }

    private Mono<Void> writeError(ServerWebExchange exchange,
                                   HttpStatus status, String error, String message) {
        String json = """
                {"error":"%s","status":%d,"message":"%s"}"""
                .formatted(error, status.value(), message);

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buf = exchange.getResponse().bufferFactory()
                .wrap(json.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buf));
    }

    private Mono<Void> writeServiceNotExposed(ServerWebExchange exchange, ParsedHost host) {
        return writeError(exchange, HttpStatus.FORBIDDEN, "SERVICE_NOT_EXPOSED",
                "Service '%s' in project '%s' is not exposed through the external gateway."
                        .formatted(host.service(), host.project()));
    }


    private record ParsedHost(String project, String service) {}
}
