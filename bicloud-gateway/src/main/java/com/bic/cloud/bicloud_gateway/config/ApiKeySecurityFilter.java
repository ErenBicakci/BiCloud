package com.bic.cloud.bicloud_gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;


@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class ApiKeySecurityFilter implements WebFilter {

    private static final String API_KEY_HEADER = "X-Api-Key";

    private static final List<PathPattern> PROTECTED = patterns("/gateway/**", "/actuator/**");
    private static final List<PathPattern> PUBLIC = patterns("/gateway/health", "/actuator/health/**", "/actuator/info");

    @Value("${bicloud.gateway.api-key}")
    private String validApiKey;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        PathContainer path = exchange.getRequest().getPath().pathWithinApplication();

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
                path.value(), remoteAddr);

        String json = """
                {"error":"UNAUTHORIZED","message":"A valid X-Api-Key header is required."}""";

        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buf = exchange.getResponse().bufferFactory()
                .wrap(json.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buf));
    }

    private static boolean requiresAuth(PathContainer path) {
        return PROTECTED.stream().anyMatch(p -> p.matches(path))
                && PUBLIC.stream().noneMatch(p -> p.matches(path));
    }

    private static List<PathPattern> patterns(String... patterns) {
        return Arrays.stream(patterns).map(PathPatternParser.defaultInstance::parse).toList();
    }
}
