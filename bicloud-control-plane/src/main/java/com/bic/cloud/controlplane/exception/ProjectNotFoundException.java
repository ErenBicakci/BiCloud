package com.bic.cloud.controlplane.exception;

public class ProjectNotFoundException extends BaseException {

    public ProjectNotFoundException(Long projectId) {
        super("PROJECT_NOT_FOUND", "Project not found with id: " + projectId);
    }

    public ProjectNotFoundException(String projectName) {
        super("PROJECT_NOT_FOUND", "Project not found: " + projectName);
    }
}
