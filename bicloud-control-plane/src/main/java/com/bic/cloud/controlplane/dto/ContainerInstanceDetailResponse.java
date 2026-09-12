package com.bic.cloud.controlplane.dto;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContainerInstanceDetailResponse {

    private UUID id;
    private String dockerContainerId;
    private String serviceName;
    private String projectName;
    private String workerName;
    private UUID workerId;
    private String workerIp;
    private String workerStatus;
    private String status;
    private Instant createdAt;

    private String gatewayUrl;
    private Double cpuPercent;
    private Long memoryUsedMb;
    private Long memoryLimitMb;
    private Instant metricsAt;
}
