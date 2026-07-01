package com.bic.cloud.controlplane.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "worker_states", indexes = {
        @Index(name = "idx_worker_state_status", columnList = "status"),
        @Index(name = "idx_worker_state_last_heartbeat", columnList = "lastHeartbeat"),
        @Index(name = "idx_worker_state_status_heartbeat", columnList = "status, lastHeartbeat")
})
public class WorkerState {

    @Id
    private UUID workerId;

    @OneToOne
    @MapsId
    @JoinColumn(name = "worker_id")
    private WorkerNode worker;

    private String ipAddress;

    private String meshIp;

    private int cpuUsagePercent;

    private long usedMemoryMb;

    @Enumerated(EnumType.STRING)
    private NodeStatus status;

    private Instant lastHeartbeat;

    public enum NodeStatus {
        REGISTERING,
        ACTIVE,
        OVERLOADED,
        OFFLINE,
        MAINTENANCE
    }
}
