package com.bic.cloud.bicloud_gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BicloudGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(BicloudGatewayApplication.class, args);
    }
}
