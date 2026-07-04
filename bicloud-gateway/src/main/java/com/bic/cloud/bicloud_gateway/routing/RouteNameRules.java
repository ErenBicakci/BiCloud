package com.bic.cloud.bicloud_gateway.routing;

import java.util.regex.Pattern;

public final class RouteNameRules {

    public static final String PROJECT_NAME_REGEX =
            "^(?!bicloud-)(?!egress-)[a-z](?:[a-z0-9-]{0,48}[a-z0-9])$";

    public static final String SERVICE_NAME_REGEX =
            "^(?!bicloud-)[a-z](?:[a-z0-9-]{0,48}[a-z0-9])$";

    private static final Pattern PROJECT_NAME_PATTERN = Pattern.compile(PROJECT_NAME_REGEX);
    private static final Pattern SERVICE_NAME_PATTERN = Pattern.compile(SERVICE_NAME_REGEX);

    private RouteNameRules() {
    }

    public static boolean isProjectName(String value) {
        return hasSupportedLength(value) && PROJECT_NAME_PATTERN.matcher(value).matches();
    }

    public static boolean isServiceName(String value) {
        return hasSupportedLength(value) && SERVICE_NAME_PATTERN.matcher(value).matches();
    }

    private static boolean hasSupportedLength(String value) {
        return value != null && value.length() >= 2 && value.length() <= 50;
    }
}
