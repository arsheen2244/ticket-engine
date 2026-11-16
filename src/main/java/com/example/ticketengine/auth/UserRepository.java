package com.example.ticketengine.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Locale;
import java.util.Optional;

@Repository
public class UserRepository {

    private static final RowMapper<AppUser> ROW = (rs, i) -> new AppUser(
            rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"), Role.valueOf(rs.getString("role")));

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }

    /** Throws DuplicateKeyException if the email is taken (UNIQUE constraint). */
    public AppUser create(String email, String passwordHash, Role role) {
        String normalized = normalize(email);
        Long id = jdbc.queryForObject(
                "INSERT INTO users(email, password_hash, role) VALUES (?, ?, ?) RETURNING id",
                Long.class, normalized, passwordHash, role.name());
        return new AppUser(id, normalized, passwordHash, role);
    }

    public Optional<AppUser> findByEmail(String email) {
        return jdbc.query("SELECT id, email, password_hash, role FROM users WHERE email = ?", ROW, normalize(email))
                .stream().findFirst();
    }

    public Optional<AppUser> findById(long id) {
        return jdbc.query("SELECT id, email, password_hash, role FROM users WHERE id = ?", ROW, id)
                .stream().findFirst();
    }

    public void setRole(long id, Role role) {
        jdbc.update("UPDATE users SET role = ? WHERE id = ?", role.name(), id);
    }

    public void setPasswordHash(long id, String passwordHash) {
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", passwordHash, id);
    }
}
