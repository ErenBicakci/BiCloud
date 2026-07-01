package com.bic.cloud.bicloud_gateway.dto;

import com.bic.cloud.bicloud_gateway.model.ServiceInstance;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * GET /gateway/routes response: summary of active routes and instances
 * (for monitoring/debug).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteStatusResponse {

    private int totalRoutes;
    private long totalInstances;

    @Builder.Default
    private Instant timestamp = Instant.now();

    // key format "{projectName}:{serviceName}"
    private Map<String, List<ServiceInstance>> routes;
}
