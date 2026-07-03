package com.bic.cloud.worker.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class ContainerCreateRequest {

    private String projectName;

    private String imageName;

    private String serviceName;

    private Integer containerPort;

    private Map<String, String> env;

    private Integer cpuLimitMillicores;

    private Integer memoryLimitMb;

    private String instanceId;

    /** When true the container is also attached to the internet-capable egress bridge. */
    private boolean allowInternet;
}
