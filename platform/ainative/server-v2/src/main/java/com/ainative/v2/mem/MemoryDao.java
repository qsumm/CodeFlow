package com.ainative.v2.mem;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** 记忆与知识域数据访问(mm_ 表):默认 store 下的记忆条目。 */
@Repository
public class MemoryDao {
    private static long ws() {
        return com.ainative.v2.common.RequestContext.workspaceId();
    }


    public record Memory(long id, long storeId, String path, String contentMd, String contentHash) {}

    private final JdbcTemplate jdbc;

    public MemoryDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long ensureDefaultStore() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM mm_store WHERE workspace_id=" + ws() + " AND name='default' AND deleted_at IS NULL LIMIT 1", Long.class);
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO mm_store (workspace_id, name, kind) VALUES (" + ws() + ",'default','AGENT')",
                    PreparedStatement.RETURN_GENERATED_KEYS);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    public List<Memory> list(long storeId, String keyword, int pageNum, int pageSize) {
        int size = Math.max(1, Math.min(pageSize, 100));
        int offset = (Math.max(1, pageNum) - 1) * size;
        if (keyword == null || keyword.isBlank()) {
            return jdbc.query("SELECT id, store_id, path, content_md, content_hash FROM mm_doc WHERE store_id=? AND deleted_at IS NULL ORDER BY id DESC LIMIT ? OFFSET ?",
                    this::map, storeId, size, offset);
        }
        String like = "%" + keyword.trim() + "%";
        return jdbc.query("SELECT id, store_id, path, content_md, content_hash FROM mm_doc WHERE store_id=? AND deleted_at IS NULL AND (path LIKE ? OR content_md LIKE ?) ORDER BY id DESC LIMIT ? OFFSET ?",
                this::map, storeId, like, like, size, offset);
    }

    public long count(long storeId, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            Long n = jdbc.queryForObject("SELECT COUNT(*) FROM mm_doc WHERE store_id=? AND deleted_at IS NULL", Long.class, storeId);
            return n == null ? 0 : n;
        }
        String like = "%" + keyword.trim() + "%";
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM mm_doc WHERE store_id=? AND deleted_at IS NULL AND (path LIKE ? OR content_md LIKE ?)",
                Long.class, storeId, like, like);
        return n == null ? 0 : n;
    }

    public Optional<Memory> find(long id) {
        List<Memory> rows = jdbc.query("SELECT id, store_id, path, content_md, content_hash FROM mm_doc WHERE id=? AND deleted_at IS NULL",
                this::map, id);
        return rows.stream().findFirst();
    }

    public long insert(long storeId, String path, String contentMd) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO mm_doc (workspace_id, store_id, path, content_md) VALUES (" + ws() + ",?,?,?)",
                    PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setLong(1, storeId);
            ps.setString(2, path);
            ps.setString(3, contentMd);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    public void update(long id, String path, String contentMd) {
        jdbc.update("UPDATE mm_doc SET path=?, content_md=?, version=version+1 WHERE id=? AND deleted_at IS NULL",
                path, contentMd, id);
    }

    public void delete(long id) {
        jdbc.update("UPDATE mm_doc SET deleted_at=NOW() WHERE id=?", id);
    }

    private Memory map(ResultSet rs, int i) throws SQLException {
        return new Memory(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5));
    }
}
