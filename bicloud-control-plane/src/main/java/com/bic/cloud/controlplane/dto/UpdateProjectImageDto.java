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

    /** Egress opt-in; only admins may change it (checked in the service layer). */
    private boolean allowInternet;

    /** Ingress opt-in: whether this service accepts external host-based gateway traffic. */
    private boolean exposeExternally;
}
