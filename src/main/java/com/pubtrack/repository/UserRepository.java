package com.pubtrack.repository;

import com.pubtrack.model.Role;
import com.pubtrack.model.User;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class UserRepository {

    private static final String COLUMNS = "SELECT id, full_name, email, role FROM users";

    public List<User> findAll(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(COLUMNS + " ORDER BY role, full_name");
             ResultSet rs = ps.executeQuery()) {
            List<User> users = new ArrayList<>();
            while (rs.next()) {
                users.add(map(rs));
            }
            return users;
        }
    }

    public Optional<User> findById(Connection conn, int id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(COLUMNS + " WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private static User map(ResultSet rs) throws SQLException {
        return new User(rs.getInt("id"), rs.getString("full_name"), rs.getString("email"),
                Role.valueOf(rs.getString("role")));
    }
}
