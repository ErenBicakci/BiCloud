package com.bic.cloud.controlplane.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Map;

@Entity
// service name unique within its project: the gateway route key is
// projectName:serviceName, duplicates would merge two services into one route
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


     // self-healing backoff: consecutive failed deploy attempts. reset to 0 on success

    @Column(name = "consecutive_deploy_failures", nullable = false, columnDefinition = "integer default 0")
    @Builder.Default
    private int consecutiveDeployFailures = 0;

    /**
     * Time of the last failed deploy attempt. Used for the cooldown window.
     */
    @Column(name = "last_deploy_failure_at")
    private Instant lastDeployFailureAt;

    /**
     * True after an explicit undeploy: self-healing must not resurrect the
     * service. desiredReplicas keeps the configured count so a later deploy
     * can restore it. Cleared by deploy/scale/update.
     */
    @Column(name = "stopped_by_user", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean stoppedByUser = false;

    /**
     * Egress opt-in: when true the worker also attaches the container to an
     * internet-capable bridge; otherwise it lives only on the internal project
     * network. Only admins may enable it (enforced in the service layer).
     */
    @Column(name = "allow_internet", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean allowInternet = false;

    /**
     * Ingress exposure: when false the service remains reachable over the
     * internal mesh, but host-based north-south gateway traffic is rejected.
     * This is intentionally separate from allowInternet, which controls egress.
     */
    @Column(name = "expose_externally", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean exposeExternally = false;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
