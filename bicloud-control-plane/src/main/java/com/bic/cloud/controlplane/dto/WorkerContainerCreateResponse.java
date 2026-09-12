package com.bic.cloud.controlplane.dto;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkerContainerCreateResponse {

    private String  containerId;
    private String  containerIp;
    private String  message;
}
