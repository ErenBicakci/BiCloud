package com.bic.cloud.controlplane.scheduler;

import com.bic.cloud.controlplane.model.ContainerInstance;
import com.bic.cloud.controlplane.model.ProjectImage;
import com.bic.cloud.controlplane.model.UserProject;
import com.bic.cloud.controlplane.repository.ContainerInstanceRepository;
import com.bic.cloud.controlplane.repository.ProjectImageRepository;
import com.bic.cloud.controlplane.service.DeploymentService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SelfHealingSchedulerTest {

    @Mock
    private ProjectImageRepository projectImageRepository;

    @Mock
    private ContainerInstanceRepository containerInstanceRepository;

    @Mock
    private DeploymentService deploymentService;

    @Mock
    private com.bic.cloud.controlplane.service.AuditService auditService;

    @InjectMocks
    private SelfHealingScheduler scheduler;

    @BeforeEach
    void bypassStartupCooldown() throws Exception {
        Field field = SelfHealingScheduler.class.getDeclaredField("startupTime");
        field.setAccessible(true);
        field.set(scheduler, Instant.now().minusSeconds(120));
    }

    @Test
    @DisplayName("reconcile -> does nothing while the startup cooldown is active")
    void reconcile_skipsWhenInStartupCooldown() throws Exception {
        Field field = SelfHealingScheduler.class.getDeclaredField("startupTime");
        field.setAccessible(true);
        field.set(scheduler, Instant.now());

        scheduler.reconcile();

        verifyNoInteractions(projectImageRepository);
        verifyNoInteractions(deploymentService);
    }

    @Test
    @DisplayName("reconcile -> queues an async deploy when running count is below desired")
    void reconcile_deploysWhenRunningCountBelowDesired() {
        ProjectImage image = buildImage(1L, "web", 3);

        when(projectImageRepository.findAllWithProject()).thenReturn(List.of(image));
        when(containerInstanceRepository.countByProjectImageAndStatus(
                image, ContainerInstance.InstanceStatus.RUNNING)).thenReturn(1L);

        scheduler.reconcile();

        verify(deploymentService, times(1)).deployAsync(1L);
    }

    @Test
    @DisplayName("reconcile -> does not deploy when running count equals desired")
    void reconcile_skipsWhenRunningEqualsDesired() {
        ProjectImage image = buildImage(1L, "api", 2);

        when(projectImageRepository.findAllWithProject()).thenReturn(List.of(image));
        when(containerInstanceRepository.countByProjectImageAndStatus(
                image, ContainerInstance.InstanceStatus.RUNNING)).thenReturn(2L);

        scheduler.reconcile();

        verify(deploymentService, never()).deployAsync(anyLong());
    }

    @Test
    @DisplayName("reconcile -> does not deploy when desiredReplicas=0")
    void reconcile_skipsWhenDesiredReplicasIsZero() {
        ProjectImage image = buildImage(1L, "worker", 0);

        when(projectImageRepository.findAllWithProject()).thenReturn(List.of(image));

        scheduler.reconcile();

        verify(deploymentService, never()).deployAsync(anyLong());
        verify(containerInstanceRepository, never()).countByProjectImageAndStatus(any(), any());
    }

    @Test
    @DisplayName("reconcile -> does not resurrect a service the user undeployed")
    void reconcile_skipsWhenStoppedByUser() {
        ProjectImage image = buildImage(1L, "undeployed-svc", 2);
        image.setStoppedByUser(true);

        when(projectImageRepository.findAllWithProject()).thenReturn(List.of(image));

        scheduler.reconcile();

        verify(deploymentService, never()).deployAsync(anyLong());
        verify(containerInstanceRepository, never()).countByProjectImageAndStatus(any(), any());
    }

    @Test
    @DisplayName("reconcile -> service stoppedByUser with active containers triggers scaleAsync to 0")
    void reconcile_serviceStoppedByUserWithActiveContainers_triggersScaleToZero() {
        ProjectImage image = buildImage(1L, "stopped-svc", 0);
        image.setStoppedByUser(true);

        when(projectImageRepository.findAllWithProject()).thenReturn(List.of(image));
        when(containerInstanceRepository.countByProjectImageAndStatusIn(eq(image), anyList())).thenReturn(2L);

        scheduler.reconcile();

        verify(deploymentService).scaleAsync(1L, 0);
        verify(deploymentService, never()).deployAsync(anyLong());
    }

    @Test
    @DisplayName("reconcile -> service with desired=0 but active containers triggers scaleAsync to 0")
    void reconcile_desiredZeroWithActiveContainers_triggersScaleToZero() {
        ProjectImage image = buildImage(1L, "zero-svc", 0);

        when(projectImageRepository.findAllWithProject()).thenReturn(List.of(image));
        when(containerInstanceRepository.countByProjectImageAndStatusIn(eq(image), anyList())).thenReturn(1L);

        scheduler.reconcile();

        verify(deploymentService).scaleAsync(1L, 0);
        verify(deploymentService, never()).deployAsync(anyLong());
    }

    @Test
    @DisplayName("reconcile -> keeps processing other services when one deploy fails")
    void reconcile_continuesAfterDeploymentFailure() {
        ProjectImage failingImage = buildImage(1L, "broken-svc", 2);
        ProjectImage healthyImage = buildImage(2L, "healthy-svc", 1);

        when(projectImageRepository.findAllWithProject())
                .thenReturn(List.of(failingImage, healthyImage));

        when(containerInstanceRepository.countByProjectImageAndStatus(
                failingImage, ContainerInstance.InstanceStatus.RUNNING)).thenReturn(0L);
        when(containerInstanceRepository.countByProjectImageAndStatus(
                failingImage, ContainerInstance.InstanceStatus.PENDING)).thenReturn(0L);
        when(containerInstanceRepository.countByProjectImageAndStatus(
                healthyImage, ContainerInstance.InstanceStatus.RUNNING)).thenReturn(0L);
        when(containerInstanceRepository.countByProjectImageAndStatus(
                healthyImage, ContainerInstance.InstanceStatus.PENDING)).thenReturn(0L);

        doThrow(new RuntimeException("Worker unavailable"))
                .when(deploymentService).deployAsync(1L);
        doNothing().when(deploymentService).deployAsync(2L);

        scheduler.reconcile();

        verify(deploymentService).deployAsync(1L);
        verify(deploymentService).deployAsync(2L);
    }

    @Test
    @DisplayName("reconcile -> does not deploy when the image list is empty")
    void reconcile_doesNothingWhenNoImages() {
        when(projectImageRepository.findAllWithProject()).thenReturn(List.of());

        scheduler.reconcile();

        verify(deploymentService, never()).deployAsync(anyLong());
    }

    @Test
    @DisplayName("reconcile -> scales down instead of deploying when running count exceeds desired")
    void reconcile_doesNotDeployWhenRunningExceedsDesired() {
        ProjectImage image = buildImage(1L, "api", 1);

        when(projectImageRepository.findAllWithProject()).thenReturn(List.of(image));
        when(containerInstanceRepository.countByProjectImageAndStatus(
                image, ContainerInstance.InstanceStatus.RUNNING)).thenReturn(3L);

        scheduler.reconcile();

        verify(deploymentService, never()).deployAsync(anyLong());
        verify(deploymentService).scaleAsync(1L, 1);
    }

    @Test
    @DisplayName("reconcile -> a service with 5+ FAILED instances in 5 min counts as a crash loop: no deploy, enters cooldown")
    void reconcile_entersCooldownOnCrashLoop() {
        ProjectImage image = buildImage(1L, "crashing-svc", 1);

        when(projectImageRepository.findAllWithProject()).thenReturn(List.of(image));
        when(containerInstanceRepository.countByProjectImageAndStatus(
                image, ContainerInstance.InstanceStatus.RUNNING)).thenReturn(0L);
        when(containerInstanceRepository.countByProjectImageAndStatusAndCreatedAtAfter(
                eq(image), eq(ContainerInstance.InstanceStatus.FAILED), any()))
                .thenReturn(6L);

        scheduler.reconcile();

        verify(deploymentService, never()).deployAsync(anyLong());
        verify(projectImageRepository).save(image);
        org.assertj.core.api.Assertions.assertThat(image.getConsecutiveDeployFailures()).isGreaterThanOrEqualTo(5);
        org.assertj.core.api.Assertions.assertThat(image.getLastDeployFailureAt()).isNotNull();
    }

    @Test
    @DisplayName("reconcile -> below the FAILED threshold there is no crash loop, deploy runs normally")
    void reconcile_deploysWhenFailureCountBelowCrashLoopThreshold() {
        ProjectImage image = buildImage(1L, "flaky-svc", 1);

        when(projectImageRepository.findAllWithProject()).thenReturn(List.of(image));
        when(containerInstanceRepository.countByProjectImageAndStatus(
                image, ContainerInstance.InstanceStatus.RUNNING)).thenReturn(0L);
        when(containerInstanceRepository.countByProjectImageAndStatusAndCreatedAtAfter(
                eq(image), eq(ContainerInstance.InstanceStatus.FAILED), any()))
                .thenReturn(2L);

        scheduler.reconcile();

        verify(deploymentService, times(1)).deployAsync(1L);
        verify(projectImageRepository, never()).save(any());
    }

    private ProjectImage buildImage(Long id, String serviceName, int desiredReplicas) {
        UserProject project = UserProject.builder()
                .name("test-project")
                .build();

        return ProjectImage.builder()
                .id(id)
                .project(project)
                .serviceName(serviceName)
                .imageName("nginx:latest")
                .desiredReplicas(desiredReplicas)
                .containerPort(80)
                .build();
    }
}
