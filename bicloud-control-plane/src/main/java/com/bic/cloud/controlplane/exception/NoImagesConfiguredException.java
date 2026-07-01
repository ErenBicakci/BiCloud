package com.bic.cloud.controlplane.exception;

public class NoImagesConfiguredException extends BaseException {

    public NoImagesConfiguredException(String projectName) {
        super("NO_IMAGES_CONFIGURED",
                "No images configured for project: " + projectName);
    }
}
