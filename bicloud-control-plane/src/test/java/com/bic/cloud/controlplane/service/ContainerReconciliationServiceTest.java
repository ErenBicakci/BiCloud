package com.bic.cloud.controlplane.service;

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
        // Regression: stats collection used to delay the snapshot, so freshly
        // deployed containers were missing from the stale list and got treated
        // as zombies, kicking off a restart storm.
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
        // Regression: when clock skew made a worker briefly OFFLINE, its live
        // containers were marked FAILED and stayed FAILED after it came back
        // ACTIVE, causing self-healing to spawn duplicates.
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

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

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

        // createdAt is set by @PrePersist; tests set it via reflection
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
