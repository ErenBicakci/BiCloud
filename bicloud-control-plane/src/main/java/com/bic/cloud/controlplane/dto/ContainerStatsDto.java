package com.bic.cloud.controlplane.dto;

import lombok.*;

/**
 * Point-in-time resource usage of a single container, sent with worker snapshots.
 * Exact same schema as the ContainerStatsDto on the worker side.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContainerStatsDto {

    private String dockerContainerId;

    /** In the 0-100 x core-count range; e.g. 200.0 for a container fully using 2 cores */
    private double cpuPercent;

    private long memoryUsedMb;

    /** Memory limit defined for the container (MB). Host total when unlimited. */
    private long memoryLimitMb;
}
