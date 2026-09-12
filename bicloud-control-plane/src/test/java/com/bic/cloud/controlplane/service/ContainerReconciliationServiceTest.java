package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.client.WorkerHttpClient;
import com.bic.cloud.controlplane.model.ContainerInstance;
import com.bic.cloud.controlplane.model.ProjectImage;
import com.bic.cloud.controlplane.model.UserProject;
import com.bic.cloud.controlplane.model.WorkerNode;
import com.bic.cloud.controlplane.repository.ContainerInstanceRepository;
import com.bic.cloud.controlplane.repository.WorkerNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Regression tests for incidents we actually hit:
 *  - stale snapshots mistaking new containers for zombies -> grace period
 *  - live containers stuck FAILED after a worker flap -> false-FAILED recovery
 *  - real zombie records getting marked FAILED (base behaviour)
 */
@ExtendWith(MockitoExtension.class)
class ContainerReconciliationServiceTest {

    @Mock
    private WorkerNodeRepository workerNodeRepository;

    @Mock
    private ContainerInstanceRepository containerInstanceRepository;

    @Mock
    private GatewayNotificationService gatewayNotificationService;

    @Mock
    private WorkerHttpClient workerHttpClient;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private ContainerReconciliationService reconciliationService;

    private WorkerNode worker;

    @BeforeEach
    void setUp() {
        worker = WorkerNode.builder()
                .id(UUID.randomUUID())
                .workerName("test-worker")
                .totalCpuCores(4)
                .totalMemoryMb(4096)
                .serverPort(8081)
                .build();

        when(workerNodeRepository.findById(worker.getId())).thenReturn(Optional.of(worker));
    }

    @Test
    @DisplayName("reconcile -> an old RUNNING record missing from the snapshot is a zombie, marked FAILED")
    void reconcile_marksMissingContainerAsFailed() {
        ContainerInstance zombie = buildInstance("dead-container-id",
                ContainerInstance.InstanceStatus.RUNNING, Instant.now().minusSeconds(300));

        when(containerInstanceRepository.findRunningByWorkerNodeId(worker.getId()))
                .thenReturn(List.of(zombie));

        reconciliationService.reconcile(worker.getId(), List.of("some-other-container"));

        assertThat(zombie.getStatus()).isEqualTo(ContainerInstance.InstanceStatus.FAILED);
        verify(containerInstanceRepository).save(zombie);
    }

    @Test
    @DisplayName("reconcile -> a RUNNING record younger than 60s is not FAILED even if missing from the snapshot (grace period)")
    void reconcile_skipsRecentInstancesWithinGracePeriod() {
        ContainerInstance fresh = buildInstance("brand-new-container",
                ContainerInstance.InstanceStatus.RUNNING, Instant.now().minusSeconds(5));

        when(containerInstanceRepository.findRunningByWorkerNodeId(worker.getId()))
                .thenReturn(List.of(fresh));

        reconciliationService.reconcile(worker.getId(), List.of());

        assertThat(fresh.getStatus()).isEqualTo(ContainerInstance.InstanceStatus.RUNNING);
        verify(containerInstanceRepository, never()).save(fresh);
    }

    @Test
    @DisplayName("reconcile -> a record FAILED in the DB but running per the snapshot is recovered to RUNNING and re-registered with the gateway")
    void reconcile_recoversFalseFailedContainer() {
        ContainerInstance falseFailed = buildInstance("alive-container-id",
                ContainerInstance.InstanceStatus.FAILED, Instant.now().minusSeconds(600));

        when(containerInstanceRepository.findByWorkerNode_IdAndStatus(
                worker.getId(), ContainerInstance.InstanceStatus.FAILED))
                .thenReturn(List.of(falseFailed));
        when(containerInstanceRepository.findRunningByWorkerNodeId(worker.getId()))
                .thenReturn(List.of());

        reconciliationService.reconcile(worker.getId(), List.of("alive-container-id"));

        assertThat(falseFailed.getStatus()).isEqualTo(ContainerInstance.InstanceStatus.RUNNING);
        verify(containerInstanceRepository).save(falseFailed);
        verify(gatewayNotificationService).register(falseFailed);
    }

    @Test
    @DisplayName("reconcile -> a FAILED record also missing from the snapshot is not recovered")
    void reconcile_doesNotRecoverGenuinelyFailedContainer() {
        ContainerInstance trulyFailed = buildInstance("gone-container-id",
                ContainerInstance.InstanceStatus.FAILED, Instant.now().minusSeconds(600));

        when(containerInstanceRepository.findByWorkerNode_IdAndStatus(
                worker.getId(), ContainerInstance.InstanceStatus.FAILED))
                .thenReturn(List.of(trulyFailed));
        when(containerInstanceRepository.findRunningByWorkerNodeId(worker.getId()))
                .thenReturn(List.of());

        reconciliationService.reconcile(worker.getId(), List.of());

        assertThat(trulyFailed.getStatus()).isEqualTo(ContainerInstance.InstanceStatus.FAILED);
        verify(gatewayNotificationService, never()).register(any());
    }

    @Test
    @DisplayName("reconcile -> nothing changes when the snapshot matches the DB")
    void reconcile_noChangesWhenInSync() {
        ContainerInstance healthy = buildInstance("healthy-container-id",
                ContainerInstance.InstanceStatus.RUNNING, Instant.now().minusSeconds(300));

        when(containerInstanceRepository.findRunningByWorkerNodeId(worker.getId()))
                .thenReturn(List.of(healthy));

        reconciliationService.reconcile(worker.getId(), List.of("healthy-container-id"));

        assertThat(healthy.getStatus()).isEqualTo(ContainerInstance.InstanceStatus.RUNNING);
        verify(containerInstanceRepository, never()).save(any());
    }

    @Test
    @DisplayName("reconcile -> STOPPING container confirmed gone in snapshot is marked STOPPED")
    void reconcile_stoppingContainerConfirmedGone_markedStopped() {
        ContainerInstance stopping = buildInstance("stopping-container-id",
                ContainerInstance.InstanceStatus.STOPPING, Instant.now().minusSeconds(100));

        when(containerInstanceRepository.findByWorkerNode_IdAndStatus(
                worker.getId(), ContainerInstance.InstanceStatus.FAILED))
                .thenReturn(List.of());
        when(containerInstanceRepository.findByWorkerNode_IdAndStatus(
                worker.getId(), ContainerInstance.InstanceStatus.STOPPING))
                .thenReturn(List.of(stopping));
        when(containerInstanceRepository.findByWorkerNode_IdAndStatus(
                worker.getId(), ContainerInstance.InstanceStatus.STOPPED))
                .thenReturn(List.of());
        when(containerInstanceRepository.findRunningByWorkerNodeId(worker.getId()))
                .thenReturn(List.of());

        reconciliationService.reconcile(worker.getId(), List.of("other-container"));

        assertThat(stopping.getStatus()).isEqualTo(ContainerInstance.InstanceStatus.STOPPED);
        verify(containerInstanceRepository).save(stopping);
        verify(workerHttpClient, never()).stopAndRemoveContainer(any(), any());
    }

    @Test
    @DisplayName("reconcile -> STOPPING container still running in snapshot retries stopAndRemoveContainer")
    void reconcile_stoppingContainerStillPresent_retriesStopAndRemove() {
        ContainerInstance stuckStopping = buildInstance("stuck-container-id",
                ContainerInstance.InstanceStatus.STOPPING, Instant.now().minusSeconds(100));

        when(containerInstanceRepository.findByWorkerNode_IdAndStatus(
                worker.getId(), ContainerInstance.InstanceStatus.FAILED))
                .thenReturn(List.of());
        when(containerInstanceRepository.findByWorkerNode_IdAndStatus(
                worker.getId(), ContainerInstance.InstanceStatus.STOPPING))
                .thenReturn(List.of(stuckStopping));
        when(containerInstanceRepository.findByWorkerNode_IdAndStatus(
                worker.getId(), ContainerInstance.InstanceStatus.STOPPED))
                .thenReturn(List.of());
        when(containerInstanceRepository.findRunningByWorkerNodeId(worker.getId()))
                .thenReturn(List.of());

        reconciliationService.reconcile(worker.getId(), List.of("stuck-container-id"));

        assertThat(stuckStopping.getStatus()).isEqualTo(ContainerInstance.InstanceStatus.STOPPING);
        verify(workerHttpClient).stopAndRemoveContainer(worker, "stuck-container-id");
    }

    @Test
    @DisplayName("reconcile -> STOPPED container still running in snapshot is killed as zombie and deregistered")
    void reconcile_stoppedContainerStillPresent_stopsZombieAndDeregisters() {
        ContainerInstance zombieStopped = buildInstance("zombie-stopped-id",
                ContainerInstance.InstanceStatus.STOPPED, Instant.now().minusSeconds(200));

        when(containerInstanceRepository.findByWorkerNode_IdAndStatus(
                worker.getId(), ContainerInstance.InstanceStatus.FAILED))
                .thenReturn(List.of());
        when(containerInstanceRepository.findByWorkerNode_IdAndStatus(
                worker.getId(), ContainerInstance.InstanceStatus.STOPPING))
                .thenReturn(List.of());
        when(containerInstanceRepository.findByWorkerNode_IdAndStatus(
                worker.getId(), ContainerInstance.InstanceStatus.STOPPED))
                .thenReturn(List.of(zombieStopped));
        when(containerInstanceRepository.findRunningByWorkerNodeId(worker.getId()))
                .thenReturn(List.of());

        reconciliationService.reconcile(worker.getId(), List.of("zombie-stopped-id"));

        verify(gatewayNotificationService).deregister(zombieStopped);
        verify(workerHttpClient).stopAndRemoveContainer(worker, "zombie-stopped-id");
    }

    private ContainerInstance buildInstance(String dockerId,
                                            ContainerInstance.InstanceStatus status,
                                            Instant createdAt) {
        UserProject project = UserProject.builder().name("test-project").build();
        ProjectImage image = ProjectImage.builder()
                .id(1L)
                .project(project)
                .serviceName("web")
                .imageName("nginx:latest")
                .desiredReplicas(1)
                .containerPort(80)
                .build();

        ContainerInstance instance = ContainerInstance.builder()
                .id(UUID.randomUUID())
                .dockerContainerId(dockerId)
                .projectImage(image)
                .workerNode(worker)
                .status(status)
                .build();

        setCreatedAt(instance, createdAt);
        return instance;
    }

    private void setCreatedAt(ContainerInstance instance, Instant createdAt) {
        try {
            Field field = ContainerInstance.class.getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(instance, createdAt);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
