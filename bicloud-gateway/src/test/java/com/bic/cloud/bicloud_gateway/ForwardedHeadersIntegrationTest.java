package com.bic.cloud.bicloud_gateway;

import com.bic.cloud.bicloud_gateway.docker.GatewayNetworkManager;
import com.bic.cloud.bicloud_gateway.registry.RouteRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sends a real request through the gateway to a local echo server and checks
 * what the upstream app actually receives.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "bicloud.gateway.reconcile-initial-delay-ms=600000")
class ForwardedHeadersIntegrationTest {

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private RouteRegistry registry;

    @MockitoBean
    private GatewayNetworkManager networkManager;

    private HttpServer upstream;
    private final AtomicReference<Map<String, String>> received = new AtomicReference<>();

    @BeforeEach
    void startUpstream() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", ex -> {
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            ex.getRequestHeaders().forEach((k, v) -> headers.put(k, String.join(",", v)));
            received.set(headers);
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        upstream.start();
        registry.register("demo", "bench", "127.0.0.1", upstream.getAddress().getPort(), "i-1", true);
    }

    @AfterEach
    void stopUpstream() {
        registry.deregister("demo", "bench", "127.0.0.1", upstream.getAddress().getPort());
        upstream.stop(0);
    }

    @Test
    void upstreamSeesOriginalHostAndClientAddress() throws Exception {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + "/hello"))
                .header("Host", "bench.demo.bicloud.local")
                .header("X-Forwarded-For", "6.6.6.6")
                .header("X-Forwarded-Host", "evil.example")
                .GET()
                .build();

        HttpResponse<Void> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(204);
        Map<String, String> headers = received.get();
        assertThat(headers.get("Host")).isEqualTo("bench.demo.bicloud.local");
        assertThat(headers.get("X-Forwarded-For")).isEqualTo("127.0.0.1");
        assertThat(headers.get("X-Forwarded-Host")).isEqualTo("bench.demo.bicloud.local");
        assertThat(headers.get("X-Forwarded-Proto")).isEqualTo("http");
    }
}
