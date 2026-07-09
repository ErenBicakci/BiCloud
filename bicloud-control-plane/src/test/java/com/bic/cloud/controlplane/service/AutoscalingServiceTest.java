package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.model.ContainerInstance;
import com.bic.cloud.controlplane.model.ProjectImage;
import com.bic.cloud.controlplane.model.UserProject;
import com.bic.cloud.controlplane.model.WorkerNode;
import com.bic.cloud.controlplane.repository.ContainerInstanceRepository;
import com.bic.cloud.controlplane.repository.ProjectImageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutoscalingServiceTest {

    @Mock
    private ProjectImageRepository projectImageRepository;

    @Mock
    private ContainerInstanceRepository containerInstanceRepository;

    @Mock
    private ContainerMetricsService containerMetricsService;

    @Mock
    private DeploymentService deploymentService;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private AutoscalingService autoscalingService;

    @Test
    @DisplayName("reconcile -> scales up by one replica when 4-round average CPU is above target")
    void reconcile_scalesUpWhenCpuAboveTarget() {
        ProjectImage image = buildImage(2);
        List<ContainerInstance> running = List.of(
                buildInstance(image, "container-1"),
                buildInstance(image, "container-2"));

        stubStableRunningService(image, running);
        when(containerMetricsService.getLatest("container-1")).thenReturn(metric(85), metric(85), metric(85), metric(85));
        when(containerMetricsService.getLatest("container-2")).thenReturn(metric(95), metric(95), metric(95), metric(95));

        runReconcileRounds(3);

        assertThat(image.getDesiredReplicas()).isEqualTo(2);
        verify(deploymentService, never()).scaleAsync(anyLong(), anyInt());

        autoscalingService.reconcileAll();

        assertThat(image.getDesiredReplicas()).isEqualTo(3);
        assertThat(image.getLastAutoscaledAt()).isNotNull();
        verify(projectImageRepository).save(image);
        verify(deploymentService).scaleAsync(1L, 3);
    }

    @Test
    @DisplayName("reconcile -> scales down by one replica when 4-round average CPU is below the scale-down threshold")
    void reconcile_scalesDownWhenCpuBelowThreshold() {
        ProjectImage image = buildImage(3);
        image.setLastAutoscaledAt(Instant.now().minusSeconds(3600));
        List<ContainerInstance> running = List.of(
                buildInstance(image, "container-1"),
                buildInstance(image, "container-2"),
                buildInstance(image, "container-3"));

        stubStableRunningService(image, running);
        when(containerMetricsService.getLatest("container-1")).thenReturn(metric(10), metric(10), metric(10), metric(10));
        when(containerMetricsService.getLatest("container-2")).thenReturn(metric(15), metric(15), metric(15), metric(15));
        when(containerMetricsService.getLatest("container-3")).thenReturn(metric(20), metric(20), metric(20), metric(20));

        runReconcileRounds(3);

        assertThat(image.getDesiredReplicas()).isEqualTo(3);
        verify(deploymentService, never()).scaleAsync(anyLong(), anyInt());

        autoscalingService.reconcileAll();

        assertThat(image.getDesiredReplicas()).isEqualTo(2);
        verify(projectImageRepository).save(image);
        verify(deploymentService).scaleAsync(1L, 2);
    }

    @Test
    @DisplayName("reconcile -> skips scaling while a replica is pending")
    void reconcile_skipsWhenPendingReplicasExist() {
        ProjectImage image = buildImage(2);

        when(projectImageRepository.findAllWithProject()).thenReturn(List.of(image));
        when(containerInstanceRepository.countByProjectImageAndStatus(
                image, ContainerInstance.InstanceStatus.PENDING)).thenReturn(1L);

        autoscalingService.reconcileAll();

        verify(containerInstanceRepository, never()).findByProjectImageAndStatus(any(), any());
        verify(projectImageRepository, never()).save(any());
        verify(deploymentService, never()).scaleAsync(anyLong(), anyInt());
    }

    @Test
    @DisplayName("reconcile -> honors the scale-up cooldown")
    void reconcile_skipsScaleUpDuringCooldown() {
        ProjectImage image = buildImage(2);
        image.setLastAutoscaledAt(Instant.now());
        List<ContainerInstance> running = List.of(
                buildInstance(image, "container-1"),
                buildInstance(image, "container-2"));

        stubStableRunningService(image, running);
        when(containerMetricsService.getLatest("container-1")).thenReturn(metric(95), metric(95), metric(95), metric(95));
        when(containerMetricsService.getLatest("container-2")).thenReturn(metric(95), metric(95), metric(95), metric(95));

        runReconcileRounds(4);

        assertThat(image.getDesiredReplicas()).isEqualTo(2);
        verify(projectImageRepository, never()).save(any());
        verify(deploymentService, never()).scaleAsync(anyLong(), anyInt());
    }

    private void stubStableRunningService(ProjectImage image, List<ContainerInstance> running) {
        when(projectImageRepository.findAllWithProject()).thenReturn(List.of(image));
        when(containerInstanceRepository.countByProjectImageAndStatus(
                image, ContainerInstance.InstanceStatus.PENDING)).thenReturn(0L);
        when(containerInstanceRepository.findByProjectImageAndStatus(
                image, ContainerInstance.InstanceStatus.RUNNING)).thenReturn(running);
    }

    private void runReconcileRounds(int rounds) {
        for (int i = 0; i < rounds; i++) {
            autoscalingService.reconcileAll();
        }
    }

    private ProjectImage buildImage(int desiredReplicas) {
        UserProject project = UserProject.builder()
                .id(10L)
                .name("test-project")
                .build();

        return ProjectImage.builder()
                .id(1L)
                .project(project)
                .serviceName("web")
                .imageName("nginx:latest")
                .desiredReplicas(desiredReplicas)
                .autoscalingEnabled(true)
                .minReplicas(1)
                .maxReplicas(4)
                .targetCpuPercent(70)
                .scaleDownCpuPercent(30)
                .scaleUpCooldownSeconds(60)
                .scaleDownCooldownSeconds(300)
                .cpuLimit(1.0)
                .containerPort(80)
                .build();
    }

    private ContainerInstance buildInstance(ProjectImage image, String dockerId) {
        return ContainerInstance.builder()
                .id(UUID.randomUUID())
                .projectImage(image)
                .workerNode(WorkerNode.builder().id(UUID.randomUUID()).workerName("worker-1").build())
                .dockerContainerId(dockerId)
                .status(ContainerInstance.InstanceStatus.RUNNING)
                .build();
    }

    private ContainerMetricsService.MetricPoint metric(double cpuPercent) {
        return new ContainerMetricsService.MetricPoint(Instant.now(), cpuPercent, 0, 0);
    }
}
