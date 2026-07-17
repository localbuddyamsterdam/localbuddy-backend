package com.localbuddy.common.exception;

/**
 * The caller is authenticated but not allowed to perform this action (403) —
 * e.g. an admin trying to manage super admin accounts, or a super admin trying
 * to remove themselves. Mapped by {@link GlobalExceptionHandler}.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
