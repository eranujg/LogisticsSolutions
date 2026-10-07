package com.aadvixon.tms.platform.security;

/** The signed-in user lacks the permission for this action (HTTP 403). */
public class PermissionDeniedException extends RuntimeException {

    public PermissionDeniedException(String permission) {
        super("You do not have permission: " + permission);
    }
}
