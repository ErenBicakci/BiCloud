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
    private Integer memoryLimitMb;
    private Double cpuLimit;
    private boolean allowInternet;
}
