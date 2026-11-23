package com.pubtrack.exception;

/** The requested record does not exist (HTTP 404). */
public class NotFoundException extends AppException {
    public NotFoundException(String message) {
        super(404, message);
    }
}
