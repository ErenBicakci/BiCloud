package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.dto.ContainerStatsDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps container metrics arriving with worker snapshots in memory.
 *
 * Design note: metrics are operational/observational data; they need no
 * persistence. History resets on CP restart and refills on its own since
 * workers push fresh data every 20s.
 * Ring buffer: last 90 points per container, roughly a 30-minute window.
 */
@Slf4j
@Service
public class ContainerMetricsService {

    private static final int MAX_POINTS = 90;
    private static final long STALE_AFTER_SECONDS = 600; // series silent for 10 min get dropped

    public record MetricPoint(Instant at, double cpuPercent, long memoryUsedMb, long memoryLimitMb) {}

    /** key: dockerContainerId - not the instance UUID; the worker only knows Docker IDs. */
    private final Map<String, Deque<MetricPoint>> history = new ConcurrentHashMap<>();

    public void record(List<ContainerStatsDto> stats) {
        if (stats == null || stats.isEmpty()) return;

        Instant now = Instant.now();
        for (ContainerStatsDto s : stats) {
            if (s.getDockerContainerId() == null) continue;
            Deque<MetricPoint> series = history.computeIfAbsent(
                    s.getDockerContainerId(), k -> new ArrayDeque<>());
            synchronized (series) {
                series.addLast(new MetricPoint(now, s.getCpuPercent(), s.getMemoryUsedMb(), s.getMemoryLimitMb()));
                while (series.size() > MAX_POINTS) {
                    series.removeFirst();
                }
            }
        }
    }

    /** Latest sample; null when there is no data. */
    public MetricPoint getLatest(String dockerContainerId) {
        Deque<MetricPoint> series = history.get(dockerContainerId);
        if (series == null) return null;
        synchronized (series) {
            return series.peekLast();
        }
    }

    /** History ordered oldest to newest; empty list when there is no data. */
    public List<MetricPoint> getHistory(String dockerContainerId) {
        Deque<MetricPoint> series = history.get(dockerContainerId);
        if (series == null) return List.of();
        synchronized (series) {
            return new ArrayList<>(series);
        }
    }

    /** Drops the series of removed/stopped containers immediately. */
    public void evict(String dockerContainerId) {
        if (dockerContainerId != null) {
            history.remove(dockerContainerId);
        }
    }

    /** Evicts series that received no data for 10 minutes (container is gone). */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void evictStaleSeries() {
        Instant cutoff = Instant.now().minusSeconds(STALE_AFTER_SECONDS);
        int before = history.size();
        history.entrySet().removeIf(e -> {
            MetricPoint last = e.getValue().peekLast();
            return last == null || last.at().isBefore(cutoff);
        });
        int removed = before - history.size();
        if (removed > 0) {
            log.debug("[Metrics] Evicted {} stale container metric series.", removed);
        }
    }
}
