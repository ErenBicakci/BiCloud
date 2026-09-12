package com.bic.cloud.bicloud_gateway.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class MeshEndpointDto {
    private String serviceName;
    private String containerIp;
    private int containerPort;
    private String workerIp;
    private int workerPort;
    private UUID workerId;
    private boolean exposeExternally;
}
