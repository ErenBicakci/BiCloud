package com.bic.cloud.worker.scheduler;

import com.bic.cloud.worker.client.ControlPlaneHttpClient;
import com.bic.cloud.worker.config.WorkerStartup;
import com.bic.cloud.worker.docker.DockerContainerService;
import com.bic.cloud.worker.dto.ContainerStatusUpdateRequest;
import com.github.dockerjava.api.model.Container;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContainerHealthScheduler {

    private final DockerContainerService dockerContainerService;
    private final ControlPlaneHttpClient controlPlaneClient;
    private final WorkerStartup workerStartup;

    private final Set<String> reportedDeadContainers = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelay = 10000)
    public void checkContainersHealth() {

        if (!workerStartup.isRegistered()) {
            return;
        }

        try {
            List<Container> containers = dockerContainerService.listManagedContainers();

            for (Container container : containers) {
                String containerId = container.getId();
                String state = container.getState();

                if ("exited".equalsIgnoreCase(state) || "dead".equalsIgnoreCase(state)) {
                    if (!reportedDeadContainers.contains(containerId)) {
                        log.warn("Container {} is {}: names={}", containerId, state,
                                container.getNames() != null ? String.join(",", container.getNames()) : "unknown");

                        ContainerStatusUpdateRequest update = ContainerStatusUpdateRequest.builder()
                                .workerId(workerStartup.getWorkerId())
                                .dockerContainerId(containerId)
                                .status("FAILED")
                                .message("Container state: " + state)
                                .build();

                        controlPlaneClient.sendContainerStatusUpdate(update);
                        reportedDeadContainers.add(containerId);
                    }
                } else if ("running".equalsIgnoreCase(state)) {
                    reportedDeadContainers.remove(containerId);
                }
            }

        } catch (Exception e) {
            log.error("Container health check failed", e);
        }
    }
}
