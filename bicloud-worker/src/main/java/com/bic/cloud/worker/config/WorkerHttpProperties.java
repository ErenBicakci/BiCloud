package com.bic.cloud.worker.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "worker.http")
public class WorkerHttpProperties {

    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 10000;
}
