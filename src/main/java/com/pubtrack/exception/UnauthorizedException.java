package com.pubtrack.exception;

/** No (valid) acting user was supplied (HTTP 401). */
public class UnauthorizedException extends AppException {
    public UnauthorizedException(String message) {
        super(401, message);
    }
}
