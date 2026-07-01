package com.bic.cloud.controlplane.exception;

public class ProjectImageNotFoundException extends BaseException {

    public ProjectImageNotFoundException(Long imageId) {
        super("IMAGE_NOT_FOUND", "Project image not found with id: " + imageId);
    }
}
