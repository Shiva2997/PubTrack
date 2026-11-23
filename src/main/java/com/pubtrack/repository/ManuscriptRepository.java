package com.pubtrack.repository;

import com.pubtrack.db.Rows;
import com.pubtrack.model.Manuscript;
import com.pubtrack.model.ManuscriptStatus;
import com.pubtrack.model.Page;
import com.pubtrack.model.Role;
import com.pubtrack.model.SearchCriteria;
import com.pubtrack.model.StatusEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class ManuscriptRepository {

    /** Base query: manuscript + journal + author, plus review progress for the current round. */
    private static final String SELECT = """
            SELECT m.id, m.journal_id, j.name AS journal_name, m.author_id, u.full_name AS author_name,
                   m.title, m.abstract_text, m.keywords, m.status, m.current_round,
                   m.submitted_at, m.updated_at, m.decided_at,
                   (SELECT COUNT(*) FROM review_assignments ra
                     WHERE ra.manuscript_id = m.id AND ra.round = m.current_round
                       AND ra.completed_at IS NOT NULL) AS reviews_done,
                   (SELECT COUNT(*) FROM review_assignments ra
                     WHERE ra.manuscript_id = m.id AND ra.round = m.current_round) AS reviews_total
              FROM manuscripts m
              JOIN journals j ON j.id = m.journal_id
              JOIN users u    ON u.id = m.author_id
            """;

    /** InnoDB's default full-text stop words; they must be removed before building a boolean query. */
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "about", "an", "are", "as", "at", "be", "by", "com", "de", "en", "for", "from", "how",
            "i", "in", "is", "it", "la", "of", "on", "or", "that", "the", "this", "to", "was", "what",
            "when", "where", "who", "will", "with", "und", "www");

    public int insert(Connection conn, int journalId, int authorId, String title, String abstractText,
                      String keywords) throws SQLException {
        String sql = """
                INSERT INTO manuscripts (journal_id, author_id, title, abstract_text, keywords, status,
                                         current_round, submitted_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'SUBMITTED', 1, NOW(), NOW())
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, journalId);
            ps.setInt(2, authorId);
            ps.setString(3, title);
            ps.setString(4, abstractText);
            ps.setString(5, keywords);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    public Optional<Manuscript> findById(Connection conn, int id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT + " WHERE m.id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Takes a row lock on the manuscript until the surrounding transaction ends, so two
     * people acting on the same manuscript at once are processed one after the other.
     */
    public boolean lock(Connection conn, int id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM manuscripts WHERE id = ? FOR UPDATE")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Moves a manuscript to a new status only if it is still in the expected one.
     * Returns false if somebody else changed it first (optimistic check).
     */
    public boolean updateStatus(Connection conn, int id, ManuscriptStatus expected, ManuscriptStatus next)
            throws SQLException {
        String sql = "UPDATE manuscripts SET status = ?, updated_at = NOW(), "
                + "decided_at = CASE WHEN ? THEN NOW() ELSE decided_at END "
                + "WHERE id = ? AND status = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, next.name());
            ps.setBoolean(2, next.isDecision());
            ps.setInt(3, id);
            ps.setString(4, expected.name());
            return ps.executeUpdate() == 1;
        }
    }

    public void startNextRound(Connection conn, int id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE manuscripts SET current_round = current_round + 1 WHERE id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    public void addHistory(Connection conn, int manuscriptId, ManuscriptStatus from, ManuscriptStatus to,
                           int changedBy, String note) throws SQLException {
        String sql = "INSERT INTO status_history (manuscript_id, from_status, to_status, changed_by, changed_at, note) "
                + "VALUES (?, ?, ?, ?, NOW(), ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, manuscriptId);
            ps.setString(2, from == null ? null : from.name());
            ps.setString(3, to.name());
            ps.setInt(4, changedBy);
            ps.setString(5, note);
            ps.executeUpdate();
        }
    }

    public List<StatusEvent> findHistory(Connection conn, int manuscriptId) throws SQLException {
        String sql = """
                SELECT h.from_status, h.to_status, u.full_name, h.changed_at, h.note
                  FROM status_history h
                  JOIN users u ON u.id = h.changed_by
                 WHERE h.manuscript_id = ?
                 ORDER BY h.changed_at, h.id
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, manuscriptId);
            try (ResultSet rs = ps.executeQuery()) {
                List<StatusEvent> events = new ArrayList<>();
                while (rs.next()) {
                    events.add(new StatusEvent(
                            Rows.enumOrNull(rs, "from_status", ManuscriptStatus.class),
                            ManuscriptStatus.valueOf(rs.getString("to_status")),
                            rs.getString("full_name"),
                            Rows.dateTime(rs, "changed_at"),
                            rs.getString("note")));
                }
                return events;
            }
        }
    }

    /**
     * Filtered, paginated search. The WHERE clause is assembled from optional filters,
     * but every value is still bound as a parameter, so user input never becomes SQL.
     */
    public Page<Manuscript> search(Connection conn, SearchCriteria c) throws SQLException {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();

        if (c.viewer().is(Role.AUTHOR)) {
            where.append(" AND m.author_id = ?");
            params.add(c.viewer().id());
        } else if (c.viewer().is(Role.REVIEWER)) {
            where.append(" AND EXISTS (SELECT 1 FROM review_assignments ra"
                    + " WHERE ra.manuscript_id = m.id AND ra.reviewer_id = ?)");
            params.add(c.viewer().id());
        }
        if (c.status() != null) {
            where.append(" AND m.status = ?");
            params.add(c.status().name());
        }
        if (c.journalId() != null) {
            where.append(" AND m.journal_id = ?");
            params.add(c.journalId());
        }
        if (c.text() != null && !c.text().isBlank()) {
            String booleanQuery = toFullTextQuery(c.text());
            if (booleanQuery.isEmpty()) {
                // Only short words or stop words: fall back to a simple substring match on the title.
                where.append(" AND m.title LIKE ?");
                params.add("%" + escapeLike(c.text().trim()) + "%");
            } else {
                where.append(" AND MATCH(m.title, m.keywords, m.abstract_text) AGAINST (? IN BOOLEAN MODE)");
                params.add(booleanQuery);
            }
        }

        long total;
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM manuscripts m" + where)) {
            Rows.bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                total = rs.getLong(1);
            }
        }

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(c.pageSize());
        pageParams.add((c.page() - 1) * c.pageSize());
        List<Manuscript> items = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                SELECT + where + " ORDER BY m.updated_at DESC, m.id DESC LIMIT ? OFFSET ?")) {
            Rows.bind(ps, pageParams);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(map(rs));
                }
            }
        }
        return new Page<>(items, c.page(), c.pageSize(), total);
    }

    /**
     * Turns "graph neural for citations" into "+graph* +neural* +citations*":
     * every remaining word must match (as a prefix). Punctuation, words under 3 letters
     * and InnoDB stop words are dropped because MySQL would otherwise return no rows.
     */
    static String toFullTextQuery(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(w -> w.length() >= 3 && !STOP_WORDS.contains(w))
                .distinct()
                .map(w -> "+" + w + "*")
                .collect(Collectors.joining(" "));
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Manuscript map(ResultSet rs) throws SQLException {
        return new Manuscript(
                rs.getInt("id"),
                rs.getInt("journal_id"),
                rs.getString("journal_name"),
                rs.getInt("author_id"),
                rs.getString("author_name"),
                rs.getString("title"),
                rs.getString("abstract_text"),
                rs.getString("keywords"),
                ManuscriptStatus.valueOf(rs.getString("status")),
                rs.getInt("current_round"),
                Rows.dateTime(rs, "submitted_at"),
                Rows.dateTime(rs, "updated_at"),
                Rows.dateTime(rs, "decided_at"),
                rs.getInt("reviews_done"),
                rs.getInt("reviews_total"));
    }
}
