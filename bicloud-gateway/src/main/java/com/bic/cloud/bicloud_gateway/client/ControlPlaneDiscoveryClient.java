package com.bic.cloud.bicloud_gateway.client;

import com.bic.cloud.bicloud_gateway.dto.MeshEndpointDto;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

@Slf4j
@Component
public class ControlPlaneDiscoveryClient {

    private static final String API_KEY_HEADER = "X-Api-Key";

    @Value("${bicloud.control-plane.url}")
    private String controlPlaneUrl;

    @Value("${bicloud.control-plane.api-key}")
    private String apiKey;

    private WebClient webClient;

    @PostConstruct
    void init() {
        this.webClient = WebClient.builder()
                .baseUrl(controlPlaneUrl)
                .defaultHeader(API_KEY_HEADER, apiKey)
                .build();
    }

    public Mono<List<MeshEndpointDto>> discover(String projectName, String serviceName) {
        return webClient.get()
                .uri("/api/workers/discover/{p}/{s}", projectName, serviceName)
                .retrieve()
                .bodyToFlux(MeshEndpointDto.class)
                .collectList()
                .timeout(Duration.ofSeconds(5))
                .onErrorResume(e -> {
                    log.error("[Mesh] CP discovery failed {}/{}: {}",
                            projectName, serviceName, e.getMessage());
                    return Mono.just(List.of());
                });
    }

    public Mono<Void> requestResync() {
        return webClient.post()
                .uri("/api/workers/gateway-resync")
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(10))
                .doOnSuccess(r -> log.debug("CP gateway-resync requested"))
                .onErrorResume(e -> {
                    log.warn("CP gateway-resync failed (will retry next period): {}",
                            e.getMessage());
                    return Mono.empty();
                })
                .then();
    }
}
