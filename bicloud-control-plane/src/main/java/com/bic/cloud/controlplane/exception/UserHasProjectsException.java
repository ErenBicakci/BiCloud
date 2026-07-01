package com.bic.cloud.controlplane.exception;

/**
 * Thrown when deleting a user who still owns projects.
 * UserProject.owner_id is NOT NULL with no cascade, so a direct delete
 * would 500 with an FK violation; we return a meaningful 409 instead.
 */
public class UserHasProjectsException extends BaseException {

    public UserHasProjectsException(long projectCount) {
        super("USER_HAS_PROJECTS",
                "User cannot be deleted: still owns " + projectCount
                        + " project(s). Delete or transfer them first.");
    }
}
