package com.pubtrack.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal thread-safe JDBC connection pool plus helpers for running work
 * with or without a transaction.
 *
 * <p>Connections are created lazily up to {@code maxSize}. Idle connections wait in a
 * {@link BlockingQueue}; when all are busy a caller waits up to 5 seconds for one to be
 * returned. Every borrowed connection is checked with {@link Connection#isValid(int)} so a
 * connection dropped by MySQL (e.g. after wait_timeout) is replaced transparently.
 */
public final class Database implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Database.class.getName());
    private static final long BORROW_TIMEOUT_SECONDS = 5;

    private final String url;
    private final String user;
    private final String password;
    private final int maxSize;
    private final BlockingQueue<Connection> idle;
    private final AtomicInteger created = new AtomicInteger();
    private volatile boolean closed;

    public Database(String url, String user, String password, int maxSize) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("Pool size must be at least 1");
        }
        this.url = url;
        this.user = user;
        this.password = password;
        this.maxSize = maxSize;
        this.idle = new ArrayBlockingQueue<>(maxSize);
    }

    /** Run work on a connection in auto-commit mode (one statement = one transaction). */
    public <T> T withConnection(SqlWork<T> work) {
        Connection c = borrow();
        try {
            return work.run(c);
        } catch (SQLException e) {
            throw new DataAccessException("Database error: " + e.getMessage(), e);
        } finally {
            release(c);
        }
    }

    /**
     * Run work inside a single transaction. Commits if the work returns normally;
     * rolls back if it throws anything (SQL errors and business-rule exceptions alike).
     */
    public <T> T inTransaction(SqlWork<T> work) {
        Connection c = borrow();
        try {
            c.setAutoCommit(false);
            T result = work.run(c);
            c.commit();
            return result;
        } catch (SQLException e) {
            rollbackQuietly(c);
            throw new DataAccessException("Database error: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            rollbackQuietly(c);
            throw e;
        } finally {
            try {
                c.setAutoCommit(true);
            } catch (SQLException e) {
                LOG.log(Level.WARNING, "Could not reset auto-commit", e);
            }
            release(c);
        }
    }

    private Connection borrow() {
        if (closed) {
            throw new IllegalStateException("Database pool is closed");
        }
        try {
            Connection c = idle.poll();
            if (c == null) {
                if (created.incrementAndGet() <= maxSize) {
                    return openNew();
                }
                created.decrementAndGet();
                c = idle.poll(BORROW_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (c == null) {
                    throw new DataAccessException("Timed out waiting for a free database connection", null);
                }
            }
            if (!c.isValid(2)) {
                closeQuietly(c);
                return openNew(); // replaces the dead connection; count is unchanged
            }
            return c;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataAccessException("Interrupted while waiting for a database connection", null);
        } catch (SQLException e) {
            throw new DataAccessException("Could not obtain a database connection: " + e.getMessage(), e);
        }
    }

    private Connection openNew() throws SQLException {
        try {
            return DriverManager.getConnection(url, user, password);
        } catch (SQLException e) {
            created.decrementAndGet();
            throw e;
        }
    }

    private void release(Connection c) {
        if (closed || !idle.offer(c)) {
            closeQuietly(c);
            created.decrementAndGet();
        }
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Rollback failed", e);
        }
    }

    private static void closeQuietly(Connection c) {
        try {
            c.close();
        } catch (SQLException ignored) {
            // nothing useful to do
        }
    }

    @Override
    public void close() {
        closed = true;
        Connection c;
        while ((c = idle.poll()) != null) {
            closeQuietly(c);
        }
    }
}
