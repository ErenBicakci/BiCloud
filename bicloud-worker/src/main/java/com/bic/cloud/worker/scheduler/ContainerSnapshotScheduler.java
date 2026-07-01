package com.bic.cloud.worker.scheduler;

import com.bic.cloud.worker.client.ControlPlaneHttpClient;
import com.bic.cloud.worker.config.WorkerStartup;
import com.bic.cloud.worker.dto.ContainerSnapshotRequest;
import com.bic.cloud.worker.dto.ContainerStatsDto;
import com.bic.cloud.worker.metrics.ContainerStatsCollector;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;


@Slf4j
@Component
@RequiredArgsConstructor
public class ContainerSnapshotScheduler {

    private static final String MANAGED_LABEL_KEY   = "bicloud.managed";
    private static final String MANAGED_LABEL_VALUE = "true";

    private final DockerClient dockerClient;
    private final ControlPlaneHttpClient controlPlaneClient;
    private final WorkerStartup workerStartup;
    private final ContainerStatsCollector statsCollector;

    @Scheduled(fixedDelay = 20_000, initialDelay = 15_000)
    public void sendSnapshot() {

        if (!workerStartup.isRegistered()) {
            return;
        }

        try {
            // 1) stats collection: blocks ~1-2s per container (the daemon takes
            //    two samples). Hence the ID list is refreshed AFTER stats;
            //    otherwise the stale list makes CP reconcile treat new containers
            //    as zombies and self-healing enters an endless restart loop.
            List<ContainerStatsDto> stats = dockerClient.listContainersCmd()
                    .withLabelFilter(Map.of(MANAGED_LABEL_KEY, MANAGED_LABEL_VALUE))
                    .exec().stream()
                    .map(Container::getId)
                    .map(statsCollector::collect)
                    .filter(java.util.Objects::nonNull)
                    .toList();

            // 2) FRESH list for reconcile - taken right before sending
            List<String> runningIds = dockerClient.listContainersCmd()
                    .withLabelFilter(Map.of(MANAGED_LABEL_KEY, MANAGED_LABEL_VALUE))
                    .exec().stream()
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
