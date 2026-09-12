package com.bic.cloud.controlplane.dto;

import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContainerMetricsResponse {

    private UUID instanceId;
    private String dockerContainerId;
    private String serviceName;
    private String workerName;

    private Double cpuPercent;
    private Long memoryUsedMb;
    private Long memoryLimitMb;
    private Instant updatedAt;

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
