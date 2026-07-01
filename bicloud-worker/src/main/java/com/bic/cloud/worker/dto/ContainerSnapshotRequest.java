package com.bic.cloud.worker.dto;

import lombok.*;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContainerSnapshotRequest {
    private UUID workerId;
    private List<String> runningContainerIds;

    /** Live CPU/RAM usage of running containers (ones that could not be sampled are absent). */
    private List<ContainerStatsDto> containerStats;
}
