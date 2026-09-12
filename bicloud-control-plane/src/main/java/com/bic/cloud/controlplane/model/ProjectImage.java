package com.bic.cloud.controlplane.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "project_images",
       uniqueConstraints = @UniqueConstraint(
           name = "uq_service_per_project",
           columnNames = {"project_id", "service_name"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private UserProject project;

    @Column(nullable = false)
    private String serviceName;

    @Column(nullable = false)
    private String imageName;

    @Column(nullable = false)
    private int desiredReplicas;

    @Column(name = "autoscaling_enabled", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean autoscalingEnabled = false;

    @Column(name = "min_replicas", nullable = false, columnDefinition = "integer default 1")
    @Builder.Default
    private int minReplicas = 1;

    @Column(name = "max_replicas", nullable = false, columnDefinition = "integer default 3")
    @Builder.Default
    private int maxReplicas = 3;

    @Column(name = "target_cpu_percent", nullable = false, columnDefinition = "integer default 70")
    @Builder.Default
    private int targetCpuPercent = 70;

    @Column(name = "scale_down_cpu_percent", nullable = false, columnDefinition = "integer default 30")
    @Builder.Default
    private int scaleDownCpuPercent = 30;

    @Column(name = "scale_up_cooldown_seconds", nullable = false, columnDefinition = "integer default 60")
    @Builder.Default
    private int scaleUpCooldownSeconds = 60;

    @Column(name = "scale_down_cooldown_seconds", nullable = false, columnDefinition = "integer default 300")
    @Builder.Default
    private int scaleDownCooldownSeconds = 300;

    @Column(name = "last_autoscaled_at")
    private Instant lastAutoscaledAt;

    @Column(nullable = false)
    private int containerPort;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "project_image_env_vars", joinColumns = @JoinColumn(name = "project_image_id"))
    @MapKeyColumn(name = "env_key")
    @Column(name = "env_value")
    private Map<String, String> environmentVariables;

    @Column(name = "memory_limit_mb")
    private Integer memoryLimitMb;

    @Column(name = "cpu_limit")
    private Double cpuLimit;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "consecutive_deploy_failures", nullable = false, columnDefinition = "integer default 0")
    @Builder.Default
    private int consecutiveDeployFailures = 0;

    @Column(name = "last_deploy_failure_at")
    private Instant lastDeployFailureAt;

    @Column(name = "stopped_by_user", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean stoppedByUser = false;

    @Column(name = "allow_internet", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean allowInternet = false;

    @Column(name = "expose_externally", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean exposeExternally = false;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
