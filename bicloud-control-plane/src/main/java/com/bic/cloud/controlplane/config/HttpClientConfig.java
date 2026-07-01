package com.bic.cloud.controlplane.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient restClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        // image pull + container create can take long (first pull 10+ min).
        // 15 minutes is a reasonable upper bound.
        factory.setReadTimeout(900_000);  // 15 minutes

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
