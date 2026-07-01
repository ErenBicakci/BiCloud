package com.bic.cloud.worker.dto;

import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkerHeartbeatRequest {

    @NotNull(message = "workerId is required")
    private UUID workerId;

    private int cpuUsagePercent;

    private long usedMemoryMb;

    private Instant timestamp;
}