package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.client.WorkerHttpClient;
import com.bic.cloud.controlplane.mapper.WorkerRequestMapper;
import com.bic.cloud.controlplane.dto.WorkerContainerCreateRequest;
import com.bic.cloud.controlplane.dto.WorkerContainerCreateResponse;
import com.bic.cloud.controlplane.model.ContainerInstance;
import com.bic.cloud.controlplane.model.ProjectImage;
import com.bic.cloud.controlplane.model.UserProject;
import com.bic.cloud.controlplane.model.WorkerNode;
import com.bic.cloud.controlplane.repository.ContainerInstanceRepository;
import com.bic.cloud.controlplane.repository.ProjectImageRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeploymentServiceTest {

    @Mock
    private WorkerHttpClient workerHttpClient;

    @Mock
    private WorkerRequestMapper workerRequestMapper;

    @Mock
    private WorkerScoringService scoringService;

    @Mock
    private ContainerInstanceRepository containerInstanceRepository;

    @Mock
    private ProjectImageRepository projectImageRepository;

    @Mock
    private ServiceDiscoveryService serviceDiscoveryService;

    @Mock
    private GatewayNotificationService gatewayNotificationService;

    @Mock
    private WorkerStateRepository workerStateRepository;

    @InjectMocks
    private DeploymentService deploymentService;

    private UserProject project;
    private ProjectImage projectImage;
    private WorkerNode worker;

    @BeforeEach
    void setUp() {
        project = UserProject.builder()
                .id(1L)
                .name("test-project")
                .build();

        projectImage = ProjectImage.builder()
                .id(10L)
                .project(project)
                .serviceName("web")
                .imageName("nginx:latest")
                .desiredReplicas(2)
                .containerPort(80)
                .cpuLimit(200.0)
                .memoryLimitMb(256)
                .build();

        worker = WorkerNode.builder()
                .id(UUID.randomUUID())
                .workerName("worker-1")
                .totalCpuCores(4)
                .totalMemoryMb(4096)
                .serverPort(8081)
                .build();
    }

    @Test
    @DisplayName("undeployProject -> preserves desiredReplicas, sets stoppedByUser=true, and stops all active instances")
    void undeployProject_stopsAllActiveInstances() {
        when(projectImageRepository.findByProject_Id(1L)).thenReturn(List.of(projectImage));

        ContainerInstance running = ContainerInstance.builder()
                .id(UUID.randomUUID())
                .dockerContainerId("docker-running-1")
                .projectImage(projectImage)
                .workerNode(worker)
                .status(ContainerInstance.InstanceStatus.RUNNING)
                .build();

        ContainerInstance pending = ContainerInstance.builder()
                .id(UUID.randomUUID())
                .dockerContainerId(null)
                .projectImage(projectImage)
                .workerNode(worker)
                .status(ContainerInstance.InstanceStatus.PENDING)
                .build();

        when(containerInstanceRepository.findActiveByProjectId(1L))
                .thenReturn(List.of(running, pending));

        deploymentService.undeployProject(1L);

        assertThat(projectImage.isStoppedByUser()).isTrue();
        assertThat(projectImage.getDesiredReplicas()).isEqualTo(2);
        verify(projectImageRepository).save(projectImage);

        verify(gatewayNotificationService).deregister(running);
        verify(workerHttpClient).stopAndRemoveContainer(worker, "docker-running-1");
        assertThat(running.getStatus()).isEqualTo(ContainerInstance.InstanceStatus.STOPPED);

        assertThat(pending.getStatus()).isEqualTo(ContainerInstance.InstanceStatus.STOPPED);
        verify(workerHttpClient, never()).stopAndRemoveContainer(eq(worker), isNull());
    }

    @Test
    @DisplayName("stopAndRemove -> on worker communication failure, instance remains in STOPPING status")
    void stopAndRemove_onWorkerFailure_remainsInStopping() {
        ContainerInstance running = ContainerInstance.builder()
                .id(UUID.randomUUID())
                .dockerContainerId("docker-err-1")
                .projectImage(projectImage)
                .workerNode(worker)
                .status(ContainerInstance.InstanceStatus.RUNNING)
                .build();

        doThrow(new RuntimeException("Worker unreachable"))
                .when(workerHttpClient).stopAndRemoveContainer(worker, "docker-err-1");

        deploymentService.stopAndRemove(running);

        verify(gatewayNotificationService).deregister(running);

        assertThat(running.getStatus()).isEqualTo(ContainerInstance.InstanceStatus.STOPPING);
        verify(containerInstanceRepository).save(running);
    }

    @Test
    @DisplayName("deploy -> stops early when projectImage is cancelled before next replica")
    void deploy_stopsEarlyWhenCancelled() {
        when(containerInstanceRepository.countByProjectImageAndStatus(projectImage, ContainerInstance.InstanceStatus.RUNNING))
                .thenReturn(0L);
        when(containerInstanceRepository.countByProjectImageAndStatus(projectImage, ContainerInstance.InstanceStatus.PENDING))
                .thenReturn(0L);

        ProjectImage cancelledImage = ProjectImage.builder()
                .id(10L)
                .serviceName("web")
                .desiredReplicas(0)
                .stoppedByUser(true)
                .build();
        when(projectImageRepository.findByIdForDeployment(10L)).thenReturn(Optional.of(cancelledImage));

        deploymentService.deploy(projectImage);

        verify(scoringService, never()).selectAndReserveWorker(any(), anyInt(), anyLong());
        verify(workerHttpClient, never()).createContainer(any(), any());
    }

    @Test
    @DisplayName("scale -> scaling down removes active instances across RUNNING, PENDING, STOPPING")
    void scale_scaleDownRemovesActiveInstances() {
        when(containerInstanceRepository.countByProjectImageAndStatus(projectImage, ContainerInstance.InstanceStatus.RUNNING))
                .thenReturn(2L);
        when(containerInstanceRepository.countByProjectImageAndStatus(projectImage, ContainerInstance.InstanceStatus.PENDING))
                .thenReturn(0L);

        ContainerInstance inst1 = ContainerInstance.builder()
                .id(UUID.randomUUID())
                .dockerContainerId("c-1")
                .projectImage(projectImage)
                .workerNode(worker)
                .status(ContainerInstance.InstanceStatus.RUNNING)
                .build();

        when(containerInstanceRepository.findByProjectImageAndStatusIn(eq(projectImage), anyList()))
                .thenReturn(List.of(inst1));

        deploymentService.scale(projectImage, 1);

        verify(gatewayNotificationService).deregister(inst1);
        verify(workerHttpClient).stopAndRemoveContainer(worker, "c-1");
        assertThat(inst1.getStatus()).isEqualTo(ContainerInstance.InstanceStatus.STOPPED);
    }

    @Test
    @DisplayName("deploy -> capacity deficit breaks loop and increments failure backoff")
    void deploy_capacityDeficit_updatesBackoff() {
        when(containerInstanceRepository.countByProjectImageAndStatus(projectImage, ContainerInstance.InstanceStatus.RUNNING))
                .thenReturn(0L);
        when(containerInstanceRepository.countByProjectImageAndStatus(projectImage, ContainerInstance.InstanceStatus.PENDING))
                .thenReturn(0L);

        when(projectImageRepository.findByIdForDeployment(10L))
                .thenReturn(Optional.of(projectImage));

        WorkerContainerCreateRequest createReq = new WorkerContainerCreateRequest();
        createReq.setCpuLimitMillicores(200);
        createReq.setMemoryLimitMb(256);
        when(workerRequestMapper.toWorkerRequest(projectImage)).thenReturn(createReq);

        when(scoringService.selectAndReserveWorker(eq(projectImage), anyInt(), anyLong()))
                .thenReturn(Optional.empty());

        deploymentService.deploy(projectImage);

        verify(projectImageRepository, atLeastOnce()).save(projectImage);
        assertThat(projectImage.getConsecutiveDeployFailures()).isEqualTo(1);
        verify(workerHttpClient, never()).createContainer(any(), any());
    }

    @Test
    @DisplayName("deploy -> post-creation cancellation cleans up container immediately via stopAndRemoveInstance")
    void deploy_postCreationCancellation_cleansUpContainer() {
        when(containerInstanceRepository.countByProjectImageAndStatus(projectImage, ContainerInstance.InstanceStatus.RUNNING))
                .thenReturn(0L);
        when(containerInstanceRepository.countByProjectImageAndStatus(projectImage, ContainerInstance.InstanceStatus.PENDING))
                .thenReturn(0L);

        ProjectImage activeImage = ProjectImage.builder()
                .id(10L)
                .project(project)
                .serviceName("web")
                .imageName("nginx:latest")
                .desiredReplicas(2)
                .stoppedByUser(false)
                .build();

        ProjectImage cancelledImage = ProjectImage.builder()
                .id(10L)
                .project(project)
                .serviceName("web")
                .imageName("nginx:latest")
                .desiredReplicas(2)
                .stoppedByUser(true)
                .build();

        when(projectImageRepository.findByIdForDeployment(10L))
                .thenReturn(Optional.of(activeImage), Optional.of(cancelledImage));

        WorkerContainerCreateRequest createReq = new WorkerContainerCreateRequest();
        createReq.setCpuLimitMillicores(200);
        createReq.setMemoryLimitMb(256);
        when(workerRequestMapper.toWorkerRequest(activeImage)).thenReturn(createReq);

        ContainerInstance pendingInstance = ContainerInstance.builder()
                .id(UUID.randomUUID())
                .projectImage(activeImage)
                .workerNode(worker)
                .status(ContainerInstance.InstanceStatus.PENDING)
                .build();

        when(scoringService.selectAndReserveWorker(eq(activeImage), anyInt(), anyLong()))
                .thenReturn(Optional.of(pendingInstance));

        WorkerContainerCreateResponse createResp = WorkerContainerCreateResponse.builder()
                .containerId("docker-cancelled-1")
                .containerIp("172.18.0.5")
                .build();
        when(workerHttpClient.createContainer(eq(worker), any())).thenReturn(createResp);

        deploymentService.deploy(projectImage);

        verify(workerHttpClient).stopAndRemoveContainer(worker, "docker-cancelled-1");
        verify(gatewayNotificationService).deregister(pendingInstance);
        assertThat(pendingInstance.getStatus()).isEqualTo(ContainerInstance.InstanceStatus.STOPPED);
    }
}

