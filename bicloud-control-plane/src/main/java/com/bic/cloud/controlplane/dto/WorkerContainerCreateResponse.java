package com.bic.cloud.controlplane.dto;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkerContainerCreateResponse {

    private String  containerId;
    private Integer assignedPort;

    /**
     * The container's internal IP on the project Docker network (172.x.x.x).
     * Read via inspect on the worker; the CP needs it for gateway registration.
     */
    private String  containerIp;

    private String  message;
}
