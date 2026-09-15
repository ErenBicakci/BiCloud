package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.dto.WorkerHeartbeatRequest;
import com.bic.cloud.controlplane.dto.WorkerRegisterRequest;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerServiceLifecycleTest {

    @Mock
    private WorkerNodeRepository nodeRepository;

    @Mock
    private WorkerStateRepository stateRepository;

    @Mock
    private ContainerInstanceRepository containerInstanceRepository;

    @Mock
    private WorkerScoringService scoringService;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private WorkerService workerService;

    private WorkerNode node;
    private WorkerState state;

    @BeforeEach
    void setUp() {
        node = WorkerNode.builder()
                .id(UUID.randomUUID())
                .workerName("worker-1")
                .totalCpuCores(4)
                .totalMemoryMb(8192)
                .serverPort(8081)
                .build();

        state = WorkerState.builder()
                .worker(node)
                .status(WorkerState.NodeStatus.ACTIVE)
                .build();

        when(stateRepository.findById(node.getId())).thenReturn(Optional.of(state));
        when(containerInstanceRepository.findRunningByWorkerNodeId(node.getId())).thenReturn(List.of());
    }

    @Test
    @DisplayName("graceful shutdown marks the worker OFFLINE and it returns ACTIVE after restart")
    void gracefulRestartReturnsToActive() {
        workerService.deregister(node.getId());
        assertThat(state.getStatus()).isEqualTo(WorkerState.NodeStatus.OFFLINE);

        restartWorker();

        assertThat(state.getStatus()).isEqualTo(WorkerState.NodeStatus.ACTIVE);
    }

    @Test
    @DisplayName("admin maintenance survives a graceful restart")
    void adminMaintenanceSurvivesRestart() {
        state.setStatus(WorkerState.NodeStatus.MAINTENANCE);

        workerService.deregister(node.getId());
        restartWorker();

        assertThat(state.getStatus()).isEqualTo(WorkerState.NodeStatus.MAINTENANCE);
    }

    private void restartWorker() {
        when(nodeRepository.findByIdForUpdate(node.getId())).thenReturn(Optional.of(node));

        workerService.register(WorkerRegisterRequest.builder()
                .workerId(node.getId())
                .workerName("worker-1")
                .ipAddress("10.0.0.5")
                .totalCpuCores(4)
                .totalMemoryMb(8192)
                .serverPort(8081)
                .build(), "10.0.0.5");

        workerService.handleHeartbeat(WorkerHeartbeatRequest.builder()
                .workerId(node.getId())
                .cpuUsagePercent(10)
                .usedMemoryMb(1024)
                .build());
    }
}
