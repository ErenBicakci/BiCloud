package com.bic.cloud.bicloud_gateway.filter;

import com.bic.cloud.bicloud_gateway.client.ControlPlaneDiscoveryClient;
import com.bic.cloud.bicloud_gateway.docker.GatewayNetworkManager;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
@RequiredArgsConstructor
public class MeshRoutingFilter implements GlobalFilter, Ordered {

    public static final String MESH_PREFIX = "/_bicloud/mesh/";
    public static final String HOPS_HEADER = "X-BiCloud-Mesh-Hops";
    public static final String GATEWAY_KEY_HEADER = "X-BiCloud-Gateway-Key";
    public static final String CALLER_PROJECT_HEADER = "X-BiCloud-Caller-Project";

    private static final int MAX_HOPS = 3;
    private static final int ORDER = 10001;

    private final RouteRegistry registry;
    private final ControlPlaneDiscoveryClient discoveryClient;
    private final GatewayNetworkManager networkManager;

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
        String rawPath = exchange.getRequest().getURI().getRawPath();
        if (!rawPath.startsWith(MESH_PREFIX)) {
            return chain.filter(exchange);
        }

        MeshTarget target = parseMeshPath(rawPath);
        if (target == null) {
            return writeError(exchange, HttpStatus.BAD_REQUEST, "INVALID_MESH_PATH",
                    "Expected format: /_bicloud/mesh/{project}/{service}/...");
        }

        String hopsHeader = exchange.getRequest().getHeaders().getFirst(HOPS_HEADER);
        boolean gatewayHop = hopsHeader != null;
        int hops = gatewayHop ? parseHops(hopsHeader) : 0;

        if (gatewayHop && !isTrustedGatewayHop(exchange, hops)) {
            log.warn("[Mesh] Forged or invalid gateway hop rejected: {}/{}",
                    target.project(), target.service());
            return writeError(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Gateway identity could not be verified.");
        }
        if (hops >= MAX_HOPS) {
            log.error("[Mesh] Hop limit ({}) exceeded: {}/{}", MAX_HOPS, target.project(), target.service());
            return writeError(exchange, HttpStatus.LOOP_DETECTED, "MESH_HOP_LIMIT",
                    "Mesh routing hop limit exceeded.");
        }

        if (gatewayHop) {
            String callerProject = exchange.getRequest().getHeaders().getFirst(CALLER_PROJECT_HEADER);
            if (callerProject == null || !callerProject.equalsIgnoreCase(target.project())) {
                log.warn("[Mesh] Tenant isolation (hop): caller project={} -> {}/{} rejected",
                        callerProject, target.project(), target.service());
                return writeError(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "Over the mesh you can only reach services in your own project.");
            }
        } else {
            String callerIp = remoteIp(exchange);
            Optional<String> callerProject = callerIp == null
                    ? Optional.empty() : networkManager.projectForIp(callerIp);

            if (callerProject.isEmpty() || !callerProject.get().equalsIgnoreCase(target.project())) {
                log.warn("[Mesh] Tenant isolation: {} (project={}) -> {}/{} access rejected",
                        callerIp, callerProject.orElse("?"), target.project(), target.service());
                return writeError(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "Over the mesh you can only reach services in your own project.");
            }
        }

        Optional<ServiceInstance> local = registry.resolve(target.project(), target.service());
        if (local.isPresent()) {
            URI targetUri = buildLocalUri(local.get(), target, exchange);
            log.debug("[Mesh] local {} {} -> {}", exchange.getRequest().getMethod(), rawPath, targetUri);
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, targetUri);
            return chain.filter(stripInternalHeaders(exchange));
        }

        if (gatewayHop) {
            log.warn("[Mesh] No local instance for a remote request: {}/{}",
                    target.project(), target.service());
            return writeError(exchange, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "Service '%s' no longer runs on this node.".formatted(target.service()));
        }

        return discoveryClient.discover(target.project(), target.service())
                .flatMap(endpoints -> forwardToRemoteGateway(exchange, chain, target, endpoints, hops));
    }

    private Mono<Void> forwardToRemoteGateway(ServerWebExchange exchange,
                                              GatewayFilterChain chain,
                                              MeshTarget target,
                                              List<MeshEndpointDto> endpoints,
                                              int hops) {
        List<MeshEndpointDto> usable = endpoints.stream()
                .filter(e -> e.getWorkerIp() != null && !e.getWorkerIp().isBlank())
                .toList();

        if (usable.isEmpty()) {
            log.warn("[Mesh] No endpoint found: {}/{}", target.project(), target.service());
            return writeError(exchange, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "No running instance found for service '%s'.".formatted(target.service()));
        }

        MeshEndpointDto chosen = usable.size() == 1
                ? usable.get(0)
                : usable.get(ThreadLocalRandom.current().nextInt(usable.size()));

        String query = exchange.getRequest().getURI().getRawQuery();
        URI targetUri = URI.create("http://" + chosen.getWorkerIp() + ":" + remoteGatewayPort
                + exchange.getRequest().getURI().getRawPath()
                + (query != null ? "?" + query : ""));

        log.info("[Mesh] remote {} {} -> {} (hops={})",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getURI().getRawPath(), targetUri, hops);

        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.headers(h -> {
                    h.set(HOPS_HEADER, String.valueOf(hops + 1));
                    h.set(GATEWAY_KEY_HEADER, gatewayApiKey);
                    h.set(CALLER_PROJECT_HEADER, target.project());
                }))
                .build();
        mutated.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, targetUri);

        return chain.filter(mutated);
    }

    private ServerWebExchange stripInternalHeaders(ServerWebExchange exchange) {
        return exchange.mutate()
                .request(r -> r.headers(h -> {
                    h.remove(GATEWAY_KEY_HEADER);
                    h.remove(CALLER_PROJECT_HEADER);
                    h.remove(HOPS_HEADER);
                }))
                .build();
    }

    private URI buildLocalUri(ServiceInstance instance, MeshTarget target, ServerWebExchange exchange) {
        String subPath = target.subPath().isEmpty() ? "/" : target.subPath();
        String query = exchange.getRequest().getURI().getRawQuery();
        return URI.create(instance.toUri() + subPath + (query != null ? "?" + query : ""));
    }

    private MeshTarget parseMeshPath(String rawPath) {
        String rest = rawPath.substring(MESH_PREFIX.length());
        int firstSlash = rest.indexOf('/');
        if (firstSlash <= 0) return null;

        String project = rest.substring(0, firstSlash);
        String afterProject = rest.substring(firstSlash + 1);

        int secondSlash = afterProject.indexOf('/');
        String service = secondSlash < 0 ? afterProject : afterProject.substring(0, secondSlash);
        if (service.isEmpty()) return null;

        String subPath = secondSlash < 0 ? "" : afterProject.substring(secondSlash);
        if (!RouteNameRules.isProjectName(project) || !RouteNameRules.isServiceName(service)) {
            return null;
        }

        return new MeshTarget(project, service, subPath);
    }

    private String remoteIp(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) return null;
        return remote.getAddress().getHostAddress();
    }

    private int parseHops(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private boolean isTrustedGatewayHop(ServerWebExchange exchange, int hops) {
        if (hops <= 0) return false;

        String key = exchange.getRequest().getHeaders().getFirst(GATEWAY_KEY_HEADER);
        if (key == null || !MessageDigest.isEqual(
                key.getBytes(StandardCharsets.UTF_8),
                gatewayApiKey.getBytes(StandardCharsets.UTF_8))) {
            return false;
        }

        String callerIp = remoteIp(exchange);
        if (callerIp == null) return false;

        // Tenant containers live inside bicloud-{project} subnets. Even if an
        // app forwards internal headers, that request must remain a first-hop
        // tenant request and cannot become a trusted gateway-to-gateway hop.
        return networkManager.projectForIp(callerIp).isEmpty();
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

    private record MeshTarget(String project, String service, String subPath) {}
}
