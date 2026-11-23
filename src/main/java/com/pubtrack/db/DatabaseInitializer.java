package com.pubtrack.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** Creates the tables on start-up and loads demo data into an empty database. */
public final class DatabaseInitializer {

    private static final Logger LOG = Logger.getLogger(DatabaseInitializer.class.getName());

    private DatabaseInitializer() {
    }

    public static void initialize(Database db, boolean loadSeedData) {
        db.withConnection(conn -> {
            runScript(conn, "/db/schema.sql");
            if (loadSeedData && countUsers(conn) == 0) {
                runScript(conn, "/db/seed.sql");
                LOG.info("Loaded demo data");
            }
            return null;
        });
    }

    private static long countUsers(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Executes a classpath SQL file statement by statement. */
    public static void runScript(Connection conn, String resource) throws SQLException {
        try (Statement st = conn.createStatement()) {
            for (String sql : splitStatements(readResource(resource))) {
                st.execute(sql);
            }
        }
    }

    /** Splits a script on ';' after removing full-line "--" comments. Scripts must not use ';' inside strings. */
    static List<String> splitStatements(String script) {
        StringBuilder withoutComments = new StringBuilder();
        for (String line : script.split("\\R")) {
            if (!line.trim().startsWith("--")) {
                withoutComments.append(line).append('\n');
            }
        }
        List<String> statements = new ArrayList<>();
        for (String part : withoutComments.toString().split(";")) {
            if (!part.isBlank()) {
                statements.add(part.trim());
            }
        }
        return statements;
    }

    private static String readResource(String resource) {
        try (InputStream in = DatabaseInitializer.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + resource, e);
        }
    }
}
