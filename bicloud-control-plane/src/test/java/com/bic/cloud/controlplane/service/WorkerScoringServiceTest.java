package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.exception.NoAvailableWorkerException;
import com.bic.cloud.controlplane.model.ContainerInstance;
import com.bic.cloud.controlplane.model.ProjectImage;
import com.bic.cloud.controlplane.model.WorkerNode;
import com.bic.cloud.controlplane.model.WorkerState;
import com.bic.cloud.controlplane.repository.ContainerInstanceRepository;
import com.bic.cloud.controlplane.repository.WorkerNodeRepository;
import com.bic.cloud.controlplane.repository.WorkerStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerScoringServiceTest {

    @Mock
    private WorkerNodeRepository nodeRepository;

    @Mock
    private WorkerStateRepository stateRepository;

    @Mock
    private ContainerInstanceRepository containerInstanceRepository;

    @InjectMocks
    private WorkerScoringService scoringService;

    private WorkerNode node;
    private WorkerState activeState;

    @BeforeEach
    void setUp() {
        node = WorkerNode.builder()
                .id(UUID.randomUUID())
                .workerName("test-worker")
                .totalCpuCores(8)
                .totalMemoryMb(8192)
                .serverPort(8081)
                .build();

        activeState = WorkerState.builder()
                .worker(node)
                .status(WorkerState.NodeStatus.ACTIVE)
                .cpuUsagePercent(0)
                .usedMemoryMb(0)
                .build();
    }

    @Test
    @DisplayName("calculateScore -> returns 0.0 for a null node")
    void calculateScore_returnsZeroForNullNode() {
        double score = scoringService.calculateScore(null, activeState);
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    @DisplayName("calculateScore -> returns 0.0 for a null state")
    void calculateScore_returnsZeroForNullState() {
        double score = scoringService.calculateScore(node, null);
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    @DisplayName("calculateScore -> returns 0.0 for an OFFLINE worker")
    void calculateScore_returnsZeroForOfflineWorker() {
        activeState.setStatus(WorkerState.NodeStatus.OFFLINE);
        double score = scoringService.calculateScore(node, activeState);
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    @DisplayName("calculateScore -> returns 0.0 for an OVERLOADED worker")
    void calculateScore_returnsZeroForOverloadedWorker() {
        activeState.setStatus(WorkerState.NodeStatus.OVERLOADED);
        double score = scoringService.calculateScore(node, activeState);
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    @DisplayName("calculateScore -> an idle worker (0% CPU, 0 RAM) gets the max score")
    void calculateScore_idleWorkerGetsMaxScore() {
        activeState.setCpuUsagePercent(0);
        activeState.setUsedMemoryMb(0);

        double score = scoringService.calculateScore(node, activeState);

        assertThat(score).isCloseTo(100.0, within(0.001));
    }

    @Test
    @DisplayName("calculateScore -> gives 50 points at 50% CPU and 50% RAM usage")
    void calculateScore_halfLoadedWorkerGetsHalfScore() {
        activeState.setCpuUsagePercent(50);
        activeState.setUsedMemoryMb(4096);

        double score = scoringService.calculateScore(node, activeState);

        assertThat(score).isCloseTo(50.0, within(0.001));
    }

    @Test
    @DisplayName("calculateScore -> %100 CPU ve tam dolu RAM ile 0 puan verir")
    void calculateScore_fullyLoadedWorkerGetsZeroScore() {
        activeState.setCpuUsagePercent(100);
        activeState.setUsedMemoryMb(8192);

        double score = scoringService.calculateScore(node, activeState);

        assertThat(score).isCloseTo(0.0, within(0.001));
    }

    @Test
    @DisplayName("calculateScore -> a node with totalMemoryMb=0 gets a zero memory score")
    void calculateScore_zeroTotalMemoryGivesZeroMemoryScore() {
        node.setTotalMemoryMb(0);
        activeState.setCpuUsagePercent(0);

        double score = scoringService.calculateScore(node, activeState);

        assertThat(score).isCloseTo(50.0, within(0.001));
    }

    @Test
    @DisplayName("selectBestWorker -> returns empty when no worker is active")
    void selectBestWorker_returnsEmptyWhenNoActiveWorkers() {
        WorkerState offline = WorkerState.builder()
                .worker(node)
                .status(WorkerState.NodeStatus.OFFLINE)
                .build();

        when(stateRepository.findAll()).thenReturn(List.of(offline));

        Optional<WorkerNode> result = scoringService.selectBestWorker();

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("selectBestWorker -> picks the highest scoring worker")
    void selectBestWorker_returnsHighestScoringWorker() {
        WorkerNode busyNode = WorkerNode.builder()
                .id(UUID.randomUUID())
                .workerName("busy-worker")
                .totalCpuCores(4)
                .totalMemoryMb(4096)
                .serverPort(8082)
                .build();

        WorkerState busyState = WorkerState.builder()
                .worker(busyNode)
                .status(WorkerState.NodeStatus.ACTIVE)
                .cpuUsagePercent(90)
                .usedMemoryMb(3900)
                .build();

        when(stateRepository.findAll()).thenReturn(List.of(busyState, activeState));

        Optional<WorkerNode> result = scoringService.selectBestWorker();

        assertThat(result).isPresent();
        assertThat(result.get().getWorkerName()).isEqualTo("test-worker");
    }

    @Test
    @DisplayName("selectBestWorker -> ignores MAINTENANCE workers")
    void selectBestWorker_ignoresNonActiveWorkers() {
        WorkerState maintenanceState = WorkerState.builder()
                .worker(node)
                .status(WorkerState.NodeStatus.MAINTENANCE)
                .build();

        when(stateRepository.findAll()).thenReturn(List.of(maintenanceState));

        Optional<WorkerNode> result = scoringService.selectBestWorker();

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("selectBestWorker -> reservations (assigned container limits) lower the score, spreading burst deploys")
    void selectBestWorker_considersReservedResources() {
        WorkerNode secondNode = WorkerNode.builder()
                .id(UUID.randomUUID())
                .workerName("second-worker")
                .totalCpuCores(8)
                .totalMemoryMb(8192)
                .serverPort(8082)
                .build();

        WorkerState secondState = WorkerState.builder()
                .worker(secondNode)
                .status(WorkerState.NodeStatus.ACTIVE)
                .cpuUsagePercent(0)
                .usedMemoryMb(0)
                .build();

        when(stateRepository.findAll()).thenReturn(List.of(activeState, secondState));
        when(containerInstanceRepository.sumReservedResourcesByWorker())
                .thenReturn(List.<Object[]>of(new Object[]{node.getId(), 6144L, 6.0}));

        Optional<WorkerNode> result = scoringService.selectBestWorker();

        assertThat(result).isPresent();
        assertThat(result.get().getWorkerName()).isEqualTo("second-worker");
    }

    // ──────────────────────────────────────────────────────────────
    // Anti-affinity
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("selectBestWorker -> spreads replicas: prefers the worker not yet hosting the service")
    void selectBestWorker_prefersWorkerWithoutReplicasOfSameService() {
        WorkerNode secondNode = WorkerNode.builder()
                .id(UUID.randomUUID())
                .workerName("second-worker")
                .totalCpuCores(8)
                .totalMemoryMb(8192)
                .serverPort(8082)
                .build();

        WorkerState secondState = WorkerState.builder()
                .worker(secondNode)
                .status(WorkerState.NodeStatus.ACTIVE)
                .cpuUsagePercent(0)
                .usedMemoryMb(0)
                .build();

        when(stateRepository.findAll()).thenReturn(List.of(activeState, secondState));
        when(containerInstanceRepository.countAliveReplicasPerWorker(42L))
                .thenReturn(List.<Object[]>of(new Object[]{node.getId(), 2L}));

        Optional<WorkerNode> result = scoringService.selectBestWorker(42L);

        assertThat(result).isPresent();
        assertThat(result.get().getWorkerName()).isEqualTo("second-worker");
    }

    @Test
    @DisplayName("selectBestWorker -> anti-affinity is soft: a much busier empty worker still loses")
    void selectBestWorker_antiAffinityDoesNotOverrideMuchBusierWorker() {
        WorkerNode busyNode = WorkerNode.builder()
                .id(UUID.randomUUID())
                .workerName("busy-empty-worker")
                .totalCpuCores(8)
                .totalMemoryMb(8192)
                .serverPort(8082)
                .build();

        WorkerState busyState = WorkerState.builder()
                .worker(busyNode)
                .status(WorkerState.NodeStatus.ACTIVE)
                .cpuUsagePercent(90)
                .usedMemoryMb(7373)
                .build();

        when(stateRepository.findAll()).thenReturn(List.of(activeState, busyState));
        when(containerInstanceRepository.countAliveReplicasPerWorker(42L))
                .thenReturn(List.<Object[]>of(new Object[]{node.getId(), 1L}));

        Optional<WorkerNode> result = scoringService.selectBestWorker(42L);

        assertThat(result).isPresent();
        assertThat(result.get().getWorkerName()).isEqualTo("test-worker");
    }

    @Test
    @DisplayName("selectBestWorker -> anti-affinity penalty diminishes after repeated replicas")
    void selectBestWorker_antiAffinityPenaltyDiminishes() {
        WorkerNode halfLoadedNode = WorkerNode.builder()
                .id(UUID.randomUUID())
                .workerName("half-loaded-empty-worker")
                .totalCpuCores(8)
                .totalMemoryMb(8192)
                .serverPort(8082)
                .build();

        WorkerState halfLoadedState = WorkerState.builder()
                .worker(halfLoadedNode)
                .status(WorkerState.NodeStatus.ACTIVE)
                .cpuUsagePercent(50)
                .usedMemoryMb(4096)
                .build();

        when(stateRepository.findAll()).thenReturn(List.of(activeState, halfLoadedState));
        when(containerInstanceRepository.countAliveReplicasPerWorker(42L))
                .thenReturn(List.<Object[]>of(new Object[]{node.getId(), 3L}));

        Optional<WorkerNode> result = scoringService.selectBestWorker(42L);

        assertThat(result).isPresent();
        assertThat(result.get().getWorkerName()).isEqualTo("test-worker");
    }

    @Test
    @DisplayName("selectBestWorker -> a lone worker is still chosen even when it hosts every replica")
    void selectBestWorker_lonWorkerStillChosenDespitePenalty() {
        when(stateRepository.findAll()).thenReturn(List.of(activeState));
        when(containerInstanceRepository.countAliveReplicasPerWorker(42L))
                .thenReturn(List.<Object[]>of(new Object[]{node.getId(), 5L}));

        Optional<WorkerNode> result = scoringService.selectBestWorker(42L);

        assertThat(result).isPresent();
        assertThat(result.get().getWorkerName()).isEqualTo("test-worker");
    }

    @Test
    @DisplayName("selectBestWorkerWithCapacity -> capacity filter and anti-affinity work together")
    void selectBestWorkerWithCapacity_appliesAntiAffinity() {
        WorkerNode secondNode = WorkerNode.builder()
                .id(UUID.randomUUID())
                .workerName("second-worker")
                .totalCpuCores(8)
                .totalMemoryMb(8192)
                .serverPort(8082)
                .build();

        WorkerState secondState = WorkerState.builder()
                .worker(secondNode)
                .status(WorkerState.NodeStatus.ACTIVE)
                .cpuUsagePercent(0)
                .usedMemoryMb(0)
                .build();

        when(stateRepository.findAll()).thenReturn(List.of(activeState, secondState));
        when(containerInstanceRepository.countAliveReplicasPerWorker(42L))
                .thenReturn(List.<Object[]>of(new Object[]{node.getId(), 1L}));

        Optional<WorkerNode> result = scoringService.selectBestWorkerWithCapacity(0, 500, 42L);

        assertThat(result).isPresent();
        assertThat(result.get().getWorkerName()).isEqualTo("second-worker");
    }

    @Test
    @DisplayName("selectBestWorkerWithCapacity -> reserved memory counts in the capacity check even when the heartbeat looks idle")
    void selectBestWorkerWithCapacity_filtersByReservedMemory() {
        when(stateRepository.findAll()).thenReturn(List.of(activeState));
        when(containerInstanceRepository.sumReservedResourcesByWorker())
                .thenReturn(List.<Object[]>of(new Object[]{node.getId(), 8000L, 1.0}));

        Optional<WorkerNode> result = scoringService.selectBestWorkerWithCapacity(0, 500);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("selectBestWorkerWithCapacity -> filters out a worker without enough memory")
    void selectBestWorkerWithCapacity_filtersWorkersBelowMemoryRequirement() {
        activeState.setUsedMemoryMb(8000);

        when(stateRepository.findAll()).thenReturn(List.of(activeState));

        Optional<WorkerNode> result = scoringService.selectBestWorkerWithCapacity(0, 500);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("selectBestWorkerWithCapacity -> filters out a worker without enough CPU")
    void selectBestWorkerWithCapacity_filtersWorkersBelowCpuRequirement() {
        activeState.setCpuUsagePercent(99);

        when(stateRepository.findAll()).thenReturn(List.of(activeState));

        Optional<WorkerNode> result = scoringService.selectBestWorkerWithCapacity(1000, 0);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("selectBestWorkerWithCapacity -> picks the best worker that has capacity")
    void selectBestWorkerWithCapacity_returnsHighestScoringWorkerWithEnoughCapacity() {
        WorkerNode smallNode = WorkerNode.builder()
                .id(UUID.randomUUID())
                .workerName("small-worker")
                .totalCpuCores(2)
                .totalMemoryMb(1024)
                .serverPort(8083)
                .build();

        WorkerState smallState = WorkerState.builder()
                .worker(smallNode)
                .status(WorkerState.NodeStatus.ACTIVE)
                .cpuUsagePercent(0)
                .usedMemoryMb(0)
                .build();

        when(stateRepository.findAll()).thenReturn(List.of(smallState, activeState));

        Optional<WorkerNode> result = scoringService.selectBestWorkerWithCapacity(0, 1500);

        assertThat(result).isPresent();
        assertThat(result.get().getWorkerName()).isEqualTo("test-worker");
    }

    @Test
    @DisplayName("selectBestWorkerWithCapacity -> returns empty when no worker meets the capacity")
    void selectBestWorkerWithCapacity_returnsEmptyWhenNoWorkersHaveEnoughCapacity() {
        activeState.setUsedMemoryMb(8000);

        when(stateRepository.findAll()).thenReturn(List.of(activeState));

        Optional<WorkerNode> result = scoringService.selectBestWorkerWithCapacity(0, 1000);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("selectAndReserveWorker -> successfully reserves PENDING instance when capacity exists")
    void selectAndReserveWorker_success() {
        ProjectImage image = ProjectImage.builder()
                .id(10L)
                .serviceName("api")
                .cpuLimit(0.5)
                .memoryLimitMb(512)
                .build();

        when(stateRepository.findAll()).thenReturn(List.of(activeState));
        when(containerInstanceRepository.save(any(ContainerInstance.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Optional<ContainerInstance> reservation =
                scoringService.selectAndReserveWorker(image, 500, 512);

        assertThat(reservation).isPresent();
        ContainerInstance instance = reservation.get();
        assertThat(instance.getWorkerNode().getWorkerName()).isEqualTo("test-worker");
        assertThat(instance.getStatus()).isEqualTo(ContainerInstance.InstanceStatus.PENDING);
        assertThat(instance.getProjectImage()).isEqualTo(image);
        assertThat(instance.getWorkerNode()).isEqualTo(node);
        verify(containerInstanceRepository).save(any(ContainerInstance.class));
    }

    @Test
    @DisplayName("selectAndReserveWorker -> returns empty when capacity is insufficient")
    void selectAndReserveWorker_returnsEmptyWhenNoCapacity() {
        activeState.setUsedMemoryMb(8000);

        ProjectImage image = ProjectImage.builder()
                .id(10L)
                .serviceName("api")
                .cpuLimit(0.0)
                .memoryLimitMb(1024)
                .build();

        when(stateRepository.findAll()).thenReturn(List.of(activeState));

        Optional<ContainerInstance> reservation =
                scoringService.selectAndReserveWorker(image, 0, 1024);

        assertThat(reservation).isEmpty();
        verify(containerInstanceRepository, never()).save(any());
    }

    @Test
    @DisplayName("selectAndReserveWorker -> accounts for in-flight PENDING instance capacity on worker")
    void selectAndReserveWorker_accountsForPendingInstancesOnWorker() {
        Object[] workerReservation = new Object[]{node.getId(), 7500L, 1.0};
        when(containerInstanceRepository.sumReservedResourcesByWorker())
                .thenReturn(List.<Object[]>of(workerReservation));

        when(stateRepository.findAll()).thenReturn(List.of(activeState));

        ProjectImage newImage = ProjectImage.builder()
                .id(11L)
                .serviceName("web")
                .cpuLimit(0.0)
                .memoryLimitMb(800)
                .build();

        Optional<ContainerInstance> reservation =
                scoringService.selectAndReserveWorker(newImage, 0, 800);

        assertThat(reservation).isEmpty();
        verify(containerInstanceRepository, never()).save(any());
    }
}
