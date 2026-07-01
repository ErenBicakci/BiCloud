package com.bic.cloud.controlplane.dto;

import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Latest sample + short history of a container instance.
 * Backs the live cards and sparklines on the service detail page.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContainerMetricsResponse {

    private UUID instanceId;
    private String dockerContainerId;
    private String serviceName;
    private String workerName;

    /** Latest sample; fields are null until the worker sends data. */
    private Double cpuPercent;
    private Long memoryUsedMb;
    private Long memoryLimitMb;
    private Instant updatedAt;

    /** History ordered oldest to newest (~20s apart, at most 90 points). */
    private List<Point> points;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Point {
        private Instant at;
        private double cpuPercent;
        private long memoryUsedMb;
    }
}
