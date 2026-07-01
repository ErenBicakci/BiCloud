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
@Table(name = "worker_nodes")
public class WorkerNode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String workerName;

    private String workerVersion;

    @Column(nullable = false)
    private int totalCpuCores;

    @Column(nullable = false)
    private long totalMemoryMb;

    @Column(nullable = false)
    private int serverPort;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}