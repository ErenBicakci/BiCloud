package com.bic.cloud.worker.scheduler;

import com.bic.cloud.worker.client.ControlPlaneHttpClient;
import com.bic.cloud.worker.config.WorkerStartup;
import com.bic.cloud.worker.dto.WorkerHeartbeatRequest;
import com.bic.cloud.worker.metrics.WorkerMetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkerHeartbeatScheduler {

    private final ControlPlaneHttpClient client;
    private final WorkerMetricsService metricsService;
    private final WorkerStartup workerStartup;

    private int consecutiveFailures = 0;

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
            if (consecutiveFailures > 0) {
                log.info("Heartbeat recovered for workerId={} after {} failed attempt(s).",
                        workerStartup.getWorkerId(), consecutiveFailures);
                consecutiveFailures = 0;
            }

        } catch (ResourceAccessException e) {
            recordHeartbeatFailure("Control plane heartbeat timed out or is unreachable", e);
        } catch (Exception e) {
            recordHeartbeatFailure("Heartbeat failed", e);
        }
    }

    private void recordHeartbeatFailure(String reason, Exception e) {
        consecutiveFailures++;

        if (consecutiveFailures == 1 || consecutiveFailures % 12 == 0) {
            log.warn("{} for workerId={} (consecutiveFailures={}, error={})",
                    reason, workerStartup.getWorkerId(), consecutiveFailures, e.getMessage());
            log.debug("Heartbeat failure details for workerId={}", workerStartup.getWorkerId(), e);
        } else {
            log.debug("{} for workerId={} (consecutiveFailures={}, error={})",
                    reason, workerStartup.getWorkerId(), consecutiveFailures, e.getMessage());
        }
    }
}
