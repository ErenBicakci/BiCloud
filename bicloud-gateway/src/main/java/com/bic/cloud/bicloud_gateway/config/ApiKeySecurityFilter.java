package com.bic.cloud.bicloud_gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;


@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class ApiKeySecurityFilter implements WebFilter {

    private static final String API_KEY_HEADER = "X-Api-Key";

    @Value("${bicloud.gateway.api-key}")
    private String validApiKey;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        if (!requiresAuth(path)) {
            return chain.filter(exchange);
        }

        String providedKey = exchange.getRequest().getHeaders().getFirst(API_KEY_HEADER);

        // constant-time comparison - String.equals leaks the match length via timing
        if (providedKey != null && MessageDigest.isEqual(
                providedKey.getBytes(StandardCharsets.UTF_8),
                validApiKey.getBytes(StandardCharsets.UTF_8))) {
            return chain.filter(exchange);
        }

        String remoteAddr = exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().toString()
                : "unknown";

        log.warn("Unauthorized gateway access denied | path={} | remote={}",
                path, remoteAddr);

        String json = """
                {"error":"UNAUTHORIZED","message":"A valid X-Api-Key header is required."}""";

        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buf = exchange.getResponse().bufferFactory()
                .wrap(json.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buf));
    }

    private boolean requiresAuth(String path) {
        if (path.equals("/gateway/health")) return false;
        if (path.equals("/actuator") || path.equals("/actuator/")) return true;
        if (path.equals("/actuator/health") || path.startsWith("/actuator/health/")) return false;
        if (path.equals("/actuator/info")) return false;
        if (path.startsWith("/actuator/")) return true;
        return path.equals("/gateway") || path.startsWith("/gateway/");
    }
}
