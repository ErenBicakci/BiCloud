package com.bic.cloud.controlplane.exception;

import java.util.UUID;

public class ContainerInstanceNotFoundException extends BaseException {

    public ContainerInstanceNotFoundException(UUID instanceId) {
        super("CONTAINER_INSTANCE_NOT_FOUND",
                "Container instance not found with id: " + instanceId);
    }

    public ContainerInstanceNotFoundException(String dockerContainerId) {
        super("CONTAINER_INSTANCE_NOT_FOUND",
                "Container instance not found for docker id: " + dockerContainerId);
    }
}
