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

@Slf4j
@Service
public class ContainerMetricsService {

    private static final int MAX_POINTS = 90;
    private static final long STALE_AFTER_SECONDS = 600;

    public record MetricPoint(Instant at, double cpuPercent, long memoryUsedMb, long memoryLimitMb) {}

    // key: dockerContainerId
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

    public MetricPoint getLatest(String dockerContainerId) {
        Deque<MetricPoint> series = history.get(dockerContainerId);
        if (series == null) return null;
        synchronized (series) {
            return series.peekLast();
        }
    }

    public List<MetricPoint> getHistory(String dockerContainerId) {
        Deque<MetricPoint> series = history.get(dockerContainerId);
        if (series == null) return List.of();
        synchronized (series) {
            return new ArrayList<>(series);
        }
    }

    public void evict(String dockerContainerId) {
        if (dockerContainerId != null) {
            history.remove(dockerContainerId);
        }
    }

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
