package com.bic.cloud.worker.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.model.Network;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerNetworkService {

    private static final String NETWORK_PREFIX = "bicloud-";
    private static final String EGRESS_PREFIX = "bicloud-egress-";

    private final DockerClient dockerClient;

    public static String networkName(String projectName) {
        requireProjectName(projectName);
        String lower = projectName.toLowerCase(Locale.ROOT);
        return lower.startsWith(NETWORK_PREFIX) ? lower : NETWORK_PREFIX + lower;
    }

    private static void requireProjectName(String projectName) {
        if (projectName == null || projectName.isBlank()) {
            throw new IllegalArgumentException("projectName must not be null or blank");
        }
    }

    public String ensureNetworkExists(String projectName) {
        return ensureNetwork(networkName(projectName), true);
    }

    public static String egressNetworkName(String projectName) {
        requireProjectName(projectName);
        return EGRESS_PREFIX + projectName.toLowerCase(Locale.ROOT);
    }

    public String ensureEgressNetworkExists(String projectName) {
        return ensureNetwork(egressNetworkName(projectName), false);
    }

    private String ensureNetwork(String name, boolean internal) {

        var existing = dockerClient.listNetworksCmd()
                .withNameFilter(name)
                .exec()
                .stream()
                .filter(n -> name.equals(n.getName()))
                .findFirst();

        if (existing.isPresent()) {
            assertInternalModeMatches(existing.get(), internal);
            log.debug("Docker network already exists: {} (id={})", name, existing.get().getId());
            return existing.get().getId();
        }

        try {
            var response = dockerClient.createNetworkCmd()
                    .withName(name)
                    .withDriver("bridge")
                    .withInternal(internal)
                    .exec();
            log.info("Created docker network: {} (id={}, internal={})", name, response.getId(), internal);
            return response.getId();
        } catch (ConflictException e) {
            // another thread created it at the same moment - query again for the ID
            log.debug("Docker network '{}' created concurrently, fetching existing.", name);
            Network network = dockerClient.listNetworksCmd()
                    .withNameFilter(name)
                    .exec()
                    .stream()
                    .filter(n -> name.equals(n.getName()))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException(
                            "Network '" + name + "' not found after conflict"));
            assertInternalModeMatches(network, internal);
            return network.getId();
        }
    }

    private void assertInternalModeMatches(Network network, boolean expectedInternal) {
        Boolean actual = network.getInternal();
        if (actual == null || actual != expectedInternal) {
            throw new IllegalStateException(
                    "Docker network '" + network.getName() + "' exists but internal="
                            + actual + " (expected " + expectedInternal
                            + "); refusing to reuse it - remove or recreate the network");
        }
    }
}
