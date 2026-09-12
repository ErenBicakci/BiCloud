package com.bic.cloud.worker.config;

import com.bic.cloud.worker.client.ControlPlaneHttpClient;
import com.bic.cloud.worker.dto.WorkerHeartbeatRequest;
import com.bic.cloud.worker.dto.WorkerRegisterRequest;
import com.bic.cloud.worker.dto.WorkerRegisterResponse;
import com.bic.cloud.worker.metrics.WorkerMetricsService;
import com.bic.cloud.worker.service.GracefulShutdownService;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkerStartup {

    private final ControlPlaneHttpClient client;
    private final WorkerMetricsService metricsService;
    private final WorkerProperties workerProperties;
    private final GracefulShutdownService gracefulShutdownService;

    @Value("${server.port}")
    private int serverPort;

    private static final String WORKER_ID_FILE = "worker-id.txt";
    private static final int MAX_RETRY_DELAY_SECONDS = 60;

    private volatile boolean registered = false;

    @Getter
    private UUID workerId;

    private int retryCount = 0;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        loadWorkerIdFromDisk();
        attemptRegister();
    }

    @Scheduled(fixedDelay = 5000)
    public void retryRegisterIfNeeded() {
        if (!registered) {
            attemptRegister();
        }
    }

    @PreDestroy
    public void onShutdown() {
        if (!registered || workerId == null) return;

        log.info("Worker shutting down gracefully (workerId={}).", workerId);

        gracefulShutdownService.stopAllManagedContainers();

        WorkerHeartbeatRequest deregisterRequest = WorkerHeartbeatRequest.builder()
                .workerId(workerId)
                .cpuUsagePercent(0)
                .usedMemoryMb(0)
                .timestamp(Instant.now())
                .build();

        client.sendDeregister(deregisterRequest);

        log.info("Worker deregistered. Shutdown complete.");
    }

    private synchronized void attemptRegister() {

        if (registered) return;

        try {
            WorkerRegisterRequest request = WorkerRegisterRequest.builder()
                    .workerId(workerId)
                    .workerName(workerProperties.getName())
                    .ipAddress(workerProperties.getIp())
                    .workerVersion(workerProperties.getVersion())
                    .totalCpuCores(metricsService.getTotalCpuCores())
                    .totalMemoryMb(metricsService.getTotalMemoryMb())
                    .serverPort(serverPort)
                    .timestamp(Instant.now())
                    .build();

            WorkerRegisterResponse response = client.sendRegister(request);

            if (response != null && response.getWorkerId() != null) {
                this.workerId = response.getWorkerId();
                persistWorkerIdToDisk();
                registered = true;
                retryCount = 0;
                log.info("Worker successfully registered with workerId: {}", workerId);
            } else {
                log.warn("Register response was null or missing workerId.");
            }

        } catch (Exception e) {
            retryCount++;
            long delaySec = Math.min((long) Math.pow(2, retryCount), MAX_RETRY_DELAY_SECONDS);
            log.warn("Register failed (attempt {}). Next retry in ~{}s. Error: {}",
                    retryCount, delaySec, e.getMessage());

            try {
                Thread.sleep(delaySec * 1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void loadWorkerIdFromDisk() {
        Path path = Paths.get(WORKER_ID_FILE);
        if (Files.exists(path)) {
            try {
                String content = Files.readString(path).trim();
                if (!content.isEmpty()) {
                    this.workerId = UUID.fromString(content);
                    log.info("Loaded existing workerId from disk: {}", workerId);
                }
            } catch (IOException e) {
                log.warn("Failed to read worker-id file: {}", e.getMessage());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid workerId in file, ignoring: {}", e.getMessage());
            }
        } else {
            log.info("No existing worker-id file found. Will register as new worker.");
        }
    }

    private void persistWorkerIdToDisk() {
        Path path = Paths.get(WORKER_ID_FILE);
        try {
            Files.writeString(path, workerId.toString());
            log.info("WorkerId persisted to disk: {}", path.toAbsolutePath());
        } catch (IOException e) {
            log.error("CRITICAL: Failed to persist workerId to disk! " +
                    "Worker will re-register as new on restart. Error: {}", e.getMessage());
        }
    }

    public boolean isRegistered() {
        return registered;
    }
}