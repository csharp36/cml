package com.indexer.auth;

public class PermissionResolutionException extends RuntimeException {
    public PermissionResolutionException(String message) {
        super(message);
    }

    public PermissionResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
