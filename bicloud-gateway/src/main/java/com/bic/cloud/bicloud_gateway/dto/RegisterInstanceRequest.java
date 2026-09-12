package com.bic.cloud.bicloud_gateway.dto;

import com.bic.cloud.bicloud_gateway.routing.RouteNameRules;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterInstanceRequest {

    @NotNull(message = "projectId is required")
    private Long projectId;

    // project and service names are used when parsing the Host header
    @NotBlank(message = "projectName is required")
    @Size(min = 2, max = 50, message = "projectName must be between 2 and 50 characters")
    @Pattern(regexp = RouteNameRules.PROJECT_NAME_REGEX,
            message = "projectName must match the control-plane project naming policy")
    private String projectName;

    @NotBlank(message = "serviceName is required")
    @Size(min = 2, max = 50, message = "serviceName must be between 2 and 50 characters")
    @Pattern(regexp = RouteNameRules.SERVICE_NAME_REGEX,
            message = "serviceName must match the control-plane service naming policy")
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
