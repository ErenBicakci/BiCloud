package com.bic.cloud.controlplane.service;

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
import static org.assertj.core.api.Assertions.within;
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

    // ──────────────────────────────────────────────────────────────
    // calculateScore
    // ──────────────────────────────────────────────────────────────

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

        // cpuFree=100, memFree=100 -> score = 0.5*100 + 0.5*100 = 100
        assertThat(score).isCloseTo(100.0, within(0.001));
    }

    @Test
    @DisplayName("calculateScore -> gives 50 points at 50% CPU and 50% RAM usage")
    void calculateScore_halfLoadedWorkerGetsHalfScore() {
        activeState.setCpuUsagePercent(50);
        activeState.setUsedMemoryMb(4096); // 4096/8192 = 50%

        double score = scoringService.calculateScore(node, activeState);

        // cpuFree=50, memFree=50 -> score = 0.5*50 + 0.5*50 = 50
        assertThat(score).isCloseTo(50.0, within(0.001));
    }

    @Test
    @DisplayName("calculateScore -> %100 CPU ve tam dolu RAM ile 0 puan verir")
    void calculateScore_fullyLoadedWorkerGetsZeroScore() {
        activeState.setCpuUsagePercent(100);
        activeState.setUsedMemoryMb(8192);

        double score = scoringService.calculateScore(node, activeState);

        // cpuFree=0, memFree=0 -> score = 0
        assertThat(score).isCloseTo(0.0, within(0.001));
    }

    @Test
    @DisplayName("calculateScore -> a node with totalMemoryMb=0 gets a zero memory score")
    void calculateScore_zeroTotalMemoryGivesZeroMemoryScore() {
        node.setTotalMemoryMb(0);
        activeState.setCpuUsagePercent(0);

        double score = scoringService.calculateScore(node, activeState);

        // memFree=0 (division guard), cpuFree=100 -> score = 50
        assertThat(score).isCloseTo(50.0, within(0.001));
    }

    // ──────────────────────────────────────────────────────────────
    // selectBestWorker
    // ──────────────────────────────────────────────────────────────

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

        // activeState: idle (score~100), busyState: busy (low score)
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

        // per heartbeat both are idle - but test-worker has 6 GB / 6 cores
        // reserved (just-assigned containers). The score must account for the
        // reservation and pick second-worker.
        when(stateRepository.findAll()).thenReturn(List.of(activeState, secondState));
        when(containerInstanceRepository.sumReservedResourcesByWorker())
                .thenReturn(List.<Object[]>of(new Object[]{node.getId(), 6144L, 6.0}));

        Optional<WorkerNode> result = scoringService.selectBestWorker();

        assertThat(result).isPresent();
        assertThat(result.get().getWorkerName()).isEqualTo("second-worker");
    }

    // ──────────────────────────────────────────────────────────────
    // selectBestWorkerWithCapacity
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("selectBestWorkerWithCapacity -> reserved memory counts in the capacity check even when the heartbeat looks idle")
    void selectBestWorkerWithCapacity_filtersByReservedMemory() {
        // OS usage is 0 but 8000 MB reserved -> 192 MB free, a 500 MB request cannot fit
        when(stateRepository.findAll()).thenReturn(List.of(activeState));
        when(containerInstanceRepository.sumReservedResourcesByWorker())
                .thenReturn(List.<Object[]>of(new Object[]{node.getId(), 8000L, 1.0}));

        Optional<WorkerNode> result = scoringService.selectBestWorkerWithCapacity(0, 500);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("selectBestWorkerWithCapacity -> filters out a worker without enough memory")
    void selectBestWorkerWithCapacity_filtersWorkersBelowMemoryRequirement() {
        activeState.setUsedMemoryMb(8000); // only 192 MB free

        when(stateRepository.findAll()).thenReturn(List.of(activeState));

        // 500 MB RAM needed, only 192 MB free
        Optional<WorkerNode> result = scoringService.selectBestWorkerWithCapacity(0, 500);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("selectBestWorkerWithCapacity -> filters out a worker without enough CPU")
    void selectBestWorkerWithCapacity_filtersWorkersBelowCpuRequirement() {
        activeState.setCpuUsagePercent(99); // almost no CPU left
        // 8 cores * 1000 millicores = 8000 total, 1% free = 80 millicores

        when(stateRepository.findAll()).thenReturn(List.of(activeState));

        // 1000 millicores needed, only ~80 free
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

        // activeState: idle, 8GB RAM -> has enough capacity
        // smallState: idle, 1GB RAM -> NOT enough for 1500 MB
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
}
