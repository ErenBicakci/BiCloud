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

    /**
     * Per-project internet-capable bridge for services with the admin-granted
     * egress flag. Project networks are internal (no outbound); a container that
     * must reach external APIs gets attached to its project's egress bridge too.
     * One bridge PER PROJECT (not a single shared one): egress-enabled containers
     * of different tenants must not share an L2 segment.
     */
    private static final String EGRESS_PREFIX = "bicloud-egress-";

    private final DockerClient dockerClient;


    //network names derive from the project name
    public static String networkName(String projectName) {
        String lower = projectName.toLowerCase();
        return lower.startsWith(NETWORK_PREFIX) ? lower : NETWORK_PREFIX + lower;
    }

    //create the network if missing. double-checked
    public String ensureNetworkExists(String projectName) {
        return ensureNetwork(networkName(projectName), true);
    }

    /** Project name -> its egress network name. */
    public static String egressNetworkName(String projectName) {
        return EGRESS_PREFIX + projectName.toLowerCase();
    }

    /** Per-project internet-capable bridge for egress-enabled services (create-if-missing). */
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
