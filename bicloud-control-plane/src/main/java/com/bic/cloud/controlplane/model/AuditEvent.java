package com.bic.cloud.controlplane.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "audit_events", indexes = {
        @Index(name = "idx_audit_owner",   columnList = "ownerName"),
        @Index(name = "idx_audit_project", columnList = "projectId"),
        @Index(name = "idx_audit_created", columnList = "createdAt")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ActorType actorType;

    @Column(nullable = false, length = 100)
    private String actorName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private TargetType targetType;

    @Column(length = 200)
    private String targetName;

    private Long projectId;

    @Column(length = 100)
    private String ownerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Severity severity;

    @Column(length = 500)
    private String message;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) this.createdAt = Instant.now();
        if (this.severity == null)  this.severity = Severity.INFO;
    }

    public enum ActorType { USER, SYSTEM }

    public enum TargetType { PROJECT, SERVICE, CONTAINER, WORKER }

    public enum Severity { INFO, WARN }

    public enum AuditAction {
        // Project
        PROJECT_CREATED,
        PROJECT_DELETED,
        PROJECT_DEPLOYED,
        PROJECT_UNDEPLOYED,
        // Service
        SERVICE_CREATED,
        SERVICE_UPDATED,
        SERVICE_DELETED,
        SERVICE_SCALED,
        AUTOSCALING_SCALED,
        // Container (user)
        CONTAINER_STOPPED,
        CONTAINER_REMOVED,
        // System - self-healing & reconciliation
        SELF_HEALING_DEPLOY,
        CRASH_LOOP_DETECTED,
        CONTAINER_RECOVERED,
        ZOMBIE_DETECTED,
        EXCESS_SCALED_DOWN,
        // System - worker
        WORKER_OFFLINE,
        WORKER_MAINTENANCE
    }
}
