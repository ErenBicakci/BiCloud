package com.bic.cloud.controlplane.dto;

import com.bic.cloud.controlplane.model.WorkerState;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkerNodeDetailResponse {

    private UUID workerId;
    private String workerName;
    private String workerVersion;

    private int totalCpuCores;
    private long totalMemoryMb;

    private int cpuUsagePercent;
    private long usedMemoryMb;
    private String status;
    private String ipAddress;
    private String meshIp;
    private int serverPort;
    private Instant lastHeartbeat;

    private double score;
    private long runningContainers;

    private Instant createdAt;
}
