package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.dto.ContainerStatusUpdateRequest;
import com.bic.cloud.controlplane.model.ContainerInstance;
import com.bic.cloud.controlplane.model.ContainerInstance.InstanceStatus;
import com.bic.cloud.controlplane.model.WorkerNode;
import com.bic.cloud.controlplane.repository.ContainerInstanceRepository;
import com.bic.cloud.controlplane.repository.WorkerNodeRepository;
import com.bic.cloud.controlplane.repository.WorkerStateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerServiceContainerStatusTest {

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

    private final WorkerNode worker = WorkerNode.builder().id(UUID.randomUUID()).workerName("worker-1").build();

    @Test
    @DisplayName("an exited RUNNING container is marked FAILED")
    void failedReportMarksRunningContainerFailed() {
        ContainerInstance instance = instance(InstanceStatus.RUNNING);

        workerService.handleContainerStatusUpdate(failedReport(worker.getId()));

        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.FAILED);
        verify(containerInstanceRepository).save(instance);
    }

    @ParameterizedTest
    @EnumSource(value = InstanceStatus.class, names = {"STOPPING", "STOPPED"})
    @DisplayName("a container stopped on purpose is not turned into FAILED")
    void reportDoesNotOverrideStopRequest(InstanceStatus status) {
        ContainerInstance instance = instance(status);

        workerService.handleContainerStatusUpdate(failedReport(worker.getId()));

        assertThat(instance.getStatus()).isEqualTo(status);
        verify(containerInstanceRepository, never()).save(any());
    }

    @Test
    @DisplayName("a worker cannot change the status of another worker's container")
    void reportFromAnotherWorkerIsIgnored() {
        ContainerInstance instance = instance(InstanceStatus.RUNNING);

        workerService.handleContainerStatusUpdate(failedReport(UUID.randomUUID()));

        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        verify(containerInstanceRepository, never()).save(any());
    }

    private ContainerInstance instance(InstanceStatus status) {
        ContainerInstance instance = ContainerInstance.builder()
                .id(UUID.randomUUID())
                .dockerContainerId("abc")
                .workerNode(worker)
                .status(status)
                .build();
        when(containerInstanceRepository.findByDockerContainerId("abc")).thenReturn(Optional.of(instance));
        return instance;
    }

    private ContainerStatusUpdateRequest failedReport(UUID workerId) {
        return ContainerStatusUpdateRequest.builder()
                .workerId(workerId)
                .dockerContainerId("abc")
                .status("FAILED")
                .message("Container state: exited")
                .build();
    }
}
