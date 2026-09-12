package com.bic.cloud.controlplane.exception;

public class InvalidRoleException extends BaseException {

    public InvalidRoleException(String role) {
        super("INVALID_ROLE", "Invalid role: '" + role + "'. Allowed: ADMIN, USER.");
    }
}
