package com.bic.cloud.worker.metrics;

import com.bic.cloud.worker.dto.ContainerStatsDto;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InfoCmd;
import com.github.dockerjava.api.model.Info;
import com.sun.management.OperatingSystemMXBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkerMetricsServiceTest {

    private static final long MB = 1024 * 1024;

    private DockerClient dockerClient;
    private OperatingSystemMXBean osBean;
    private Info info;

    @BeforeEach
    void setUp() {
        dockerClient = mock(DockerClient.class);
        osBean = mock(OperatingSystemMXBean.class);
        info = mock(Info.class);
        InfoCmd infoCmd = mock(InfoCmd.class);
        when(dockerClient.infoCmd()).thenReturn(infoCmd);
        when(infoCmd.exec()).thenReturn(info);

        // host: 16 cores, 32 GB (what Windows reports)
        when(osBean.getAvailableProcessors()).thenReturn(16);
        when(osBean.getTotalMemorySize()).thenReturn(32L * 1024 * MB);
    }

    @Test
    @DisplayName("capacity comes from Docker, not from the Windows host")
    void capacityFromDockerVm() {
        when(info.getNCPU()).thenReturn(16);
        when(info.getMemTotal()).thenReturn(15_862L * MB);
        when(info.getOperatingSystem()).thenReturn("Docker Desktop");

        WorkerMetricsService service = new WorkerMetricsService(dockerClient, osBean, "Windows 11");

        assertThat(service.getCapacity()).isEqualTo(new WorkerMetricsService.Capacity(16, 15_862));
    }

    @Test
    @DisplayName("falls back to host values when Docker is unreachable")
    void fallbackWhenDockerDown() {
        when(dockerClient.infoCmd().exec()).thenThrow(new RuntimeException("pipe not found"));

        WorkerMetricsService service = new WorkerMetricsService(dockerClient, osBean, "Windows 11");

        assertThat(service.getCapacity()).isEqualTo(new WorkerMetricsService.Capacity(16, 32 * 1024));
    }

    @Test
    @DisplayName("inside a Docker VM, usage is the sum of container stats")
    void usageFromContainerStatsInVm() {
        when(info.getNCPU()).thenReturn(8);
        when(info.getMemTotal()).thenReturn(8_000L * MB);
        when(info.getOperatingSystem()).thenReturn("Docker Desktop");
        when(osBean.getFreeMemorySize()).thenReturn(1L * 1024 * MB); // host looks nearly full - must be ignored

        WorkerMetricsService service = new WorkerMetricsService(dockerClient, osBean, "Windows 11");
        service.getCapacity();

        assertThat(service.getUsedMemoryMb()).isZero();
        assertThat(service.getCpuUsagePercent()).isZero();

        service.recordContainerStats(List.of(
                stats(150.0, 300),   // 1.5 cores
                stats(50.0, 212)));  // 0.5 cores

        assertThat(service.getUsedMemoryMb()).isEqualTo(512);
        assertThat(service.getCpuUsagePercent()).isEqualTo(25); // 2 of 8 cores
    }

    @Test
    @DisplayName("native Linux daemon keeps using host usage")
    void linuxUsesHostUsage() {
        when(info.getNCPU()).thenReturn(16);
        when(info.getMemTotal()).thenReturn(32L * 1024 * MB);
        when(info.getOperatingSystem()).thenReturn("Ubuntu 24.04 LTS");
        when(osBean.getCpuLoad()).thenReturn(0.4);

        WorkerMetricsService service = new WorkerMetricsService(dockerClient, osBean, "Linux");
        service.getCapacity();
        service.recordContainerStats(List.of(stats(100.0, 100)));

        assertThat(service.getCpuUsagePercent()).isEqualTo(40);
    }

    private static ContainerStatsDto stats(double cpuPercent, long memoryMb) {
        ContainerStatsDto dto = new ContainerStatsDto();
        dto.setCpuPercent(cpuPercent);
        dto.setMemoryUsedMb(memoryMb);
        return dto;
    }
}
