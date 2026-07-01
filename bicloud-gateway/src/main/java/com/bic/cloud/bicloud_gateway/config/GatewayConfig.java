package com.bic.cloud.bicloud_gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class GatewayConfig {


    @Bean
    public RouteLocator bicloudRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                // browsers append the port to Host on non-standard ports
                // ("front.project.bicloud.local:9000") - both patterns must match.
                .route("bicloud-dynamic-catch-all", r -> r
                        .host("**.bicloud.local", "**.bicloud.local:*")
                        .uri("http://localhost:1"))  // placeholder - DynamicRoutingFilter overrides this
                .route("bicloud-mesh", r -> r
                        .path("/_bicloud/mesh/**")
                        .uri("http://localhost:1"))  // placeholder - MeshRoutingFilter overrides this
                .build();
    }
}
