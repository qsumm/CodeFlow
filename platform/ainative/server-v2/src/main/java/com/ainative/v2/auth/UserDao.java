package com.ainative.v2.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/** 身份域数据访问:只管 SQL,不做业务判断。 */
@Repository
public class UserDao {
    public record User(Long id, String username, String nickname, String email, String passwordHash,
                       boolean admin, String status) {}

    private final JdbcTemplate jdbc;

    public UserDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<User> findByUsername(String username) {
        List<User> rows = jdbc.query(
                "SELECT id, username, nickname, email, password_hash, is_admin, status FROM id_user WHERE username = ? AND deleted_at IS NULL",
                (rs, i) -> new User(rs.getLong("id"), rs.getString("username"), rs.getString("nickname"),
                        rs.getString("email"), rs.getString("password_hash"), rs.getInt("is_admin") == 1,
                        rs.getString("status")),
                username);
        return rows.stream().findFirst();
    }

    public Optional<User> findById(long id) {
        List<User> rows = jdbc.query(
                "SELECT id, username, nickname, email, password_hash, is_admin, status FROM id_user WHERE id = ? AND deleted_at IS NULL",
                (rs, i) -> new User(rs.getLong("id"), rs.getString("username"), rs.getString("nickname"),
                        rs.getString("email"), rs.getString("password_hash"), rs.getInt("is_admin") == 1,
                        rs.getString("status")),
                id);
        return rows.stream().findFirst();
    }

    public long insert(String username, String nickname, String email, String passwordHash) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO id_user (username, nickname, email, password_hash, is_admin, status) VALUES (?,?,?,?,0,'ACTIVE')",
                    PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setString(1, username);
            ps.setString(2, nickname);
            ps.setString(3, email);
            ps.setString(4, passwordHash);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0L : key.longValue();
    }

    public long countUsers() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM id_user WHERE deleted_at IS NULL", Long.class);
        return n == null ? 0L : n;
    }
}
