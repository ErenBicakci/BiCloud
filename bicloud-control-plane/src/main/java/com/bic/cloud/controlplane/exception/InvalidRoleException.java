package com.bic.cloud.controlplane.exception;

/**
 * Service-level defense: role can only be {ADMIN, USER}.
 * (The HTTP path already returns 400 via ChangeRoleRequest @Pattern; this
 * stops an invalid role reaching the DB when the service is called directly.)
 */
public class InvalidRoleException extends BaseException {

    public InvalidRoleException(String role) {
        super("INVALID_ROLE", "Invalid role: '" + role + "'. Allowed: ADMIN, USER.");
    }
}
