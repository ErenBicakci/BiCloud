package com.bic.cloud.controlplane.dto;

import lombok.*;

import java.util.List;
import java.util.UUID;

/**
 * The worker periodically sends the CP the list of BiCloud container IDs
 * actually running in Docker.
 * The CP compares this snapshot with the RUNNING records in the DB and
 * marks zombie records missing from Docker as FAILED.
 */
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
