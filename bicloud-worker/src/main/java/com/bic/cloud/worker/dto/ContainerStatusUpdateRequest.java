package com.bic.cloud.worker.dto;

import lombok.*;

import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContainerStatusUpdateRequest {

    private UUID workerId;
    private String dockerContainerId;
    private String status; 
    private String message;
}
