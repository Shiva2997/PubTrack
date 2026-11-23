package com.pubtrack.db;

import java.sql.SQLException;

/**
 * Unchecked wrapper around {@link SQLException} so service code is not cluttered with
 * {@code throws SQLException}. Keeps the SQLState so callers can recognise e.g. constraint
 * violations (SQLState class "23").
 */
public class DataAccessException extends RuntimeException {

    private final String sqlState;

    public DataAccessException(String message, SQLException cause) {
        super(message, cause);
        this.sqlState = cause == null ? null : cause.getSQLState();
    }

    public boolean isConstraintViolation() {
        return sqlState != null && sqlState.startsWith("23");
    }
}
