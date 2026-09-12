package com.bic.cloud.controlplane.exception;

public class UserHasProjectsException extends BaseException {

    public UserHasProjectsException(long projectCount) {
        super("USER_HAS_PROJECTS",
                "User cannot be deleted: still owns " + projectCount
                        + " project(s). Delete or transfer them first.");
    }
}
