package com.bic.cloud.worker.metrics;

import com.bic.cloud.worker.dto.ContainerStatsDto;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.CpuStatsConfig;
import com.github.dockerjava.api.model.CpuUsageConfig;
import com.github.dockerjava.api.model.MemoryStatsConfig;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Statistics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads one-shot (no-stream) resource usage from the Docker stats API.
 * With stream=false the daemon takes two samples and fills the precpu fields,
 * so the CPU percentage can use the exact same formula as `docker stats`.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContainerStatsCollector {

    private static final int STATS_TIMEOUT_SECONDS = 5;

    private final DockerClient dockerClient;

    /**
     * Reads live stats for one container. Returns null when the container
     * stopped/was removed in the meantime, or on timeout.
     */
    public ContainerStatsDto collect(String containerId) {
        try (FirstResultCallback callback = new FirstResultCallback()) {

            dockerClient.statsCmd(containerId).withNoStream(true).exec(callback);
            Statistics stats = callback.await(STATS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (stats == null) return null;

            return ContainerStatsDto.builder()
                    .dockerContainerId(containerId)
                    .cpuPercent(calculateCpuPercent(stats))
                    .memoryUsedMb(calculateMemoryUsedMb(stats))
                    .memoryLimitMb(toMb(stats.getMemoryStats() != null
                            ? stats.getMemoryStats().getLimit() : null))
                    .build();

        } catch (Exception e) {
            log.debug("[Stats] Could not collect stats for container {}: {}",
                    containerId, e.getMessage());
            return null;
        }
    }

    /**
     * Same formula as the docker stats CLI:
     * cpu% = (cpuDelta / systemDelta) * onlineCpus * 100
     */
    private double calculateCpuPercent(Statistics stats) {
        CpuStatsConfig cpu = stats.getCpuStats();
        CpuStatsConfig preCpu = stats.getPreCpuStats();
        if (cpu == null || preCpu == null) return 0;

        CpuUsageConfig usage = cpu.getCpuUsage();
        CpuUsageConfig preUsage = preCpu.getCpuUsage();
        if (usage == null || preUsage == null
                || usage.getTotalUsage() == null || preUsage.getTotalUsage() == null
                || cpu.getSystemCpuUsage() == null || preCpu.getSystemCpuUsage() == null) {
            return 0;
        }

        long cpuDelta = usage.getTotalUsage() - preUsage.getTotalUsage();
        long systemDelta = cpu.getSystemCpuUsage() - preCpu.getSystemCpuUsage();
        if (cpuDelta <= 0 || systemDelta <= 0) return 0;

        long onlineCpus = cpu.getOnlineCpus() != null ? cpu.getOnlineCpus()
                : Runtime.getRuntime().availableProcessors();

        double percent = ((double) cpuDelta / systemDelta) * onlineCpus * 100.0;
        return Math.round(percent * 10.0) / 10.0;
    }

    /**
     * Real usage with cache/inactive_file subtracted, like the docker stats CLI.
     */
    private long calculateMemoryUsedMb(Statistics stats) {
        MemoryStatsConfig mem = stats.getMemoryStats();
        if (mem == null || mem.getUsage() == null) return 0;

        long usage = mem.getUsage();
        if (mem.getStats() != null && mem.getStats().getCache() != null) {
            usage -= mem.getStats().getCache();
        }
        return toMb(Math.max(0, usage));
    }

    private long toMb(Long bytes) {
        return bytes == null ? 0 : bytes / (1024 * 1024);
    }

    /**
     * Callback that captures the first Statistics item and allows a bounded wait.
     * (InvocationBuilder.AsyncResultCallback.awaitResult timeout desteklemiyor;
     * used to block forever when the stream closed without emitting an item.)
     */
    private static class FirstResultCallback extends ResultCallback.Adapter<Statistics> {

        private final AtomicReference<Statistics> first = new AtomicReference<>();
        private final CountDownLatch received = new CountDownLatch(1);

        @Override
        public void onNext(Statistics stats) {
            if (first.compareAndSet(null, stats)) {
                received.countDown();
            }
        }

        @Override
        public void onComplete() {
            received.countDown(); // stop waiting if the stream closes without an item
            super.onComplete();
        }

        Statistics await(long timeout, TimeUnit unit) throws InterruptedException {
            received.await(timeout, unit);
            return first.get();
        }
    }
}
