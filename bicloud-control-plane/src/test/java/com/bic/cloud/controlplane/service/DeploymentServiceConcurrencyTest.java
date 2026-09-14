package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.client.WorkerHttpClient;
import com.bic.cloud.controlplane.dto.WorkerContainerCreateResponse;
import com.bic.cloud.controlplane.mapper.WorkerRequestMapper;
import com.bic.cloud.controlplane.model.BicloudUser;
import com.bic.cloud.controlplane.model.ContainerInstance;
import com.bic.cloud.controlplane.model.ProjectImage;
import com.bic.cloud.controlplane.model.UserProject;
import com.bic.cloud.controlplane.model.WorkerNode;
import com.bic.cloud.controlplane.repository.BicloudUserRepository;
import com.bic.cloud.controlplane.repository.ContainerInstanceRepository;
import com.bic.cloud.controlplane.repository.ProjectImageRepository;
import com.bic.cloud.controlplane.repository.UserProjectRepository;
import com.bic.cloud.controlplane.repository.WorkerNodeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs the deploy loop against a real persistence context, without a surrounding
 * test transaction, so every repository call commits on its own like in production.
 *
 * Incident: a deploy that failed while the user was fixing the image name saved
 * its stale ProjectImage snapshot and silently reverted the user's edit.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeploymentServiceConcurrencyTest {

    @Autowired private BicloudUserRepository userRepository;
    @Autowired private UserProjectRepository projectRepository;
    @Autowired private ProjectImageRepository imageRepository;
    @Autowired private ContainerInstanceRepository instanceRepository;
    @Autowired private WorkerNodeRepository workerRepository;

    @AfterEach
    void cleanUp() {
        instanceRepository.deleteAll();
        imageRepository.deleteAll();
        projectRepository.deleteAll();
        workerRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("failed deploy records the failure without reverting a concurrent user edit")
    void failedDeployKeepsConcurrentServiceEdit() {
        ProjectImage image = saveImage("ngnix:alpine", 2, 1);

        WorkerScoringService scoring = mock(WorkerScoringService.class);
        when(scoring.selectAndReserveWorker(any(), anyInt(), anyLong())).thenAnswer(inv -> {
            // the user fixes the typo and scales up while the deploy is in flight
            ProjectImage fresh = imageRepository.findById(image.getId()).orElseThrow();
            fresh.setImageName("nginx:alpine");
            fresh.setDesiredReplicas(4);
            imageRepository.save(fresh);
            return Optional.empty();
        });

        deploymentService(scoring, mock(WorkerHttpClient.class))
                .reconcileService(imageRepository.findByIdForDeployment(image.getId()).orElseThrow());

        ProjectImage after = imageRepository.findById(image.getId()).orElseThrow();
        assertThat(after.getImageName()).isEqualTo("nginx:alpine");
        assertThat(after.getDesiredReplicas()).isEqualTo(4);
        assertThat(after.getConsecutiveDeployFailures()).isEqualTo(2);
        assertThat(after.getLastDeployFailureAt()).isNotNull();
    }

    @Test
    @DisplayName("a reservation released during container creation is not promoted back to RUNNING")
    void releasedReservationIsNotRevived() {
        ProjectImage image = saveImage("nginx:alpine", 1, 0);
        WorkerNode worker = workerRepository.save(WorkerNode.builder()
                .workerName("worker-1").totalCpuCores(4).totalMemoryMb(4096).serverPort(8081).build());

        AtomicReference<ContainerInstance> reserved = new AtomicReference<>();
        WorkerScoringService scoring = mock(WorkerScoringService.class);
        when(scoring.selectAndReserveWorker(any(), anyInt(), anyLong())).thenAnswer(inv -> {
            reserved.set(instanceRepository.save(ContainerInstance.builder()
                    .projectImage(inv.getArgument(0))
                    .workerNode(worker)
                    .status(ContainerInstance.InstanceStatus.PENDING)
                    .build()));
            return Optional.of(reserved.get());
        });

        WorkerHttpClient workerClient = mock(WorkerHttpClient.class);
        when(workerClient.createContainer(any(), any())).thenAnswer(inv -> {
            // a scale-down picks the PENDING record while the worker is still pulling
            instanceRepository.transitionStatus(reserved.get().getId(),
                    ContainerInstance.InstanceStatus.PENDING, ContainerInstance.InstanceStatus.STOPPED);
            return WorkerContainerCreateResponse.builder()
                    .containerId("docker-late-1")
                    .containerIp("172.18.0.7")
                    .build();
        });

        deploymentService(scoring, workerClient)
                .reconcileService(imageRepository.findByIdForDeployment(image.getId()).orElseThrow());

        ContainerInstance after = instanceRepository.findById(reserved.get().getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ContainerInstance.InstanceStatus.STOPPED);
        assertThat(after.getDockerContainerId()).isNull();
        verify(workerClient).stopAndRemoveContainer(any(), eq("docker-late-1"));
    }

    private ProjectImage saveImage(String imageName, int desiredReplicas, int failures) {
        BicloudUser owner = userRepository.save(BicloudUser.builder()
                .username("owner").password("x").role("USER").build());
        UserProject project = projectRepository.save(UserProject.builder()
                .name("demo").owner(owner).build());
        return imageRepository.save(ProjectImage.builder()
                .project(project)
                .serviceName("web")
                .imageName(imageName)
                .desiredReplicas(desiredReplicas)
                .containerPort(80)
                .memoryLimitMb(128)
                .cpuLimit(0.5)
                .environmentVariables(new HashMap<>(Map.of("MODE", "test")))
                .consecutiveDeployFailures(failures)
                .lastDeployFailureAt(failures > 0 ? Instant.now() : null)
                .build());
    }

    private DeploymentService deploymentService(WorkerScoringService scoring, WorkerHttpClient workerClient) {
        return new DeploymentService(workerClient, new WorkerRequestMapper(), scoring,
                instanceRepository, imageRepository,
                mock(ServiceDiscoveryService.class), mock(GatewayNotificationService.class));
    }
}
