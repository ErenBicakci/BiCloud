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


    //composite key: "{projectName}:{serviceName}"
    private String routeKey;

    private String projectName;

    private String serviceName;

    /**
     * All live instances of this service.
     * Each represents a different Docker container.
     */
    @Builder.Default
    private List<ServiceInstance> instances = new ArrayList<>();

    /** Atomic counter for round-robin. */
    @Builder.Default
    private AtomicInteger roundRobinCounter = new AtomicInteger(0);

    /** When the route was first created. */
    @Builder.Default
    private Instant createdAt = Instant.now();

    /** Time of the last register/deregister. */
    @Builder.Default
    private Instant updatedAt = Instant.now();

    /**
     * Returns the next instance using round-robin.
     * Returns {@code null} when all instances have been removed.
     *
     * @return the chosen instance, or null when the list is empty
     */
    public ServiceInstance nextInstance() {
        List<ServiceInstance> snapshot = List.copyOf(instances);
        if (snapshot.isEmpty()) {
            return null;
        }
        // floorMod: Math.abs would break on Integer.MIN_VALUE when the counter wraps
        int idx = Math.floorMod(roundRobinCounter.getAndIncrement(), snapshot.size());
        return snapshot.get(idx);
    }

    /** Composite key builder (static factory). */
    public static String buildKey(String projectName, String serviceName) {
        return projectName.toLowerCase() + ":" + serviceName.toLowerCase();
    }
}
