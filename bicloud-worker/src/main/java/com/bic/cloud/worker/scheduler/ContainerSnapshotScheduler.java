package com.bic.cloud.worker.scheduler;

import com.bic.cloud.worker.client.ControlPlaneHttpClient;
import com.bic.cloud.worker.config.WorkerStartup;
import com.bic.cloud.worker.docker.DockerContainerService;
import com.bic.cloud.worker.dto.ContainerSnapshotRequest;
import com.bic.cloud.worker.dto.ContainerStatsDto;
import com.bic.cloud.worker.metrics.ContainerStatsCollector;
import com.bic.cloud.worker.metrics.WorkerMetricsService;
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
    private final WorkerMetricsService metricsService;

    @Scheduled(
            fixedDelayString = "${worker.container-snapshot-interval-ms:15000}",
            initialDelayString = "${worker.container-snapshot-initial-delay-ms:15000}"
    )
    public void sendSnapshot() {

        if (!workerStartup.isRegistered()) {
            return;
        }

        try {
            List<String> listedIds = runningIds();

            // Stats are collected one container at a time (~2 s each), so the
            // list above can be many seconds old by the time it is sent.
            List<ContainerStatsDto> stats = listedIds.stream()
                    .map(statsCollector::collect)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            metricsService.recordContainerStats(stats);

            // Only report containers that are still running now; a container
            // that died meanwhile must not be presented to the control plane
            // as alive (it would be treated as recovered and get traffic again).
            java.util.Set<String> stillRunning = new java.util.HashSet<>(runningIds());
            List<String> runningIds = listedIds.stream().filter(stillRunning::contains).toList();
            stats = stats.stream().filter(s -> stillRunning.contains(s.getDockerContainerId())).toList();

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

    private List<String> runningIds() {
        return dockerContainerService.listManagedRunningContainers().stream()
                .map(Container::getId)
                .toList();
    }
}
