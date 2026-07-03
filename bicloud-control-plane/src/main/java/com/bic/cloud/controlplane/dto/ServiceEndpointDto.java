package com.bic.cloud.controlplane.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Getter
@Builder
@AllArgsConstructor
public class ServiceEndpointDto {

    private final String serviceName;

    /** Container IP inside the Docker bridge - only reachable from the same host */
    private final String containerIp;

    /** Port the container listens on */
    private final int containerPort;

    /** The worker's real LAN/public IP - for cross-host access */
    private final String workerIp;

    /** The worker's Spring Boot port (the mesh proxy listens here) */
    private final int workerPort;

    /** Which worker it runs on */
    private final UUID workerId;

    /** Whether north-south gateway traffic is allowed for this service. */
    private final boolean exposeExternally;
}
