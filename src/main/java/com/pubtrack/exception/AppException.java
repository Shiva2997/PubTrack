package com.pubtrack.exception;

/**
 * Base class for errors we expect and can explain to the user.
 * Each subclass carries the HTTP status the API should answer with.
 */
public abstract class AppException extends RuntimeException {

    private final int httpStatus;

    protected AppException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
