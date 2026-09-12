package com.bic.cloud.worker.dto;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContainerStatsDto {

    private String dockerContainerId;
    private double cpuPercent;
    private long memoryUsedMb;
    private long memoryLimitMb;
}
