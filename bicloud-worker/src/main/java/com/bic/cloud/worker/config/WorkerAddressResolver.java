package com.bic.cloud.worker.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;

/**
 * Auto-detects worker.ip when it is left blank (or is a loopback).
 * Since this is the address other machines use to reach this one, the IP of
 * the interface towards the control plane is used; if the CP is localhost,
 * 8.8.8.8 is used instead. Runs in PostConstruct; WorkerStartup registers on
 * ApplicationReady, so the resolved value makes it into the registration.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerAddressResolver {

    private final WorkerProperties workerProperties;

    @Value("${worker.control-plane-url}")
    private String controlPlaneUrl;

    @PostConstruct
    void resolveAddresses() {
        String ip = workerProperties.getIp();
        if (isBlank(ip) || "127.0.0.1".equals(ip) || "localhost".equalsIgnoreCase(ip)) {
            String detected = detectLanIp();
            workerProperties.setIp(detected);
            log.info("worker.ip auto-detected: {}", detected);
        }
    }

    private String detectLanIp() {
        try {
            URI cp = URI.create(controlPlaneUrl);
            String host = cp.getHost();
            int port = cp.getPort() > 0 ? cp.getPort() : 80;
            String viaControlPlane = interfaceAddressTowards(host, port);
            if (viaControlPlane != null && !viaControlPlane.startsWith("127.")) {
                return viaControlPlane;
            }
        } catch (Exception e) {
            log.warn("IP detection from the control plane URL failed: {}", e.getMessage());
        }

        String viaPublic = interfaceAddressTowards("8.8.8.8", 53);
        if (viaPublic != null) {
            return viaPublic;
        }

        log.warn("Could not detect LAN IP, falling back to 127.0.0.1 - multi-machine mesh will not work!");
        return "127.0.0.1";
    }

    /**
     * Which local interface would be used to send a packet to the given target?
     * UDP connect produces no real traffic; it only consults the routing table.
     */
    private String interfaceAddressTowards(String host, int port) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName(host), port));
            return socket.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            log.warn("Interface detection failed ({}:{}): {}", host, port, e.getMessage());
            return null;
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
