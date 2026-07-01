package com.bic.cloud.bicloud_gateway.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Instance removal request coming from the control plane to the gateway.
 *
 * Used when the control plane stops or deletes a container to remove the
 * instance from the load-balancer pool.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeregisterInstanceRequest {

    /** Project name - selects which route pool to remove from. */
    @NotBlank(message = "projectName is required")
    @Pattern(regexp = "^[a-z0-9][a-z0-9_-]{0,62}$",
            message = "projectName may only contain lowercase letters, digits, hyphens and underscores")
    private String projectName;

    /** Service name - fully identifies the route pool. */
    @NotBlank(message = "serviceName is required")
    @Pattern(regexp = "^[a-z0-9][a-z0-9_-]{0,62}$",
            message = "serviceName may only contain lowercase letters, digits, hyphens and underscores")
    private String serviceName;

    /** Docker internal IP of the container to remove. */
    @NotBlank(message = "instanceIp is required")
    private String instanceIp;

    /** Container port number. */
    @Min(value = 1,     message = "port must be between 1 and 65535")
    @Max(value = 65535, message = "port must be between 1 and 65535")
    private int port;
}
