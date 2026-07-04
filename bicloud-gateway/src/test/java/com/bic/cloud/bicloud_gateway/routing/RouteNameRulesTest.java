package com.bic.cloud.bicloud_gateway.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RouteNameRulesTest {

    @Test
    @DisplayName("project names match the control-plane naming policy")
    void projectNamesMatchControlPlanePolicy() {
        assertThat(RouteNameRules.isProjectName("alpha")).isTrue();
        assertThat(RouteNameRules.isProjectName("alpha-1")).isTrue();

        assertThat(RouteNameRules.isProjectName("a")).isFalse();
        assertThat(RouteNameRules.isProjectName("1alpha")).isFalse();
        assertThat(RouteNameRules.isProjectName("alpha_1")).isFalse();
        assertThat(RouteNameRules.isProjectName("alpha-")).isFalse();
        assertThat(RouteNameRules.isProjectName("bicloud-alpha")).isFalse();
        assertThat(RouteNameRules.isProjectName("egress-alpha")).isFalse();
    }

    @Test
    @DisplayName("service names match the control-plane naming policy")
    void serviceNamesMatchControlPlanePolicy() {
        assertThat(RouteNameRules.isServiceName("api")).isTrue();
        assertThat(RouteNameRules.isServiceName("api-1")).isTrue();

        assertThat(RouteNameRules.isServiceName("a")).isFalse();
        assertThat(RouteNameRules.isServiceName("1api")).isFalse();
        assertThat(RouteNameRules.isServiceName("api_1")).isFalse();
        assertThat(RouteNameRules.isServiceName("api-")).isFalse();
        assertThat(RouteNameRules.isServiceName("bicloud-api")).isFalse();
    }
}
