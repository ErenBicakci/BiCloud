package com.bic.cloud.worker.scheduler;

import com.bic.cloud.worker.client.ControlPlaneHttpClient;
import com.bic.cloud.worker.config.WorkerStartup;
import com.bic.cloud.worker.docker.DockerContainerService;
import com.bic.cloud.worker.dto.ContainerSnapshotRequest;
import com.bic.cloud.worker.dto.ContainerStatsDto;
import com.bic.cloud.worker.metrics.ContainerStatsCollector;
import com.github.dockerjava.api.model.Container;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;


@Slf4j
@Component
@RequiredArgsConstructor
public class ContainerSnapshotScheduler {

    private final DockerContainerService dockerContainerService;
    private final ControlPlaneHttpClient controlPlaneClient;
    private final WorkerStartup workerStartup;
    private final ContainerStatsCollector statsCollector;

    @Scheduled(
            fixedDelayString = "${worker.container-snapshot-interval-ms:15000}",
            initialDelayString = "${worker.container-snapshot-initial-delay-ms:15000}"
    )
    public void sendSnapshot() {

        if (!workerStartup.isRegistered()) {
            return;
        }

        try {
            List<ContainerStatsDto> stats = dockerContainerService.listManagedRunningContainers().stream()
                    .map(Container::getId)
                    .map(statsCollector::collect)
                    .filter(java.util.Objects::nonNull)
                    .toList();

            List<String> runningIds = dockerContainerService.listManagedRunningContainers().stream()
                    .map(Container::getId)
                    .toList();

            ContainerSnapshotRequest snapshot = ContainerSnapshotRequest.builder()
                    .workerId(workerStartup.getWorkerId())
                    .runningContainerIds(runningIds)
                    .containerStats(stats)
                    .build();

            controlPlaneClient.sendContainerSnapshot(snapshot);

            log.debug("[Snapshot] Sent {} running container ID(s) with {} stats to Control Plane.",
                    runningIds.size(), stats.size());

        } catch (Exception e) {
            log.error("[Snapshot] Failed to build/send container snapshot: {}", e.getMessage());
        }
    }
}
