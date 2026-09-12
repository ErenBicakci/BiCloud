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
    private final String containerIp;
    private final int containerPort;
    private final String workerIp;
    private final int workerPort;
    private final UUID workerId;
    private final boolean exposeExternally;
}
