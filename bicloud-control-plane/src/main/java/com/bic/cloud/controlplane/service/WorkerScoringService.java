package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.model.WorkerNode;
import com.bic.cloud.controlplane.model.WorkerState;
import com.bic.cloud.controlplane.repository.ContainerInstanceRepository;
import com.bic.cloud.controlplane.repository.WorkerNodeRepository;
import com.bic.cloud.controlplane.repository.WorkerStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Worker selection combines two signals:
 *
 * 1. LIVE OS usage from heartbeats (cpuUsagePercent, usedMemoryMb).
 *    Insufficient on its own: it updates every ~10s and a freshly started idle
 *    container doesn't raise OS load immediately. Deploy 40 replicas in a row
 *    and they all pile onto the same worker.
 *
 * 2. RESERVATIONS in the DB: the summed limits of RUNNING/PENDING containers
 *    assigned to the worker. A PENDING row is committed before the worker call,
 *    so every replica pick accounts for the previous one - including deploys
 *    running concurrently on other threads (a simplified version of
 *    Kubernetes' request-based scheduling).
 *
 * The score uses the pessimistic (max usage) of the two signals.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerScoringService {

    private final WorkerNodeRepository nodeRepository;
    private final WorkerStateRepository stateRepository;
    private final ContainerInstanceRepository containerInstanceRepository;

    private static final double CPU_FREE_WEIGHT = 0.5;
    private static final double MEMORY_FREE_WEIGHT = 0.5;

    private record Reservation(long memoryMb, long cpuMillicores) {
        static final Reservation NONE = new Reservation(0, 0);
    }

    public double calculateScore(WorkerNode node, WorkerState state) {
        return calculateScore(node, state, Reservation.NONE);
    }

    private double calculateScore(WorkerNode node, WorkerState state, Reservation reserved) {

        if (node == null || state == null) {
            return 0.0;
        }

        if (state.getStatus() != WorkerState.NodeStatus.ACTIVE) {
            return 0.0;
        }

        double cpuUsedPercent = Math.max(state.getCpuUsagePercent(), reservedCpuPercent(node, reserved));
        double cpuFreePercent = Math.max(0.0, 100.0 - cpuUsedPercent);

        double memoryFreePercent = 0.0;
        if (node.getTotalMemoryMb() > 0) {
            long usedMemory = Math.max(state.getUsedMemoryMb(), reserved.memoryMb());
            long freeMemory = Math.max(0, node.getTotalMemoryMb() - usedMemory);
            memoryFreePercent = ((double) freeMemory / node.getTotalMemoryMb()) * 100.0;
        }

        return (CPU_FREE_WEIGHT * cpuFreePercent)
             + (MEMORY_FREE_WEIGHT * memoryFreePercent);
    }

    public Optional<WorkerNode> selectBestWorker() {

        List<WorkerState> activeStates = stateRepository.findAll()
                .stream()
                .filter(s -> s.getStatus() == WorkerState.NodeStatus.ACTIVE)
                .toList();

        if (activeStates.isEmpty()) {
            log.warn("No ACTIVE workers available for scheduling.");
            return Optional.empty();
        }

        Map<UUID, Reservation> reservations = loadReservations();

        return activeStates.stream()
                .max(Comparator.comparingDouble(
                        s -> calculateScore(s.getWorker(), s, reservationFor(s, reservations))))
                .map(WorkerState::getWorker);
    }

    public Optional<WorkerNode> selectBestWorkerWithCapacity(int requiredCpuMillicores, long requiredMemoryMb) {

        List<WorkerState> activeStates = stateRepository.findAll()
                .stream()
                .filter(s -> s.getStatus() == WorkerState.NodeStatus.ACTIVE)
                .toList();

        if (activeStates.isEmpty()) {
            log.warn("No ACTIVE workers available for scheduling.");
            return Optional.empty();
        }

        Map<UUID, Reservation> reservations = loadReservations();

        return activeStates.stream()
                .filter(state -> {
                    WorkerNode node = state.getWorker();
                    Reservation reserved = reservationFor(state, reservations);

                    long usedMemory = Math.max(state.getUsedMemoryMb(), reserved.memoryMb());
                    long freeMemory = Math.max(0, node.getTotalMemoryMb() - usedMemory);

                    long totalMillicores = (long) node.getTotalCpuCores() * 1000;
                    long usedMillicores = Math.max(
                            (long) ((state.getCpuUsagePercent() / 100.0) * totalMillicores),
                            reserved.cpuMillicores());
                    long freeCpuMillicores = Math.max(0, totalMillicores - usedMillicores);

                    return freeMemory >= requiredMemoryMb && freeCpuMillicores >= requiredCpuMillicores;
                })
                .max(Comparator.comparingDouble(
                        s -> calculateScore(s.getWorker(), s, reservationFor(s, reservations))))
                .map(WorkerState::getWorker);
    }

    /**
     * Summed RUNNING/PENDING container limits per worker. Deploys commit a
     * PENDING row before calling the worker, so assignments made moments ago -
     * on this thread or any other - immediately lower the next pick's score.
     */
    private Map<UUID, Reservation> loadReservations() {
        Map<UUID, Reservation> map = new HashMap<>();
        for (Object[] row : containerInstanceRepository.sumReservedResourcesByWorker()) {
            UUID workerId = (UUID) row[0];
            long memoryMb = row[1] != null ? ((Number) row[1]).longValue() : 0;
            double cpuCores = row[2] != null ? ((Number) row[2]).doubleValue() : 0;
            map.put(workerId, new Reservation(memoryMb, Math.round(cpuCores * 1000)));
        }
        return map;
    }

    private Reservation reservationFor(WorkerState state, Map<UUID, Reservation> reservations) {
        if (state.getWorker() == null || state.getWorker().getId() == null) {
            return Reservation.NONE;
        }
        return reservations.getOrDefault(state.getWorker().getId(), Reservation.NONE);
    }

    private double reservedCpuPercent(WorkerNode node, Reservation reserved) {
        if (node.getTotalCpuCores() <= 0) return 0.0;
        return ((double) reserved.cpuMillicores() / (node.getTotalCpuCores() * 1000)) * 100.0;
    }
}
