package com.bic.cloud.controlplane.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "container_instances")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContainerInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "docker_container_id", unique = true)
    private String dockerContainerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_image_id", nullable = false)
    private ProjectImage projectImage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "worker_node_id", nullable = false)
    private WorkerNode workerNode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InstanceStatus status;

    /**
     * The container's internal IP on the project Docker network (172.x.x.x).
     * The gateway routes traffic to this IP.
     * Bilinmiyorsa null olabilir.
     */
    @Column(name = "container_ip")
    private String containerIp;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * When the container actually reached RUNNING. createdAt marks the PENDING
     * reservation, which can precede this by minutes (image pull) - liveness
     * grace periods must use this, not createdAt.
     */
    @Column(name = "started_at")
    private Instant startedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        if (this.status == null) {
            this.status = InstanceStatus.PENDING;
        }
    }

    public enum InstanceStatus {
        PENDING,
        RUNNING,
        STOPPED,
        FAILED
    }
}