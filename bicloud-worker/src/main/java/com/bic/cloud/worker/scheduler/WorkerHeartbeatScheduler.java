package com.bic.cloud.worker.scheduler;

import com.bic.cloud.worker.client.ControlPlaneHttpClient;
import com.bic.cloud.worker.config.WorkerStartup;
import com.bic.cloud.worker.dto.WorkerHeartbeatRequest;
import com.bic.cloud.worker.metrics.WorkerMetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkerHeartbeatScheduler {

    private final ControlPlaneHttpClient client;
    private final WorkerMetricsService metricsService;
    private final WorkerStartup workerStartup;

    @Scheduled(fixedDelay = 5000)
    public void sendHeartbeat() {

        if (!workerStartup.isRegistered()) {
            return;
        }

        try {
            WorkerHeartbeatRequest request = WorkerHeartbeatRequest.builder()
                    .workerId(workerStartup.getWorkerId())
                    .cpuUsagePercent(metricsService.getCpuUsagePercent())
                    .usedMemoryMb(metricsService.getUsedMemoryMb())
                    .timestamp(Instant.now())
                    .build();

            client.sendHeartbeat(request);

        } catch (Exception e) {
            log.error("Heartbeat failed for workerId: {}", workerStartup.getWorkerId(), e);
        }
    }
}