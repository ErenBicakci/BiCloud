package com.bic.cloud.controlplane.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Slf4j
@Configuration
public class DockerConfig {

    @Value("${docker.host:npipe:////./pipe/docker_engine}")
    private String dockerHost;

    @Bean
    public DockerClient dockerClient() {
        log.info("Starting DockerClient - host: {}", dockerHost);

        DefaultDockerClientConfig config = DefaultDockerClientConfig
                .createDefaultConfigBuilder()
                .withDockerHost(dockerHost)
                .build();

        var httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .connectionTimeout(Duration.ofSeconds(10))
                .responseTimeout(Duration.ofSeconds(60))
                .build();

        return DockerClientImpl.getInstance(config, httpClient);
    }
}
