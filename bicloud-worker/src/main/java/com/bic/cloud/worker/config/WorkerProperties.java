package com.bic.cloud.worker.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "worker")
public class WorkerProperties {

    private String name;
    private String ip;
    private String version = "1.0.0";
}
