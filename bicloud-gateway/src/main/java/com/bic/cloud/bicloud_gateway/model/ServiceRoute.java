package com.bic.cloud.bicloud_gateway.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceRoute {


    private String routeKey;

    private String projectName;

    private String serviceName;

    @Builder.Default
    private List<ServiceInstance> instances = new ArrayList<>();

    @Builder.Default
    private AtomicInteger roundRobinCounter = new AtomicInteger(0);

    @Builder.Default
    private Instant createdAt = Instant.now();

    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Builder.Default
    private boolean exposeExternally = false;

    public ServiceInstance nextInstance() {
        List<ServiceInstance> snapshot = List.copyOf(instances);
        if (snapshot.isEmpty()) {
            return null;
        }
        // floorMod: Math.abs would break on Integer.MIN_VALUE when counter wraps
        int idx = Math.floorMod(roundRobinCounter.getAndIncrement(), snapshot.size());
        return snapshot.get(idx);
    }

    public static String buildKey(String projectName, String serviceName) {
        return projectName.toLowerCase() + ":" + serviceName.toLowerCase();
    }
}
