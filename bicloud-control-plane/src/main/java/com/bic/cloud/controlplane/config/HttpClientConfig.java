package com.bic.cloud.controlplane.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {

    /**
     * Default client for short calls (stop/remove/logs, gateway notifications).
     * docker stop alone can take ~10s (default grace period), hence 30s.
     */
    @Bean
    @Primary
    public RestClient restClient() {
        return buildClient(30_000);
    }

    /**
     * Container create only: the worker may pull the image first, which can
     * take 10+ minutes on a cold cache. Nothing else may use this client -
     * a hung short call must fail fast, not block for 15 minutes.
     */
    @Bean("deployRestClient")
    public RestClient deployRestClient() {
        return buildClient(900_000);
    }

    private RestClient buildClient(int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(readTimeoutMs);

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
