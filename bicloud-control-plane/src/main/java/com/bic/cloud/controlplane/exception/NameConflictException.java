package com.bic.cloud.controlplane.exception;

/**
 * A project or service name is already in use. Project names are globally
 * unique because every isolation identity (Docker network, gateway route,
 * mesh path, discovery) derives from them; service names are unique within
 * their project because the route key is projectName:serviceName.
 */
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
