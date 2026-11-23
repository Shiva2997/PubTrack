package com.pubtrack.repository;

import com.pubtrack.db.Rows;
import com.pubtrack.model.OverdueReminder;
import com.pubtrack.model.Recommendation;
import com.pubtrack.model.ReviewAssignment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ReviewRepository {

    private static final String SELECT = """
            SELECT ra.id, ra.manuscript_id, m.title AS manuscript_title, ra.reviewer_id,
                   u.full_name AS reviewer_name, ra.round, ra.assigned_at, ra.due_at, ra.completed_at,
                   ra.recommendation, ra.comments,
                   (ra.completed_at IS NULL AND ra.due_at < NOW()) AS overdue
              FROM review_assignments ra
              JOIN manuscripts m ON m.id = ra.manuscript_id
              JOIN users u       ON u.id = ra.reviewer_id
            """;

    public void insert(Connection conn, int manuscriptId, int reviewerId, int round, int reviewDays)
            throws SQLException {
        String sql = "INSERT INTO review_assignments (manuscript_id, reviewer_id, round, assigned_at, due_at) "
                + "VALUES (?, ?, ?, NOW(), NOW() + INTERVAL ? DAY)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, manuscriptId);
            ps.setInt(2, reviewerId);
            ps.setInt(3, round);
            ps.setInt(4, reviewDays);
            ps.executeUpdate();
        }
    }

    public Optional<ReviewAssignment> findById(Connection conn, int id, boolean forUpdate) throws SQLException {
        String sql = SELECT + " WHERE ra.id = ?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<ReviewAssignment> findByManuscript(Connection conn, int manuscriptId) throws SQLException {
        return list(conn, SELECT + " WHERE ra.manuscript_id = ? ORDER BY ra.round, ra.id", manuscriptId);
    }

    public List<ReviewAssignment> findByReviewer(Connection conn, int reviewerId) throws SQLException {
        // Pending first (soonest due first), then completed (most recent first).
        return list(conn, SELECT + " WHERE ra.reviewer_id = ?"
                + " ORDER BY ra.completed_at IS NOT NULL, ra.due_at, ra.completed_at DESC", reviewerId);
    }

    public boolean isAssigned(Connection conn, int manuscriptId, int reviewerId, Integer round) throws SQLException {
        String sql = "SELECT 1 FROM review_assignments WHERE manuscript_id = ? AND reviewer_id = ?"
                + (round == null ? "" : " AND round = ?");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, manuscriptId);
            ps.setInt(2, reviewerId);
            if (round != null) {
                ps.setInt(3, round);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Returns {completed, pending} review counts for one round of one manuscript. */
    public int[] countByState(Connection conn, int manuscriptId, int round) throws SQLException {
        String sql = "SELECT COALESCE(SUM(completed_at IS NOT NULL), 0), COALESCE(SUM(completed_at IS NULL), 0) "
                + "FROM review_assignments WHERE manuscript_id = ? AND round = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, manuscriptId);
            ps.setInt(2, round);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new int[] {rs.getInt(1), rs.getInt(2)};
            }
        }
    }

    public void complete(Connection conn, int assignmentId, Recommendation recommendation, String comments)
            throws SQLException {
        String sql = "UPDATE review_assignments SET completed_at = NOW(), recommendation = ?, comments = ? "
                + "WHERE id = ? AND completed_at IS NULL";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, recommendation.name());
            ps.setString(2, comments);
            ps.setInt(3, assignmentId);
            ps.executeUpdate();
        }
    }

    /** Cancels review invitations that were never completed (used when an author withdraws). */
    public int deletePending(Connection conn, int manuscriptId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM review_assignments WHERE manuscript_id = ? AND completed_at IS NULL")) {
            ps.setInt(1, manuscriptId);
            return ps.executeUpdate();
        }
    }

    public List<OverdueReminder> findOverdueWithoutReminder(Connection conn) throws SQLException {
        String sql = """
                SELECT ra.id, u.full_name, u.email, m.title, ra.due_at
                  FROM review_assignments ra
                  JOIN users u       ON u.id = ra.reviewer_id
                  JOIN manuscripts m ON m.id = ra.manuscript_id
                 WHERE ra.completed_at IS NULL AND ra.due_at < NOW() AND ra.reminder_sent = FALSE
                 ORDER BY ra.due_at
                   FOR UPDATE
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            List<OverdueReminder> reminders = new ArrayList<>();
            while (rs.next()) {
                reminders.add(new OverdueReminder(rs.getInt("id"), rs.getString("full_name"),
                        rs.getString("email"), rs.getString("title"), Rows.dateTime(rs, "due_at")));
            }
            return reminders;
        }
    }

    /** Marks many assignments in one round trip using JDBC batching. */
    public void markReminderSent(Connection conn, List<Integer> assignmentIds) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE review_assignments SET reminder_sent = TRUE WHERE id = ?")) {
            for (int id : assignmentIds) {
                ps.setInt(1, id);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private List<ReviewAssignment> list(Connection conn, String sql, int param) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                List<ReviewAssignment> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return result;
            }
        }
    }

    private static ReviewAssignment map(ResultSet rs) throws SQLException {
        return new ReviewAssignment(
                rs.getInt("id"),
                rs.getInt("manuscript_id"),
                rs.getString("manuscript_title"),
                rs.getInt("reviewer_id"),
                rs.getString("reviewer_name"),
                rs.getInt("round"),
                Rows.dateTime(rs, "assigned_at"),
                Rows.dateTime(rs, "due_at"),
                Rows.dateTime(rs, "completed_at"),
                Rows.enumOrNull(rs, "recommendation", Recommendation.class),
                rs.getString("comments"),
                rs.getBoolean("overdue"));
    }
}
