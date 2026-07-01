package com.bic.cloud.bicloud_gateway.dto;

import lombok.Data;

import java.util.UUID;

/**
 * Service endpoint info returned from the control plane's
 * /api/workers/discover/{project}/{service} endpoint (mirrors ServiceEndpointDto on the CP side).
 */
@Data
public class MeshEndpointDto {
    private String serviceName;
    private String containerIp;
    private int containerPort;
    private String workerIp;
    private int workerPort;
    private UUID workerId;
}
