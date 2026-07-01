package com.bic.cloud.worker.dto;

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
