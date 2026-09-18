package com.bic.cloud.worker.docker;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class DockerContainerServiceTest {

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
}
