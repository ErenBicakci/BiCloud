package com.bic.cloud.controlplane.dto;

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
    private List<ContainerStatsDto> containerStats;
}
