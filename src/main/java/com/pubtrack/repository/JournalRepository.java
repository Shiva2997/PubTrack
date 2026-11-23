package com.pubtrack.repository;

import com.pubtrack.model.Journal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JournalRepository {

    private static final String COLUMNS = "SELECT id, name, subject_area, review_days FROM journals";

    public List<Journal> findAll(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(COLUMNS + " ORDER BY name");
             ResultSet rs = ps.executeQuery()) {
            List<Journal> journals = new ArrayList<>();
            while (rs.next()) {
                journals.add(map(rs));
            }
            return journals;
        }
    }

    public Optional<Journal> findById(Connection conn, int id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(COLUMNS + " WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private static Journal map(ResultSet rs) throws SQLException {
        return new Journal(rs.getInt("id"), rs.getString("name"), rs.getString("subject_area"),
                rs.getInt("review_days"));
    }
}
