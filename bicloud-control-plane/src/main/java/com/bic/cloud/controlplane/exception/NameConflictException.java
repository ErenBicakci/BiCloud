package com.bic.cloud.controlplane.exception;

public class NameConflictException extends BaseException {

    public NameConflictException(String message) {
        super("NAME_CONFLICT", message);
    }

    public static NameConflictException projectName(String name) {
        return new NameConflictException("Project name is already taken: " + name);
    }

    public static NameConflictException serviceName(String name) {
        return new NameConflictException("Service name already exists in this project: " + name);
    }
}
