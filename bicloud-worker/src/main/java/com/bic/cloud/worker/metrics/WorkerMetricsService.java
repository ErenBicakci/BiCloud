package com.bic.cloud.worker.metrics;

import com.bic.cloud.worker.dto.ContainerStatsDto;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Info;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;

import com.sun.management.OperatingSystemMXBean;

/**
 * Capacity and usage of the machine that actually runs the containers.
 *
 * Capacity always comes from the Docker daemon (NCPU / MemTotal). On Linux
 * that equals the host; with Docker Desktop (Windows/macOS) the containers run
 * inside a VM that only gets part of the host's RAM, so the host's values would
 * overstate what the scheduler can place here.
 *
 * Usage: on a native Linux daemon the host's own CPU/memory usage is the right
 * signal (containers share the host). Inside a Docker Desktop VM the host's
 * usage says nothing about the VM, so usage is the sum of the managed
 * containers' stats from the latest snapshot instead.
 */
@Slf4j
@Component
public class WorkerMetricsService {

    private static final long MB = 1024 * 1024;
    private static final int SAMPLE_COUNT = 6;

    private final DockerClient dockerClient;
    private final OperatingSystemMXBean osBean;
    private final boolean desktopOs;

    private final double[] cpuSamples = new double[SAMPLE_COUNT];
    private int sampleIndex = 0;
    private int filledSamples = 0;

    private volatile boolean dockerInVm;
    private volatile Capacity lastCapacity;
    private volatile ContainerUsage containerUsage;

    public record Capacity(int cpuCores, long memoryMb) {}

    private record ContainerUsage(double cpuPercentOfOneCore, long memoryMb) {}

    @Autowired
    public WorkerMetricsService(DockerClient dockerClient) {
        this(dockerClient,
                (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean(),
                System.getProperty("os.name", ""));
    }

    WorkerMetricsService(DockerClient dockerClient, OperatingSystemMXBean osBean, String osName) {
        this.dockerClient = dockerClient;
        this.osBean = osBean;
        String os = osName.toLowerCase(Locale.ROOT);
        this.desktopOs = os.startsWith("windows") || os.startsWith("mac");
        this.dockerInVm = desktopOs;
        this.lastCapacity = hostCapacity();
    }

    public Capacity getCapacity() {
        try {
            Info info = dockerClient.infoCmd().exec();
            Integer cpus = info.getNCPU();
            Long memTotal = info.getMemTotal();
            if (cpus != null && cpus > 0 && memTotal != null && memTotal > 0) {
                String dockerOs = info.getOperatingSystem();
                dockerInVm = desktopOs
                        || (dockerOs != null && dockerOs.toLowerCase(Locale.ROOT).contains("docker desktop"));
                Capacity capacity = new Capacity(cpus, memTotal / MB);
                lastCapacity = capacity;
                log.info("Docker capacity: {} CPU(s), {} MB (daemon OS: {}, runs in VM: {})",
                        capacity.cpuCores(), capacity.memoryMb(), dockerOs, dockerInVm);
                return capacity;
            }
            log.warn("Docker info returned no CPU/memory totals; falling back to host values.");
        } catch (Exception e) {
            log.warn("Could not read capacity from Docker ({}); falling back to host values.", e.getMessage());
        }
        Capacity host = hostCapacity();
        lastCapacity = host;
        return host;
    }

    public int getCpuUsagePercent() {
        if (dockerInVm) {
            ContainerUsage usage = containerUsage;
            int cores = Math.max(1, lastCapacity.cpuCores());
            return usage == null ? 0 : (int) Math.min(100, Math.round(usage.cpuPercentOfOneCore() / cores));
        }

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
        return (int) Math.round(sum / count * 100);
    }

    public long getUsedMemoryMb() {
        if (dockerInVm) {
            ContainerUsage usage = containerUsage;
            return usage == null ? 0 : usage.memoryMb();
        }

        long availableKb = readMemAvailableKb();
        if (availableKb >= 0) {
            long totalKb = osBean.getTotalMemorySize() / 1024;
            return (totalKb - availableKb) / 1024;
        }
        return (osBean.getTotalMemorySize() - osBean.getFreeMemorySize()) / MB;
    }

    /** Called with each container snapshot; used as the usage signal inside a Docker VM. */
    public void recordContainerStats(List<ContainerStatsDto> stats) {
        double cpu = 0;
        long memory = 0;
        for (ContainerStatsDto s : stats) {
            cpu += Math.max(0, s.getCpuPercent());
            memory += Math.max(0, s.getMemoryUsedMb());
        }
        containerUsage = new ContainerUsage(cpu, memory);
    }

    private Capacity hostCapacity() {
        return new Capacity(osBean.getAvailableProcessors(), osBean.getTotalMemorySize() / MB);
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
    }
}
