package com.bic.cloud.controlplane.dto;

import lombok.*;

import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkerRegisterResponse {

    private UUID workerId;
}
