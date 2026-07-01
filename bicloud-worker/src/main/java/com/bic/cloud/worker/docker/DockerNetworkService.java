package com.bic.cloud.worker.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.ConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerNetworkService {

    private static final String NETWORK_PREFIX = "bicloud-";

    private final DockerClient dockerClient;


    //network names derive from the project name
    public static String networkName(String projectName) {
        String lower = projectName.toLowerCase();
        return lower.startsWith(NETWORK_PREFIX) ? lower : NETWORK_PREFIX + lower;
    }

    //create the network if missing. double-checked
    public String ensureNetworkExists(String projectName) {
        String name = networkName(projectName);

        var existing = dockerClient.listNetworksCmd()
                .withNameFilter(name)
                .exec()
                .stream()
                .filter(n -> name.equals(n.getName()))
                .findFirst();

        if (existing.isPresent()) {
            log.debug("Docker network already exists: {} (id={})", name, existing.get().getId());
            return existing.get().getId();
        }

        try {
            var response = dockerClient.createNetworkCmd()
                    .withName(name)
                    .withDriver("bridge")
                    .withInternal(true)
                    .exec();
            log.info("Created docker network: {} (id={})", name, response.getId());
            return response.getId();
        } catch (ConflictException e) {
            // another thread created it at the same moment - query again for the ID
            log.debug("Docker network '{}' created concurrently, fetching existing.", name);
            return dockerClient.listNetworksCmd()
                    .withNameFilter(name)
                    .exec()
                    .stream()
                    .filter(n -> name.equals(n.getName()))
                    .findFirst()
                    .map(n -> n.getId())
                    .orElseThrow(() -> new RuntimeException(
                            "Network '" + name + "' not found after conflict"));
        }
    }
}
