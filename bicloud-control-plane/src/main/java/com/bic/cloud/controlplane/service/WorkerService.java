package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.dto.*;
import com.bic.cloud.controlplane.exception.WorkerNotFoundException;
import com.bic.cloud.controlplane.model.AuditEvent;
import com.bic.cloud.controlplane.model.ContainerInstance;
import com.bic.cloud.controlplane.model.WorkerNode;
import com.bic.cloud.controlplane.model.WorkerState;
import com.bic.cloud.controlplane.repository.ContainerInstanceRepository;
import com.bic.cloud.controlplane.repository.WorkerNodeRepository;
import com.bic.cloud.controlplane.repository.WorkerStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerService {

    private final WorkerNodeRepository nodeRepository;
    private final WorkerStateRepository stateRepository;
    private final ContainerInstanceRepository containerInstanceRepository;
    private final WorkerScoringService scoringService;
    private final AuditService auditService;

    private static final int CPU_OVERLOAD_THRESHOLD = 85;
    private static final int MEMORY_OVERLOAD_THRESHOLD_PERCENT = 90;

    @Transactional
    public WorkerRegisterResponse register(WorkerRegisterRequest request, String clientIp) {

        WorkerNode node;

        if (request.getWorkerId() != null) {
            node = nodeRepository.findByIdForUpdate(request.getWorkerId())
                    .orElseGet(() -> createNewNode(request));

            auditCapacityChange(node, request);
            auditVersionChange(node, request);

        } else {
            node = nodeRepository.findByWorkerNameForUpdate(request.getWorkerName())
                    .orElseGet(() -> createNewNode(request));

            auditCapacityChange(node, request);
            auditVersionChange(node, request);
        }

        node.setWorkerName(request.getWorkerName());
        node.setWorkerVersion(request.getWorkerVersion());
        node.setTotalCpuCores(request.getTotalCpuCores());
        node.setTotalMemoryMb(request.getTotalMemoryMb());
        node.setServerPort(request.getServerPort());
        nodeRepository.save(node);

        WorkerState state = stateRepository.findById(node.getId())
                .orElseGet(() -> {
                    WorkerState newState = new WorkerState();
                    newState.setWorker(node);
                    return newState;
                });

        state.setIpAddress(clientIp);
        state.setMeshIp(request.getMeshIp());
        state.setCpuUsagePercent(0);
        state.setUsedMemoryMb(0);
        state.setLastHeartbeat(Instant.now());

        // If a worker the admin drained (MAINTENANCE) restarts its JVM and
        // re-registers, the drain intent must survive - unconditionally setting
        // ACTIVE would silently put a worker under maintenance back into scheduling.
        if (state.getStatus() != WorkerState.NodeStatus.MAINTENANCE) {
            state.setStatus(WorkerState.NodeStatus.ACTIVE);
        } else {
            log.info("Worker {} re-registered while in MAINTENANCE - drain durumu korunuyor.", node.getId());
        }

        stateRepository.save(state);

        log.info("Worker registered/refreshed: id={}, name={}, version={}, CPU={}, RAM={} MB, port={}, meshIp={}",
                node.getId(), node.getWorkerName(), node.getWorkerVersion(),
                node.getTotalCpuCores(), node.getTotalMemoryMb(),
                node.getServerPort(), request.getMeshIp());

        return WorkerRegisterResponse.builder()
                .workerId(node.getId())
                .build();
    }

    @Transactional
    public void handleHeartbeat(WorkerHeartbeatRequest request) {

        UUID workerId = request.getWorkerId();

        WorkerNode node = nodeRepository.findByIdForUpdate(workerId)
                .orElseThrow(() -> new WorkerNotFoundException(workerId));

        WorkerState state = stateRepository.findById(workerId)
                .orElseThrow(() -> new WorkerNotFoundException(workerId));

        int cpuPercent = request.getCpuUsagePercent();
        long usedMemory = request.getUsedMemoryMb();

        if (usedMemory > node.getTotalMemoryMb()) {
            log.warn("Worker {} reported usedMemory ({} MB) > totalMemory ({} MB). Clamping.",
                    workerId, usedMemory, node.getTotalMemoryMb());
            usedMemory = node.getTotalMemoryMb();
        }
        if (usedMemory < 0) {
            usedMemory = 0;
        }
        state.setUsedMemoryMb(usedMemory);

        if (cpuPercent < 0) {
            log.debug("Worker {} reported CPU = {} (not yet available). Keeping previous value: {}%",
                    workerId, cpuPercent, state.getCpuUsagePercent());
            cpuPercent = state.getCpuUsagePercent();
        } else if (cpuPercent > 100) {
            cpuPercent = 100;
        }
        state.setCpuUsagePercent(cpuPercent);

        // ALWAYS use the CP's own clock for liveness. The worker's timestamp cannot
        // be trusted: if cross-machine clock skew exceeds 30s, a worker with a
        // perfectly regular heartbeat keeps getting marked OFFLINE, its containers
        // go FAILED and self-healing enters an endless restart loop.
        state.setLastHeartbeat(Instant.now());
        if (request.getTimestamp() != null) {
            long skewSeconds = Math.abs(
                    Instant.now().getEpochSecond() - request.getTimestamp().getEpochSecond());
            if (skewSeconds > 15) {
                log.warn("Worker {} clock skew detected: ~{}s difference from CP clock. " +
                         "Not a problem since liveness uses the CP clock; still, NTP sync is recommended.",
                        workerId, skewSeconds);
            }
        }

        // maintenance mode is only lifted by an admin - heartbeats must not override it.
        // metrics and lastHeartbeat still update (liveness tracking continues).
        if (state.getStatus() == WorkerState.NodeStatus.MAINTENANCE) {
            stateRepository.save(state);
            log.debug("Heartbeat updated for worker {} (MAINTENANCE preserved)", workerId);
            return;
        }

        boolean cpuOverloaded = cpuPercent > CPU_OVERLOAD_THRESHOLD;
        boolean memoryOverloaded = isMemoryOverloaded(usedMemory, node.getTotalMemoryMb());

        if (cpuOverloaded || memoryOverloaded) {
            state.setStatus(WorkerState.NodeStatus.OVERLOADED);
            if (cpuOverloaded) {
                log.warn("Worker {} CPU overloaded: {}%", workerId, cpuPercent);
            }
            if (memoryOverloaded) {
                long memPercent = node.getTotalMemoryMb() > 0
                        ? (usedMemory * 100) / node.getTotalMemoryMb() : 0;
                log.warn("Worker {} Memory overloaded: {}% ({}/{} MB)",
                        workerId, memPercent, usedMemory, node.getTotalMemoryMb());
            }
        } else {
            state.setStatus(WorkerState.NodeStatus.ACTIVE);
        }

        stateRepository.save(state);

        log.debug("Heartbeat updated for worker: {} (cpu={}%, mem={} MB)",
                workerId, cpuPercent, usedMemory);
    }

    @Transactional
    public void deregister(UUID workerId) {

        WorkerState state = stateRepository.findById(workerId)
                .orElseThrow(() -> new WorkerNotFoundException(workerId));

        state.setStatus(WorkerState.NodeStatus.MAINTENANCE);
        state.setLastHeartbeat(Instant.now());
        stateRepository.save(state);

        log.info("Worker {} deregistered (graceful shutdown). Status -> MAINTENANCE", workerId);

        markContainersStopped(workerId, state.getWorker().getWorkerName());
    }

    private void markContainersStopped(UUID workerNodeId, String workerName) {
        List<ContainerInstance> running = containerInstanceRepository.findRunningByWorkerNodeId(workerNodeId);

        if (running.isEmpty()) {
            return;
        }

        log.info("Marking {} container(s) as STOPPED for gracefully deregistered worker '{}'",
                running.size(), workerName);

        for (ContainerInstance ci : running) {
            ci.setStatus(ContainerInstance.InstanceStatus.STOPPED);
            containerInstanceRepository.save(ci);

            log.info("  -> Container STOPPED: instanceId={}, service={}",
                    ci.getId(), ci.getProjectImage().getServiceName());
        }
    }

    @Transactional
    public void handleContainerStatusUpdate(ContainerStatusUpdateRequest request) {

        String dockerId = request.getDockerContainerId();

        containerInstanceRepository.findByDockerContainerId(dockerId)
                .ifPresentOrElse(instance -> {
                    try {
                        ContainerInstance.InstanceStatus newStatus =
                                ContainerInstance.InstanceStatus.valueOf(request.getStatus());
                        instance.setStatus(newStatus);
                        containerInstanceRepository.save(instance);

                        log.info("Container {} status updated to {} (worker: {}, message: {})",
                                dockerId, newStatus, request.getWorkerId(), request.getMessage());

                    } catch (IllegalArgumentException e) {
                        log.warn("Unknown container status '{}' for container {}",
                                request.getStatus(), dockerId);
                    }
                }, () -> log.warn("Container status update for unknown container: {}", dockerId));
    }

    /**
     * Maintenance mode (drain). A MAINTENANCE worker is never picked by the
     * scheduler (WorkerScoringService filters for ACTIVE only); its existing
     * containers keep running. Heartbeats do not override this state.
     */
    @Transactional
    public WorkerNodeDetailResponse setMaintenance(UUID workerId, boolean enabled, String adminUsername) {

        WorkerNode node = nodeRepository.findById(workerId)
                .orElseThrow(() -> new WorkerNotFoundException(workerId));

        WorkerState state = stateRepository.findById(workerId)
                .orElseThrow(() -> new WorkerNotFoundException(workerId));

        if (enabled) {
            state.setStatus(WorkerState.NodeStatus.MAINTENANCE);
        } else {
            // ACTIVE/OVERLOADED is recomputed on the first heartbeat;
            // set ACTIVE right away so it isn't closed to the scheduler until then.
            state.setStatus(WorkerState.NodeStatus.ACTIVE);
        }
        stateRepository.save(state);

        log.info("Worker '{}' (id={}) maintenance mode {} by {}.",
                node.getWorkerName(), workerId, enabled ? "ENABLED" : "DISABLED", adminUsername);

        auditService.workerAction(adminUsername != null ? adminUsername : "admin",
                AuditEvent.AuditAction.WORKER_MAINTENANCE, AuditEvent.Severity.WARN,
                node.getWorkerName(),
                enabled ? "Worker put in maintenance (no new work will be assigned)" : "Worker taken out of maintenance");

        return toDetailResponse(node);
    }

    @Transactional(readOnly = true)
    public List<WorkerNodeDetailResponse> listAllWorkers() {

        return nodeRepository.findAll().stream()
                .map(this::toDetailResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public WorkerNodeDetailResponse getWorkerDetail(UUID workerId) {

        WorkerNode node = nodeRepository.findById(workerId)
                .orElseThrow(() -> new WorkerNotFoundException(workerId));

        return toDetailResponse(node);
    }

    private WorkerNodeDetailResponse toDetailResponse(WorkerNode node) {

        WorkerState state = stateRepository.findById(node.getId()).orElse(null);
        double score = state != null ? scoringService.calculateScore(node, state) : 0.0;
        long runningContainers = containerInstanceRepository
                .countByWorkerNodeAndStatus(node, ContainerInstance.InstanceStatus.RUNNING);

        return WorkerNodeDetailResponse.builder()
                .workerId(node.getId())
                .workerName(node.getWorkerName())
                .workerVersion(node.getWorkerVersion())
                .totalCpuCores(node.getTotalCpuCores())
                .totalMemoryMb(node.getTotalMemoryMb())
                .cpuUsagePercent(state != null ? state.getCpuUsagePercent() : 0)
                .usedMemoryMb(state != null ? state.getUsedMemoryMb() : 0)
                .status(state != null ? state.getStatus().name() : "UNKNOWN")
                .ipAddress(state != null ? state.getIpAddress() : null)
                .meshIp(state != null ? state.getMeshIp() : null)
                .serverPort(node.getServerPort())
                .lastHeartbeat(state != null ? state.getLastHeartbeat() : null)
                .score(score)
                .runningContainers(runningContainers)
                .createdAt(node.getCreatedAt())
                .build();
    }

    private WorkerNode createNewNode(WorkerRegisterRequest request) {
        WorkerNode newNode = WorkerNode.builder()
                .workerName(request.getWorkerName())
                .workerVersion(request.getWorkerVersion())
                .totalCpuCores(request.getTotalCpuCores())
                .totalMemoryMb(request.getTotalMemoryMb())
                .serverPort(request.getServerPort())
                .build();
        return nodeRepository.save(newNode);
    }

    private void auditCapacityChange(WorkerNode existing, WorkerRegisterRequest request) {
        if (existing.getId() == null) return;

        if (existing.getTotalCpuCores() != request.getTotalCpuCores()) {
            log.warn("CAPACITY CHANGE DETECTED for worker '{}' (id={}): CPU cores {} -> {}",
                    existing.getWorkerName(), existing.getId(),
                    existing.getTotalCpuCores(), request.getTotalCpuCores());
        }
        if (existing.getTotalMemoryMb() != request.getTotalMemoryMb()) {
            log.warn("CAPACITY CHANGE DETECTED for worker '{}' (id={}): Memory {} MB -> {} MB",
                    existing.getWorkerName(), existing.getId(),
                    existing.getTotalMemoryMb(), request.getTotalMemoryMb());
        }
    }

    private void auditVersionChange(WorkerNode existing, WorkerRegisterRequest request) {
        if (existing.getId() == null) return;

        String oldVersion = existing.getWorkerVersion();
        String newVersion = request.getWorkerVersion();

        if (oldVersion != null && !Objects.equals(oldVersion, newVersion)) {
            log.warn("VERSION CHANGE DETECTED for worker '{}' (id={}): version {} -> {}",
                    existing.getWorkerName(), existing.getId(), oldVersion, newVersion);
        }
    }

    private boolean isMemoryOverloaded(long usedMemoryMb, long totalMemoryMb) {
        if (totalMemoryMb <= 0) return false;
        long memoryPercent = (usedMemoryMb * 100) / totalMemoryMb;
        return memoryPercent > MEMORY_OVERLOAD_THRESHOLD_PERCENT;
    }
}