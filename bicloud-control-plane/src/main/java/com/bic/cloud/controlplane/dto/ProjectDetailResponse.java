package com.bic.cloud.controlplane.dto;

import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectDetailResponse {

    private Long id;
    private String name;
    private String ownerUsername;
    private Instant createdAt;
    private List<ImageSummary> images;
    private int totalRunningContainers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImageSummary {
        private Long id;
        private String serviceName;
        private String imageName;
        private int desiredReplicas;
        private long runningReplicas;
        private int containerPort;
        private Integer memoryLimitMb;
        private Double cpuLimit;
        private Map<String, String> environmentVariables;
        private Instant createdAt;
        private int consecutiveDeployFailures;
        private Instant lastDeployFailureAt;
        private boolean allowInternet;
        private boolean exposeExternally;
    }
}
