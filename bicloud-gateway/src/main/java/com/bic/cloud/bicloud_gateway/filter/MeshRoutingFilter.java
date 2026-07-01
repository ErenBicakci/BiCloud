package com.bic.cloud.bicloud_gateway.filter;

import com.bic.cloud.bicloud_gateway.client.ControlPlaneDiscoveryClient;
import com.bic.cloud.bicloud_gateway.docker.GatewayNetworkManager;
import com.bic.cloud.bicloud_gateway.dto.MeshEndpointDto;
import com.bic.cloud.bicloud_gateway.model.ServiceInstance;
import com.bic.cloud.bicloud_gateway.registry.RouteRegistry;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * East-west (service -> service) mesh routing. Containers call other services at
 * http://bicloud-gateway:9000/_bicloud/mesh/{project}/{service}/...
 * (a Docker DNS alias, identical on every machine).
 *
 * If the target service is in this gateway's registry the request goes straight
 * to the container; otherwise the CP is asked for an endpoint and the request is
 * forwarded to the target machine's gateway, which delivers it locally. A request
 * that arrived remotely (hops > 0) is not forwarded to another machine again, so
 * no loop can form.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MeshRoutingFilter implements GlobalFilter, Ordered {

    public static final String MESH_PREFIX = "/_bicloud/mesh/";

    /** Hop counter between gateways - shared with DynamicRoutingFilter (north-south). */
    public static final String HOPS_HEADER = "X-BiCloud-Mesh-Hops";

    /** Gateway-to-gateway identity: proves that a hops > 0 request really came from
     *  a gateway - a tenant container cannot forge the hops header and skip the
     *  isolation check. */
    public static final String GATEWAY_KEY_HEADER = "X-BiCloud-Gateway-Key";

    /** Caller project the first gateway VERIFIED from the subnet - stamped on the
     *  hop request so the receiving gateway can compare it against the target project. */
    public static final String CALLER_PROJECT_HEADER = "X-BiCloud-Caller-Project";

    private static final int MAX_HOPS = 3;

    /** Right after RouteToRequestUrlFilter (10000), same order as DynamicRoutingFilter. */
    private static final int ORDER = 10001;

    private final RouteRegistry registry;
    private final ControlPlaneDiscoveryClient discoveryClient;
    private final GatewayNetworkManager networkManager;

    /** Host port of the gateways on other machines (must be the same everywhere). */
    @Value("${bicloud.gateway.port:9000}")
    private int remoteGatewayPort;

    /** Shared key, identical on all gateways (provided via compose env). */
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
            return chain.filter(exchange); // north-south -> handled by DynamicRoutingFilter
        }

        MeshTarget target = parseMeshPath(rawPath);
        if (target == null) {
            return writeError(exchange, HttpStatus.BAD_REQUEST, "INVALID_MESH_PATH",
                    "Expected format: /_bicloud/mesh/{project}/{service}/...");
        }

        int hops = parseHops(exchange);
        if (hops >= MAX_HOPS) {
            log.error("[Mesh] Hop limit ({}) exceeded: {}/{}", MAX_HOPS, target.project(), target.service());
            return writeError(exchange, HttpStatus.LOOP_DETECTED, "MESH_HOP_LIMIT",
                    "Mesh routing hop limit exceeded.");
        }

        // ── Tenant isolation ───────────────────────────────────────────────
        if (hops > 0) {
            // hop from another gateway: first prove it really came from a gateway
            // (so a tenant container cannot forge the hops header).
            String key = exchange.getRequest().getHeaders().getFirst(GATEWAY_KEY_HEADER);
            if (!gatewayApiKey.equals(key)) {
                log.warn("[Mesh] Hop request with invalid gateway key rejected: {}/{}",
                        target.project(), target.service());
                return writeError(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "Gateway identity could not be verified.");
            }

            // the caller project verified by the first gateway must equal the target project.
            String callerProject = exchange.getRequest().getHeaders().getFirst(CALLER_PROJECT_HEADER);
            if (callerProject == null || !callerProject.equalsIgnoreCase(target.project())) {
                log.warn("[Mesh] Tenant isolation (hop): caller project={} -> {}/{} rejected",
                        callerProject, target.project(), target.service());
                return writeError(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "Over the mesh you can only reach services in your own project.");
            }
        } else {
            // first request from a container: derive the caller's project from the
            // source IP's subnet - it may only reach services in its OWN project.
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

        // 1) local delivery: is the service on this machine? (RouteRegistry only holds local instances)
        Optional<ServiceInstance> local = registry.resolve(target.project(), target.service());
        if (local.isPresent()) {
            URI targetUri = buildLocalUri(local.get(), target, exchange);
            log.debug("[Mesh] local {} {} -> {}", exchange.getRequest().getMethod(), rawPath, targetUri);
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, targetUri);
            return chain.filter(exchange);
        }

        // 2) arrived remotely but there is no local instance -> do NOT forward again (loop risk).
        //    the instance died between the CP's response and the request arriving.
        if (hops > 0) {
            log.warn("[Mesh] No local instance for a remote request: {}/{}",
                    target.project(), target.service());
            return writeError(exchange, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "Service '%s' no longer runs on this node.".formatted(target.service()));
        }

        // 3) remote delivery: get an endpoint from the CP, forward to the target machine's gateway
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
                    h.set(GATEWAY_KEY_HEADER, gatewayApiKey); // identity proof to the receiving gateway
                    // it passed the isolation check, so caller project == target project;
                    // the receiving gateway compares it against the target project again.
                    h.set(CALLER_PROJECT_HEADER, target.project());
                }))
                .build();
        mutated.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, targetUri);

        return chain.filter(mutated);
    }

    /** For a local instance: strip the mesh prefix, the remaining sub-path goes to the container. */
    private URI buildLocalUri(ServiceInstance instance, MeshTarget target, ServerWebExchange exchange) {
        String subPath = target.subPath().isEmpty() ? "/" : target.subPath();
        String query = exchange.getRequest().getURI().getRawQuery();
        return URI.create(instance.toUri() + subPath + (query != null ? "?" + query : ""));
    }

    /** "/_bicloud/mesh/proje/servis/alt/yol" -> (proje, servis, "/alt/yol") */
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
        return new MeshTarget(project, service, subPath);
    }

    /** Source IPv4 of the TCP connection (not a header - cannot be forged). */
    private String remoteIp(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) return null;
        return remote.getAddress().getHostAddress();
    }

    private int parseHops(ServerWebExchange exchange) {
        String value = exchange.getRequest().getHeaders().getFirst(HOPS_HEADER);
        if (value == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return 0;
        }
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
