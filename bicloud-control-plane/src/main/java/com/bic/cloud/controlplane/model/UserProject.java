package com.bic.cloud.controlplane.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
    name = "user_projects",
    // GLOBALLY unique, not per owner: the Docker network, gateway route key,
    // mesh path and service discovery all derive from the project name, so two
    // owners sharing a name would share an isolation boundary (cross-tenant
    // traffic). The name IS the tenant identity in the current design.
    uniqueConstraints = @UniqueConstraint(
        name = "uq_project_name",
        columnNames = {"name"}
    )
)
public class UserProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private BicloudUser owner;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate(){
        this.createdAt = Instant.now();
    }
}
