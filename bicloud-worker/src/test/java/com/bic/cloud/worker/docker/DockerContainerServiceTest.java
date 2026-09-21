package com.bic.cloud.worker.docker;

import com.bic.cloud.worker.exception.DockerOperationException;
import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DockerContainerServiceTest {

    private final DockerClient dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
    private final DockerContainerService service =
            new DockerContainerService(dockerClient, mock(DockerNetworkService.class));

    @ParameterizedTest
    @CsvSource({
            "nginx, false",
            "library/nginx, false",
            "localhost:5000/app, false",
            "ghcr.io/org/app, false",
            "nginx:alpine, true",
            "localhost:5000/app:1.2, true",
            "nginx@sha256:0123abcd, true"
    })
    void detectsExplicitTagOrDigest(String imageName, boolean expected) {
        assertThat(DockerContainerService.hasTagOrDigest(imageName)).isEqualTo(expected);
    }

    @Test
    void refusesToTouchContainersItDoesNotManage() {
        when(dockerClient.inspectContainerCmd("postgres").exec().getConfig().getLabels()).thenReturn(Map.of());

        assertThatThrownBy(() -> service.removeContainer("postgres"))
                .isInstanceOf(DockerOperationException.class);
        verify(dockerClient, never()).removeContainerCmd(anyString());
    }

    @Test
    void removesManagedContainers() {
        when(dockerClient.inspectContainerCmd("app").exec().getConfig().getLabels())
                .thenReturn(Map.of("bicloud.managed", "true"));

        service.removeContainer("app");

        verify(dockerClient).removeContainerCmd("app");
    }
}
