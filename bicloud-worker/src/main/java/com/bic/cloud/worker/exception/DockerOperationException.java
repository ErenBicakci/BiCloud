package com.bic.cloud.worker.exception;

public class DockerOperationException extends RuntimeException {

    private final String containerId;

    public DockerOperationException(String containerId, String operation, Throwable cause) {
        super("Docker " + operation + " failed for container: " + containerId, cause);
        this.containerId = containerId;
    }

    public String getContainerId() {
        return containerId;
    }
}
