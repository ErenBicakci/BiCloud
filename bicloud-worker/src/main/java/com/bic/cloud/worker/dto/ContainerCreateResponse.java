package com.bic.cloud.worker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ContainerCreateResponse {

    private String  containerId;

    private String containerIp;

    private String message;
}
