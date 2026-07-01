package com.bic.cloud.bicloud_gateway.registry;

import com.bic.cloud.bicloud_gateway.client.ControlPlaneDiscoveryClient;
import com.bic.cloud.bicloud_gateway.docker.GatewayNetworkManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Periodically reconciles the RouteRegistry with reality. The CP's push
 * notifications can be missed (a gateway restart wipes the registry, a failed
 * deregister leaves a stale entry); fixed both ways here: instances absent
 * from local Docker networks are pruned, and a resync is requested from the CP.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegistryReconciler {

    private final RouteRegistry registry;
    private final GatewayNetworkManager networkManager;
    private final ControlPlaneDiscoveryClient controlPlaneClient;

    @Scheduled(initialDelayString = "${bicloud.gateway.reconcile-initial-delay-ms:10000}",
               fixedDelayString   = "${bicloud.gateway.reconcile-interval-ms:30000}")
    public void reconcile() {
        // 1) prune - local Docker reality
        Map<String, Set<String>> live = networkManager.liveIpsByProject();
        if (!live.isEmpty()) { // empty map = Docker query failed -> skip pruning
            int pruned = registry.pruneStale(live);
            if (pruned > 0) {
                log.info("Reconcile: pruned {} stale instance(s). routes={}, instances={}",
                        pruned, registry.routeCount(), registry.instanceCount());
            }
        }

        // 2) refill - re-request the RUNNING records from the CP (fire-and-forget)
        controlPlaneClient.requestResync().subscribe();
    }
}
