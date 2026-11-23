package com.pubtrack.service;

import com.pubtrack.db.Database;
import com.pubtrack.exception.ForbiddenException;
import com.pubtrack.exception.UnauthorizedException;
import com.pubtrack.model.Role;
import com.pubtrack.model.User;
import com.pubtrack.repository.StatsRepository;
import com.pubtrack.repository.UserRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the editor dashboard. */
public class StatsService {

    private final Database db;
    private final UserRepository users;
    private final StatsRepository stats;

    public StatsService(Database db, UserRepository users, StatsRepository stats) {
        this.db = db;
        this.users = users;
        this.stats = stats;
    }

    public Map<String, Object> dashboard(int actingUserId) {
        return db.withConnection(conn -> {
            requireEditor(conn, actingUserId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("journals", stats.journalStats(conn));
            result.put("reviewers", stats.reviewerStats(conn));
            result.put("overdue", stats.overdueReviews(conn));
            return result;
        });
    }

    private void requireEditor(Connection conn, int userId) throws SQLException {
        User u = users.findById(conn, userId).orElseThrow(() -> new UnauthorizedException("Unknown user " + userId));
        if (!u.is(Role.EDITOR)) {
            throw new ForbiddenException("Only editors can see the dashboard");
        }
    }
}
