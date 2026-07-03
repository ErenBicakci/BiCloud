package com.bic.cloud.bicloud_gateway.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Instance registration request coming from the control plane to the gateway.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterInstanceRequest {

    @NotNull(message = "projectId is required")
    private Long projectId;

    // project and service names are used when parsing the Host header
    @NotBlank(message = "projectName is required")
    @Pattern(regexp = "^[a-z0-9][a-z0-9_-]{0,62}$",
            message = "projectName may only contain lowercase letters, digits, hyphens and underscores")
    private String projectName;

    @NotBlank(message = "serviceName is required")
    @Pattern(regexp = "^[a-z0-9][a-z0-9_-]{0,62}$",
            message = "serviceName may only contain lowercase letters, digits, hyphens and underscores")
    private String serviceName;

    // container's Docker internal network IP (e.g. 172.18.0.5)
    @NotBlank(message = "instanceIp is required")
    private String instanceIp;

    @Min(value = 1, message = "port must be between 1 and 65535")
    @Max(value = 65535, message = "port must be between 1 and 65535")
    private int port;

    // ContainerInstance UUID on the CP (optional, for tracing)
    private String instanceId;

    // Service-level ingress policy. false keeps mesh traffic working, but blocks external Host routing.
    private boolean exposeExternally;
}
