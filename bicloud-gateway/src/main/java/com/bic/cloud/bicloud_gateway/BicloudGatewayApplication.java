package com.bic.cloud.bicloud_gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * BiCloud gateway: host-header based dynamic routing
 * ({service}.{project}.bicloud.local), round-robin across replicas and a
 * route registry the control plane updates at runtime.
 */
@SpringBootApplication
@EnableScheduling
public class BicloudGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(BicloudGatewayApplication.class, args);
    }
}
