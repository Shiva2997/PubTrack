package com.pubtrack.exception;

/** The acting user exists but is not allowed to do this (HTTP 403). */
public class ForbiddenException extends AppException {
    public ForbiddenException(String message) {
        super(403, message);
    }
}
