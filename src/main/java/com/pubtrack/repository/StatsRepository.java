package com.pubtrack.repository;

import com.pubtrack.db.Rows;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** Reporting queries for the editor dashboard (aggregations, conditional sums, outer joins). */
public class StatsRepository {

    public List<Map<String, Object>> journalStats(Connection conn) throws SQLException {
        String sql = """
                SELECT j.id, j.name,
                       COUNT(m.id) AS total,
                       COALESCE(SUM(m.status IN ('SUBMITTED', 'UNDER_REVIEW', 'REVISION_REQUESTED')), 0) AS in_progress,
                       COALESCE(SUM(m.status = 'ACCEPTED'), 0) AS accepted,
                       COALESCE(SUM(m.status = 'REJECTED'), 0) AS rejected,
                       ROUND(100 * SUM(m.status = 'ACCEPTED')
                             / NULLIF(SUM(m.status IN ('ACCEPTED', 'REJECTED')), 0), 1) AS acceptance_rate,
                       ROUND(AVG(TIMESTAMPDIFF(HOUR, m.submitted_at, m.decided_at)) / 24, 1) AS avg_days_to_decision
                  FROM journals j
                  LEFT JOIN manuscripts m ON m.journal_id = j.id
                 GROUP BY j.id, j.name
                 ORDER BY total DESC, j.name
                """;
        return query(conn, sql);
    }

    public List<Map<String, Object>> reviewerStats(Connection conn) throws SQLException {
        String sql = """
                SELECT u.id, u.full_name AS name,
                       COUNT(ra.id) AS assigned,
                       COALESCE(SUM(ra.completed_at IS NOT NULL), 0) AS completed,
                       COALESCE(SUM(ra.completed_at IS NULL), 0) AS pending,
                       COALESCE(SUM(ra.completed_at IS NULL AND ra.due_at < NOW()), 0) AS overdue,
                       COALESCE(SUM(ra.completed_at > ra.due_at), 0) AS late,
                       ROUND(AVG(TIMESTAMPDIFF(HOUR, ra.assigned_at, ra.completed_at)) / 24, 1) AS avg_turnaround_days
                  FROM users u
                  LEFT JOIN review_assignments ra ON ra.reviewer_id = u.id
                 WHERE u.role = 'REVIEWER'
                 GROUP BY u.id, u.full_name
                 ORDER BY pending, u.full_name
                """;
        return query(conn, sql);
    }

    public List<Map<String, Object>> overdueReviews(Connection conn) throws SQLException {
        String sql = """
                SELECT ra.id AS assignment_id, m.id AS manuscript_id, m.title, j.name AS journal,
                       u.full_name AS reviewer, ra.due_at,
                       TIMESTAMPDIFF(DAY, ra.due_at, NOW()) AS days_overdue,
                       ra.reminder_sent
                  FROM review_assignments ra
                  JOIN manuscripts m ON m.id = ra.manuscript_id
                  JOIN journals j    ON j.id = m.journal_id
                  JOIN users u       ON u.id = ra.reviewer_id
                 WHERE ra.completed_at IS NULL AND ra.due_at < NOW()
                 ORDER BY ra.due_at
                """;
        return query(conn, sql);
    }

    private static List<Map<String, Object>> query(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return Rows.toMaps(rs);
        }
    }
}
