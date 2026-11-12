package com.ainative.v2.asset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** 资产与仓库域数据访问(as_ 表):仓库/关系/结论、环境变量、产物。 */
@Repository
public class AssetDao {
    private static long ws() {
        return com.ainative.v2.common.RequestContext.workspaceId();
    }


    public record Repo(long id, String name, String url, String provider, String defaultBranch) {}
    public record EnvVar(long id, String keyName, String valueRef, boolean secret) {}
    public record Artifact(long id, Long ticketId, String name, String kind, String objectKey, Long sizeBytes) {}

    private final JdbcTemplate jdbc;

    public AssetDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Long nLong(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        return v == null ? null : ((Number) v).longValue();
    }

    // ── 仓库 ──
    public List<Repo> listRepos() {
        return jdbc.query("SELECT id, name, url, provider, default_branch FROM as_repo WHERE workspace_id=" + ws() + " AND deleted_at IS NULL ORDER BY id DESC",
                (rs, i) -> new Repo(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
    }

    public Optional<Repo> findRepo(long id) {
        List<Repo> rows = jdbc.query("SELECT id, name, url, provider, default_branch FROM as_repo WHERE id=? AND deleted_at IS NULL",
                (rs, i) -> new Repo(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), id);
        return rows.stream().findFirst();
    }

    public long insertRepo(String name, String url, String provider, String defaultBranch) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO as_repo (workspace_id, name, url, provider, default_branch) VALUES (" + ws() + ",?,?,?,?)",
                    PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, url);
            ps.setString(3, provider);
            ps.setString(4, defaultBranch);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    public void updateRepo(long id, String name, String url, String provider, String defaultBranch) {
        jdbc.update("UPDATE as_repo SET name=?, url=?, provider=?, default_branch=?, version=version+1 WHERE id=?",
                name, url, provider, defaultBranch, id);
    }

    public void deleteRepo(long id) {
        jdbc.update("UPDATE as_repo SET deleted_at=NOW() WHERE id=?", id);
    }

    public void addRelation(long fromRepoId, long toRepoId, String type) {
        jdbc.update("INSERT IGNORE INTO as_repo_relation (workspace_id, from_repo_id, to_repo_id, rel_type) VALUES (1,?,?,?)",
                fromRepoId, toRepoId, type);
    }

    public List<java.util.Map<String, Object>> relations() {
        return jdbc.queryForList("SELECT from_repo_id AS fromRepoId, to_repo_id AS toRepoId, rel_type AS type FROM as_repo_relation WHERE workspace_id=" + ws() + "");
    }

    public Optional<String> conclusion(long repoId) {
        List<String> rows = jdbc.queryForList("SELECT content_md FROM as_repo_conclusion WHERE repo_id=?", String.class, repoId);
        return rows.stream().findFirst();
    }

    public void saveConclusion(long repoId, String contentMd) {
        int n = jdbc.update("UPDATE as_repo_conclusion SET content_md=? WHERE repo_id=?", contentMd, repoId);
        if (n == 0) {
            jdbc.update("INSERT INTO as_repo_conclusion (workspace_id, repo_id, content_md) VALUES (" + ws() + ",?,?)", repoId, contentMd);
        }
    }

    // ── 环境变量 ──
    public List<EnvVar> listEnvVars() {
        return jdbc.query("SELECT id, key_name, value_ref, is_secret FROM as_env_var WHERE workspace_id=" + ws() + " AND deleted_at IS NULL ORDER BY key_name",
                (rs, i) -> new EnvVar(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4) == 1));
    }

    public Optional<EnvVar> findEnvVar(long id) {
        List<EnvVar> rows = jdbc.query("SELECT id, key_name, value_ref, is_secret FROM as_env_var WHERE id=? AND deleted_at IS NULL",
                (rs, i) -> new EnvVar(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4) == 1), id);
        return rows.stream().findFirst();
    }

    public long insertEnvVar(String keyName, String valueRef, boolean secret) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO as_env_var (workspace_id, key_name, value_ref, is_secret) VALUES (" + ws() + ",?,?,?)",
                    PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setString(1, keyName);
            ps.setString(2, valueRef);
            ps.setInt(3, secret ? 1 : 0);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    public void updateEnvVar(long id, String valueRef, boolean secret) {
        jdbc.update("UPDATE as_env_var SET value_ref=?, is_secret=?, version=version+1 WHERE id=?", valueRef, secret ? 1 : 0, id);
    }

    public void deleteEnvVar(long id) {
        jdbc.update("UPDATE as_env_var SET deleted_at=NOW() WHERE id=?", id);
    }

    // ── 产物 ──
    public List<Artifact> artifactsOf(long ticketId) {
        return jdbc.query("SELECT id, ticket_id, name, kind, object_key, size_bytes FROM as_artifact WHERE ticket_id=? AND deleted_at IS NULL ORDER BY id DESC",
                (rs, i) -> new Artifact(rs.getLong(1), nLong(rs, "ticket_id"), rs.getString(3), rs.getString(4),
                        rs.getString(5), nLong(rs, "size_bytes")), ticketId);
    }
}
