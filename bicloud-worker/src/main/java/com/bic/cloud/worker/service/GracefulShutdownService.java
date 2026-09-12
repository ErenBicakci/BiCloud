package com.bic.cloud.worker.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GracefulShutdownService {

    private static final String MANAGED_LABEL_KEY   = "bicloud.managed";
    private static final String MANAGED_LABEL_VALUE = "true";
    private static final int    STOP_TIMEOUT_SECONDS = 10;

    private final DockerClient dockerClient;

    public void stopAllManagedContainers() {
        List<Container> running = findManagedRunningContainers();

        if (running.isEmpty()) {
            log.info("[GracefulShutdown] No managed containers to stop.");
            return;
        }

        log.info("[GracefulShutdown] Stopping {} managed container(s) before shutdown...", running.size());

        int stopped  = 0;
        int failed   = 0;

        for (Container c : running) {
            String project = c.getLabels().getOrDefault("bicloud.project", "?");
            String service = c.getLabels().getOrDefault("bicloud.service", "?");
            String shortId = c.getId().substring(0, 12);

            try {
                dockerClient.stopContainerCmd(c.getId())
                        .withTimeout(STOP_TIMEOUT_SECONDS)
                        .exec();

                log.info("[GracefulShutdown] Stopped: {} ({}/{})",
                        shortId, project, service);
                stopped++;

            } catch (Exception e) {
                log.warn("[GracefulShutdown] Failed to stop {} ({}/{}): {}",
                        shortId, project, service, e.getMessage());
                failed++;
            }
        }

        log.info("[GracefulShutdown] Done. stopped={}, failed={}", stopped, failed);
    }

    private List<Container> findManagedRunningContainers() {
        try {
            return dockerClient.listContainersCmd()
                    .withLabelFilter(Map.of(MANAGED_LABEL_KEY, MANAGED_LABEL_VALUE))
                    .exec();
        } catch (Exception e) {
            log.error("[GracefulShutdown] Could not list containers from Docker: {}", e.getMessage());
            return List.of();
        }
    }
}
