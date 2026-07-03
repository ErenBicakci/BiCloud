package com.bic.cloud.controlplane.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.Map;

@Data
public class CreateProjectImageDto {

    @NotNull(message = "projectId is required")
    private Long projectId;

    /**
     * The service name doubles as the Docker network alias.
     * Follows the same naming rules as the project name.
     */
    @NotBlank(message = "Service name must not be blank.")
    @Size(min = 2, max = 50,
          message = "Service name must be between 2 and 50 characters.")
    @Pattern(
            regexp = "^(?!bicloud-)[a-z](?:[a-z0-9-]{0,48}[a-z0-9])$",
            message = "Service name may only contain lowercase letters, digits and hyphens (-); " +
                      "it must start with a letter, must not end with a hyphen " +
                      "and must not start with the reserved 'bicloud-' prefix."
    )
    private String serviceName;

    @NotBlank(message = "imageName is required")
    private String imageName;

    @Min(value = 1,  message = "desiredReplicas must be at least 1.")
    @Max(value = 10, message = "desiredReplicas may be at most 10.")
    private int desiredReplicas;

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

    /** Egress opt-in; only admins may set it to true (checked in the service layer). */
    private boolean allowInternet;

    /** Ingress opt-in: whether this service accepts external host-based gateway traffic. */
    private boolean exposeExternally;
}
