package com.bic.cloud.bicloud_gateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class ClientForwardedHeadersFilterTest {

    private final ClientForwardedHeadersFilter filter = new ClientForwardedHeadersFilter();

    @Test
    @DisplayName("direct client gets X-Forwarded-* from its own connection")
    void directClientHeadersAreAdded() {
        MockServerWebExchange exchange = exchange("203.0.113.7", "web.alpha.bicloud.local:9000");

        HttpHeaders out = filter.filter(exchange.getRequest().getHeaders(), exchange);

        assertThat(out.getFirst("X-Forwarded-For")).isEqualTo("203.0.113.7");
        assertThat(out.getFirst("X-Forwarded-Host")).isEqualTo("web.alpha.bicloud.local:9000");
        assertThat(out.getFirst("X-Forwarded-Proto")).isEqualTo("http");
        assertThat(out.getFirst("X-Forwarded-Port")).isEqualTo("9000");
        assertThat(out.getFirst(HttpHeaders.HOST)).isEqualTo("web.alpha.bicloud.local:9000");
    }

    @Test
    @DisplayName("client-supplied X-Forwarded-* values are discarded, not appended")
    void spoofedHeadersFromClientAreReplaced() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("http://web.alpha.bicloud.local/")
                .remoteAddress(new InetSocketAddress("203.0.113.7", 50000))
                .header(HttpHeaders.HOST, "web.alpha.bicloud.local")
                .header("X-Forwarded-For", "1.2.3.4")
                .header("X-Forwarded-Host", "evil.example")
                .header("X-Forwarded-Proto", "https")
                .build());

        HttpHeaders out = filter.filter(exchange.getRequest().getHeaders(), exchange);

        assertThat(out.get("X-Forwarded-For")).containsExactly("203.0.113.7");
        assertThat(out.get("X-Forwarded-Host")).containsExactly("web.alpha.bicloud.local");
        assertThat(out.get("X-Forwarded-Proto")).containsExactly("http");
        assertThat(out.get("X-Forwarded-Port")).containsExactly("80");
    }

    @Test
    @DisplayName("trusted gateway hop keeps the original client and appends the previous gateway")
    void trustedHopKeepsOriginalClient() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("http://web.alpha.bicloud.local:9000/")
                .remoteAddress(new InetSocketAddress("10.0.0.8", 50000))
                .header(HttpHeaders.HOST, "web.alpha.bicloud.local:9000")
                .header("X-Forwarded-For", "203.0.113.7")
                .header("X-Forwarded-Host", "web.alpha.bicloud.local")
                .header("X-Forwarded-Proto", "http")
                .header("X-Forwarded-Port", "80")
                .build());
        exchange.getAttributes().put(ClientForwardedHeadersFilter.TRUSTED_HOP_ATTR, true);

        // simulate Spring Cloud Gateway's RemoveXForwardedHeadersFilter running first
        HttpHeaders input = new HttpHeaders();
        input.set(HttpHeaders.HOST, "web.alpha.bicloud.local:9000");

        HttpHeaders out = filter.filter(input, exchange);

        assertThat(out.getFirst("X-Forwarded-For")).isEqualTo("203.0.113.7, 10.0.0.8");
        assertThat(out.getFirst("X-Forwarded-Host")).isEqualTo("web.alpha.bicloud.local");
        assertThat(out.getFirst("X-Forwarded-Port")).isEqualTo("80");
    }

    private static MockServerWebExchange exchange(String remoteIp, String host) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("http://" + host + "/api")
                .remoteAddress(new InetSocketAddress(remoteIp, 50000))
                .header(HttpHeaders.HOST, host)
                .build());
    }
}
