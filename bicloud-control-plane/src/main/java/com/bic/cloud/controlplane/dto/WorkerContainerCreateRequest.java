package com.bic.cloud.controlplane.dto;

import lombok.Data;

import java.util.Map;

@Data
public class WorkerContainerCreateRequest {

    private String projectName;

    private String imageName;

    private String serviceName;

    private Integer containerPort;

    private Map<String, String> env;

    private Integer cpuLimitMillicores;

    private Integer memoryLimitMb;

    private String instanceId;

    private boolean allowInternet;
}
