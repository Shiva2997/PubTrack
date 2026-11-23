package com.pubtrack.exception;

/** The request clashes with the current state of the data, e.g. a duplicate (HTTP 409). */
public class ConflictException extends AppException {
    public ConflictException(String message) {
        super(409, message);
    }
}
