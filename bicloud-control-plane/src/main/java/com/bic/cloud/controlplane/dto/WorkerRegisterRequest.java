package com.bic.cloud.controlplane.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkerRegisterRequest {

    private UUID workerId;

    @NotBlank(message = "workerName is required")
    private String workerName;

    private String ipAddress;
    private String meshIp;
    private String workerVersion;

    @Min(value = 1, message = "totalCpuCores must be at least 1")
    private int totalCpuCores;

    @Min(value = 1, message = "totalMemoryMb must be at least 1")
    private long totalMemoryMb;

    private int serverPort;

    private Instant timestamp;
}
