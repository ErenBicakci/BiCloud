package com.bic.cloud.controlplane.exception;

public class ForbiddenException extends BaseException {

    public ForbiddenException() {
        super("FORBIDDEN", "You do not have permission to access this resource");
    }

    public ForbiddenException(String message) {
        super("FORBIDDEN", message);
    }
}
