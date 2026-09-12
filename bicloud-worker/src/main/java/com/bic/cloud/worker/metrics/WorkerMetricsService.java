package com.bic.cloud.worker.metrics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;

@Slf4j
@Component
public class WorkerMetricsService {

    private final OperatingSystemMXBean osBean =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    private static final int SAMPLE_COUNT = 6;
    private final double[] cpuSamples = new double[SAMPLE_COUNT];
    private int sampleIndex = 0;
    private int filledSamples = 0;

    public int getTotalCpuCores() {
        return osBean.getAvailableProcessors();
    }

    public long getTotalMemoryMb() {
        return osBean.getTotalMemorySize() / (1024 * 1024);
    }

    public int getCpuUsagePercent() {
        if (filledSamples == 0) {
            double load = osBean.getCpuLoad();
            if (load < 0) {
                log.debug("CPU load not available yet (JVM warming up)");
                return -1;
            }
            return (int) Math.round(load * 100);
        }

        double sum = 0;
        int count = Math.min(filledSamples, SAMPLE_COUNT);
        for (int i = 0; i < count; i++) {
            sum += cpuSamples[i];
        }
        double avgLoad = sum / count;

        return (int) Math.round(avgLoad * 100);
    }

    public long getUsedMemoryMb() {
        long availableKb = readMemAvailableKb();
        if (availableKb >= 0) {
            long totalKb = osBean.getTotalMemorySize() / 1024;
            return (totalKb - availableKb) / 1024;
        }
        long total = osBean.getTotalMemorySize();
        long free  = osBean.getFreeMemorySize();
        return (total - free) / (1024 * 1024);
    }

    private long readMemAvailableKb() {
        try {
            java.nio.file.Path path = java.nio.file.Paths.get("/proc/meminfo");
            if (!java.nio.file.Files.exists(path)) return -1;
            for (String line : java.nio.file.Files.readAllLines(path)) {
                if (line.startsWith("MemAvailable:")) {
                    String[] parts = line.trim().split("\\s+");
                    return Long.parseLong(parts[1]);
                }
            }
        } catch (Exception e) {
            log.debug("Could not read MemAvailable: {}", e.getMessage());
        }
        return -1;
    }

    @Scheduled(fixedRate = 5000)
    public void sampleCpuLoad() {
        double load = osBean.getCpuLoad();

        if (load < 0) {
            return;
        }

        cpuSamples[sampleIndex] = load;
        sampleIndex = (sampleIndex + 1) % SAMPLE_COUNT;

        if (filledSamples < SAMPLE_COUNT) {
            filledSamples++;
        }

        if (log.isTraceEnabled()) {
            log.trace("CPU sample: {:.1f}% (avg: {}%)", load * 100, getCpuUsagePercent());
        }
    }
}