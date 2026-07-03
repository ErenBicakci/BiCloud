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

    /**
     * Per-project internet-capable bridge for services with the admin-granted
     * egress flag. Project networks are internal (no outbound); a container that
     * must reach external APIs gets attached to its project's egress bridge too.
     * One bridge PER PROJECT (not a single shared one): egress-enabled containers
     * of different tenants must not share an L2 segment.
     */
    private static final String EGRESS_PREFIX = "bicloud-egress-";

    private final DockerClient dockerClient;


    //network names derive from the project name (globally unique - enforced by the CP)
    public static String networkName(String projectName) {
        requireProjectName(projectName);
        // Locale.ROOT: never locale-sensitive (Turkish 'I' -> 'ı' would change the name)
        String lower = projectName.toLowerCase(Locale.ROOT);
        return lower.startsWith(NETWORK_PREFIX) ? lower : NETWORK_PREFIX + lower;
    }

    /** The worker is an API boundary: don't trust the caller to send a usable name. */
    private static void requireProjectName(String projectName) {
        if (projectName == null || projectName.isBlank()) {
            throw new IllegalArgumentException("projectName must not be null or blank");
        }
    }

    //create the network if missing. double-checked
    public String ensureNetworkExists(String projectName) {
        return ensureNetwork(networkName(projectName), true);
    }

    /** Project name -> its egress network name. */
    public static String egressNetworkName(String projectName) {
        requireProjectName(projectName);
        return EGRESS_PREFIX + projectName.toLowerCase(Locale.ROOT);
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
            // Fail-CLOSED: a pre-existing network with the wrong internal mode must
            // not be silently reused - an isolated project network that is actually
            // internet-capable (or vice versa) breaks the isolation contract.
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

    /**
     * Refuses to reuse a network whose internal mode differs from what the
     * caller expects. Without this check a network named like a project network
     * but created internet-capable (manually or by older code) would silently
     * void the isolation guarantee.
     */
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
