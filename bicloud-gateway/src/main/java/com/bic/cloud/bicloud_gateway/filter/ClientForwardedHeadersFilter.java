package com.bic.cloud.bicloud_gateway.filter;

import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tells upstream apps who the real client is (X-Forwarded-For/Host/Proto/Port).
 *
 * Incoming X-Forwarded-* values are only kept when the request is a verified
 * gateway-to-gateway hop (the routing filters set {@link #TRUSTED_HOP_ATTR});
 * anything a client sends itself is discarded and rewritten, so apps can rely
 * on the first X-Forwarded-For entry being the real client address.
 *
 * Runs after Spring Cloud Gateway's RemoveXForwardedHeadersFilter (order 0),
 * which strips these headers because no trusted-proxies regex is configured.
 */
@Component
public class ClientForwardedHeadersFilter implements HttpHeadersFilter, Ordered {

    public static final String TRUSTED_HOP_ATTR = ClientForwardedHeadersFilter.class.getName() + ".trustedHop";

    static final String X_FORWARDED_FOR   = "X-Forwarded-For";
    static final String X_FORWARDED_HOST  = "X-Forwarded-Host";
    static final String X_FORWARDED_PROTO = "X-Forwarded-Proto";
    static final String X_FORWARDED_PORT  = "X-Forwarded-Port";

    private static final String X_FORWARDED_PREFIX = "x-forwarded-";

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public HttpHeaders filter(HttpHeaders input, ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();

        HttpHeaders out = new HttpHeaders();
        for (Map.Entry<String, List<String>> entry : input.headerSet()) {
            if (!entry.getKey().toLowerCase(Locale.ROOT).startsWith(X_FORWARDED_PREFIX)) {
                out.addAll(entry.getKey(), entry.getValue());
            }
        }

        String remoteIp = remoteIp(request);
        boolean trustedHop = Boolean.TRUE.equals(exchange.getAttribute(TRUSTED_HOP_ATTR));
        // read from the request, not from 'input': earlier filters already removed them
        HttpHeaders incoming = request.getHeaders();

        String forwardedFor = trustedHop ? join(incoming.get(X_FORWARDED_FOR)) : null;
        if (remoteIp != null) {
            forwardedFor = forwardedFor == null ? remoteIp : forwardedFor + ", " + remoteIp;
        }
        if (forwardedFor != null) {
            out.set(X_FORWARDED_FOR, forwardedFor);
        }

        setFirst(out, X_FORWARDED_HOST,  trustedHop ? incoming.getFirst(X_FORWARDED_HOST)  : null, hostHeader(request));
        setFirst(out, X_FORWARDED_PROTO, trustedHop ? incoming.getFirst(X_FORWARDED_PROTO) : null, scheme(request));
        setFirst(out, X_FORWARDED_PORT,  trustedHop ? incoming.getFirst(X_FORWARDED_PORT)  : null, port(request));

        return out;
    }

    private static void setFirst(HttpHeaders out, String name, String preferred, String fallback) {
        String value = preferred != null && !preferred.isBlank() ? preferred : fallback;
        if (value != null && !value.isBlank()) {
            out.set(name, value);
        }
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        String joined = String.join(", ", values).trim();
        return joined.isEmpty() ? null : joined;
    }

    private static String remoteIp(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null || remote.getAddress() == null) return null;
        return remote.getAddress().getHostAddress();
    }

    private static String hostHeader(ServerHttpRequest request) {
        return request.getHeaders().getFirst(HttpHeaders.HOST);
    }

    private static String scheme(ServerHttpRequest request) {
        String scheme = request.getURI().getScheme();
        return scheme != null ? scheme : "http";
    }

    private static String port(ServerHttpRequest request) {
        URI uri = request.getURI();
        if (uri.getPort() > 0) return String.valueOf(uri.getPort());
        return "https".equalsIgnoreCase(scheme(request)) ? "443" : "80";
    }
}
