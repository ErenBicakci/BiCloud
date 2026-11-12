package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.client.WorkerHttpClient;
import com.bic.cloud.controlplane.dto.ContainerStatusUpdateRequest;
import com.bic.cloud.controlplane.model.ContainerInstance;
import com.bic.cloud.controlplane.model.ContainerInstance.InstanceStatus;
import com.bic.cloud.controlplane.model.ProjectImage;
import com.bic.cloud.controlplane.model.UserProject;
import com.bic.cloud.controlplane.model.WorkerNode;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for a crash-recovery race observed live (SIGKILL on a replica):
 *
 *  1. the worker's health check reports the container as exited -> FAILED
 *  2. a container snapshot listed ~12 s EARLIER (stats are collected one
 *     container at a time between listing and sending) arrives afterwards
 *     and still contains the dead container
 *  3. false-FAILED recovery trusted that stale snapshot, flipped the record
 *     back to RUNNING and re-registered the dead IP with the gateway
 *
 * Traffic kept flowing to the dead replica and the replacement only started
 * ~80 s after the crash. Both services are exercised together because the bug
 * lives in the interaction between the status report and the snapshot.
 */
@ExtendWith(MockitoExtension.class)
class CrashedContainerStaleSnapshotTest {

    @Mock private WorkerNodeRepository workerNodeRepository;
    @Mock private WorkerStateRepository workerStateRepository;
    @Mock private ContainerInstanceRepository containerInstanceRepository;
    @Mock private WorkerScoringService scoringService;
    @Mock private AuditService auditService;
    @Mock private GatewayNotificationService gatewayNotificationService;
    @Mock private WorkerHttpClient workerHttpClient;

    @InjectMocks private WorkerService workerService;
    @InjectMocks private ContainerReconciliationService reconciliationService;

    private WorkerNode worker;
    private ContainerInstance replica;

    @BeforeEach
    void setUp() {
        worker = WorkerNode.builder().id(UUID.randomUUID()).workerName("worker-1").build();

        UserProject project = new UserProject();
        project.setName("demo");
        ProjectImage image = new ProjectImage();
        image.setServiceName("bench");
        image.setProject(project);

        replica = ContainerInstance.builder()
                .id(UUID.randomUUID())
                .dockerContainerId("dead-replica")
                .workerNode(worker)
                .projectImage(image)
                .containerIp("172.21.0.6")
                .status(InstanceStatus.RUNNING)
                .startedAt(Instant.now().minusSeconds(15))
                .build();

        when(containerInstanceRepository.findByDockerContainerId("dead-replica")).thenReturn(Optional.of(replica));
    }

    @Test
    @DisplayName("a container the worker reported as exited is not resurrected by a stale snapshot")
    void staleSnapshotDoesNotResurrectExitedContainer() {
        // 1. health check: the container has exited
        workerService.handleContainerStatusUpdate(exitedReport());
        assertThat(replica.getStatus()).isEqualTo(InstanceStatus.FAILED);

        // 2. a snapshot taken before the crash arrives afterwards
        when(workerNodeRepository.findById(worker.getId())).thenReturn(Optional.of(worker));
        when(containerInstanceRepository.findByWorkerNode_IdAndStatus(worker.getId(), InstanceStatus.FAILED))
                .thenReturn(List.of(replica));
        reconciliationService.reconcile(worker.getId(), List.of("dead-replica"));

        // 3. it must stay FAILED, so self-healing replaces it and no traffic goes to it
        assertThat(replica.getStatus()).isEqualTo(InstanceStatus.FAILED);
        verify(gatewayNotificationService, never()).register(replica);
    }

    @Test
    @DisplayName("an exit report removes the replica from the gateway immediately")
    void exitReportDeregistersFromGateway() {
        workerService.handleContainerStatusUpdate(exitedReport());

        verify(gatewayNotificationService).deregister(replica);
    }

    private ContainerStatusUpdateRequest exitedReport() {
        return ContainerStatusUpdateRequest.builder()
                .workerId(worker.getId())
                .dockerContainerId("dead-replica")
                .status("FAILED")
                .message("Container state: exited")
                .build();
    }
}
