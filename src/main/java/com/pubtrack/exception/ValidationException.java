package com.pubtrack.exception;

/** The request was understood but its data is invalid (HTTP 400). */
public class ValidationException extends AppException {
    public ValidationException(String message) {
        super(400, message);
    }
}
