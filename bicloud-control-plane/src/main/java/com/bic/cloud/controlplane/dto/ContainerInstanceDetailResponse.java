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

    /**
     * Address reachable through the gateway: http://{serviceName}.{projectName}.bicloud.local
     * There is no host port binding anymore; external access goes through the gateway only.
     */
    private String gatewayUrl;

    /** Latest resource sample - null until the worker sends data. */
    private Double cpuPercent;
    private Long memoryUsedMb;
    private Long memoryLimitMb;
    private Instant metricsAt;
}
