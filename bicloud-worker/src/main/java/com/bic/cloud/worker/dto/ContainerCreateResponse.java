package com.bic.cloud.worker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ContainerCreateResponse {

    private String  containerId;

    private Integer assignedPort;


    private String containerIp;

    private String message;
}
