package com.bic.cloud.controlplane.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProjectImageResponse {

    private Long id;
    private Long projectId;
    private String serviceName;
    private String imageName;
    private int containerPort;
    private int desiredReplicas;
    private boolean autoscalingEnabled;
    private int minReplicas;
    private int maxReplicas;
    private int targetCpuPercent;
    private int scaleDownCpuPercent;
    private int scaleUpCooldownSeconds;
    private int scaleDownCooldownSeconds;
    private Integer memoryLimitMb;
    private Double cpuLimit;
    private boolean allowInternet;
    private boolean exposeExternally;
}
