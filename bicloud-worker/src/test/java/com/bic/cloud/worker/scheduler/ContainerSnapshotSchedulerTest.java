package com.bic.cloud.worker.scheduler;

import com.bic.cloud.worker.client.ControlPlaneHttpClient;
import com.bic.cloud.worker.config.WorkerStartup;
import com.bic.cloud.worker.docker.DockerContainerService;
import com.bic.cloud.worker.dto.ContainerSnapshotRequest;
import com.bic.cloud.worker.dto.ContainerStatsDto;
import com.bic.cloud.worker.metrics.ContainerStatsCollector;
import com.bic.cloud.worker.metrics.WorkerMetricsService;
import com.github.dockerjava.api.model.Container;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContainerSnapshotSchedulerTest {

    @Mock private DockerContainerService dockerContainerService;
    @Mock private ControlPlaneHttpClient controlPlaneClient;
    @Mock private WorkerStartup workerStartup;
    @Mock private ContainerStatsCollector statsCollector;
    @Mock private WorkerMetricsService metricsService;

    @InjectMocks private ContainerSnapshotScheduler scheduler;

    @Test
    @DisplayName("a container that dies while stats are being collected is not reported as running")
    void containerDyingDuringStatsCollectionIsLeftOut() {
        when(workerStartup.isRegistered()).thenReturn(true);
        when(workerStartup.getWorkerId()).thenReturn(UUID.randomUUID());
        Container dies = container("dies");
        Container lives = container("lives");
        // listed as running, then gone by the time the snapshot is sent
        when(dockerContainerService.listManagedRunningContainers())
                .thenReturn(List.of(dies, lives))
                .thenReturn(List.of(lives));
        when(statsCollector.collect(anyString()))
                .thenAnswer(inv -> ContainerStatsDto.builder().dockerContainerId(inv.getArgument(0)).build());

        scheduler.sendSnapshot();

        ArgumentCaptor<ContainerSnapshotRequest> sent = ArgumentCaptor.forClass(ContainerSnapshotRequest.class);
        verify(controlPlaneClient).sendContainerSnapshot(sent.capture());
        assertThat(sent.getValue().getRunningContainerIds()).containsExactly("lives");
        assertThat(sent.getValue().getContainerStats())
                .extracting(ContainerStatsDto::getDockerContainerId)
                .containsExactly("lives");
    }

    @Test
    @DisplayName("containers that stay up are all reported")
    void stableContainersAreReported() {
        when(workerStartup.isRegistered()).thenReturn(true);
        when(workerStartup.getWorkerId()).thenReturn(UUID.randomUUID());
        List<Container> both = List.of(container("a"), container("b"));
        when(dockerContainerService.listManagedRunningContainers()).thenReturn(both);
        when(statsCollector.collect(anyString()))
                .thenAnswer(inv -> ContainerStatsDto.builder().dockerContainerId(inv.getArgument(0)).build());

        scheduler.sendSnapshot();

        ArgumentCaptor<ContainerSnapshotRequest> sent = ArgumentCaptor.forClass(ContainerSnapshotRequest.class);
        verify(controlPlaneClient).sendContainerSnapshot(sent.capture());
        assertThat(sent.getValue().getRunningContainerIds()).containsExactly("a", "b");
    }

    private static Container container(String id) {
        Container c = mock(Container.class);
        when(c.getId()).thenReturn(id);
        return c;
    }
}
