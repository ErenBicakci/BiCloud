package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.dto.WorkerHeartbeatRequest;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.when;

/**
 * Heartbeat handling regression tests.
 *
 * Incident: lastHeartbeat used to be written with the worker-sent timestamp.
 * A worker clock 30+ seconds behind made a perfectly regular worker count as
 * OFFLINE constantly, its containers got marked FAILED and self-healing
 * kicked off a restart storm. Liveness must ALWAYS use the CP clock.
 */
@ExtendWith(MockitoExtension.class)
class WorkerServiceHeartbeatTest {

    @Mock
    private WorkerNodeRepository nodeRepository;

    @Mock
    private WorkerStateRepository stateRepository;

    @Mock
    private ContainerInstanceRepository containerInstanceRepository;

    @Mock
    private WorkerScoringService scoringService;

    @InjectMocks
    private WorkerService workerService;

    private WorkerNode node;
    private WorkerState state;

    @BeforeEach
    void setUp() {
        node = WorkerNode.builder()
                .id(UUID.randomUUID())
                .workerName("test-worker")
                .totalCpuCores(8)
                .totalMemoryMb(8192)
                .serverPort(8081)
                .build();

        state = WorkerState.builder()
                .worker(node)
                .status(WorkerState.NodeStatus.ACTIVE)
                .cpuUsagePercent(10)
                .usedMemoryMb(1024)
                .build();

        when(nodeRepository.findByIdForUpdate(node.getId())).thenReturn(Optional.of(node));
        when(stateRepository.findById(node.getId())).thenReturn(Optional.of(state));
    }

    private WorkerHeartbeatRequest heartbeat(Instant workerClock) {
        return WorkerHeartbeatRequest.builder()
                .workerId(node.getId())
                .cpuUsagePercent(20)
                .usedMemoryMb(2048)
                .timestamp(workerClock)
                .build();
    }

    @Test
    @DisplayName("handleHeartbeat -> lastHeartbeat uses the CP clock even when the worker clock is 5 min behind")
    void heartbeat_usesControlPlaneClockNotWorkerClock() {
        Instant before = Instant.now();

        // the worker clock is 5 minutes behind - the old code wrote it to
        // lastHeartbeat and WorkerHealthScheduler counted the worker OFFLINE instantly.
        workerService.handleHeartbeat(heartbeat(Instant.now().minus(Duration.ofMinutes(5))));

        assertThat(state.getLastHeartbeat())
                .isNotNull()
                .isBetween(before, Instant.now().plusSeconds(1));
    }

    @Test
    @DisplayName("handleHeartbeat -> lastHeartbeat uses the CP clock even when the timestamp is null")
    void heartbeat_handlesNullTimestamp() {
        Instant before = Instant.now();

        workerService.handleHeartbeat(heartbeat(null));

        assertThat(state.getLastHeartbeat())
                .isNotNull()
                .isBetween(before, Instant.now().plusSeconds(1));
    }

    @Test
    @DisplayName("handleHeartbeat -> MAINTENANCE is not overridden by heartbeats, metrics still update")
    void heartbeat_preservesMaintenanceStatus() {
        state.setStatus(WorkerState.NodeStatus.MAINTENANCE);

        workerService.handleHeartbeat(heartbeat(Instant.now()));

        assertThat(state.getStatus()).isEqualTo(WorkerState.NodeStatus.MAINTENANCE);
        assertThat(state.getCpuUsagePercent()).isEqualTo(20);
        assertThat(state.getUsedMemoryMb()).isEqualTo(2048);
        assertThat(state.getLastHeartbeat()).isCloseTo(Instant.now(), within(2, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("handleHeartbeat -> normal load values yield ACTIVE")
    void heartbeat_setsActiveOnNormalLoad() {
        state.setStatus(WorkerState.NodeStatus.OVERLOADED);

        workerService.handleHeartbeat(heartbeat(Instant.now()));

        assertThat(state.getStatus()).isEqualTo(WorkerState.NodeStatus.ACTIVE);
    }

    @Test
    @DisplayName("handleHeartbeat -> exceeding the CPU threshold yields OVERLOADED")
    void heartbeat_setsOverloadedOnHighCpu() {
        WorkerHeartbeatRequest request = WorkerHeartbeatRequest.builder()
                .workerId(node.getId())
                .cpuUsagePercent(95)
                .usedMemoryMb(1024)
                .timestamp(Instant.now())
                .build();

        workerService.handleHeartbeat(request);

        assertThat(state.getStatus()).isEqualTo(WorkerState.NodeStatus.OVERLOADED);
    }
}
