package com.bic.cloud.controlplane.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.Map;

/**
 * Configuration update for an existing service (ProjectImage).
 * serviceName is immutable: mesh/gateway routes and Docker aliases are
 * bound to it. The replica count is managed via the scale endpoint.
 */
@Data
public class UpdateProjectImageDto {

    @NotBlank(message = "imageName is required")
    private String imageName;

    @Min(value = 1,     message = "containerPort must be at least 1.")
    @Max(value = 65535, message = "containerPort may be at most 65535.")
    private int containerPort;

    @Size(max = 50, message = "At most 50 env vars can be defined.")
    private Map<
        @NotBlank(message = "Env var key must not be blank.")
        @Size(max = 256, message = "Env var key may be at most 256 characters.")
        @Pattern(
            regexp = "^(?!BICLOUD_)[a-zA-Z_][a-zA-Z0-9_.]*$",
            message = "Env var key may only contain letters/digits/underscores/dots, " +
                      "must not start with a digit; the BICLOUD_ prefix is reserved."
        )
        String,
        @NotNull(message = "Env var value must not be null.")
        @Size(max = 4096, message = "Env var value may be at most 4 KB.")
        String
    > environmentVariables;

    @NotNull(message = "memoryLimitMb is required.")
    @Min(value = 64,   message = "memoryLimitMb must be at least 64 MB.")
    @Max(value = 4096, message = "memoryLimitMb may be at most 4096 MB (4 GB).")
    private Integer memoryLimitMb;

    @NotNull(message = "cpuLimit is required.")
    @DecimalMin(value = "0.1", message = "cpuLimit must be at least 0.1 cores.")
    @DecimalMax(value = "4.0", message = "cpuLimit may be at most 4.0 cores.")
    private Double cpuLimit;

    private Boolean autoscalingEnabled;

    @Min(value = 1, message = "minReplicas must be at least 1.")
    @Max(value = 10, message = "minReplicas may be at most 10.")
    private Integer minReplicas;

    @Min(value = 1, message = "maxReplicas must be at least 1.")
    @Max(value = 10, message = "maxReplicas may be at most 10.")
    private Integer maxReplicas;

    @Min(value = 1, message = "targetCpuPercent must be at least 1.")
    @Max(value = 100, message = "targetCpuPercent may be at most 100.")
    private Integer targetCpuPercent;

    @Min(value = 1, message = "scaleDownCpuPercent must be at least 1.")
    @Max(value = 99, message = "scaleDownCpuPercent may be at most 99.")
    private Integer scaleDownCpuPercent;

    @Min(value = 15, message = "scaleUpCooldownSeconds must be at least 15.")
    @Max(value = 3600, message = "scaleUpCooldownSeconds may be at most 3600.")
    private Integer scaleUpCooldownSeconds;

    @Min(value = 15, message = "scaleDownCooldownSeconds must be at least 15.")
    @Max(value = 3600, message = "scaleDownCooldownSeconds may be at most 3600.")
    private Integer scaleDownCooldownSeconds;

    /** Egress opt-in; only admins may change it (checked in the service layer). */
    private boolean allowInternet;

    /** Ingress opt-in: whether this service accepts external host-based gateway traffic. */
    private boolean exposeExternally;
}
