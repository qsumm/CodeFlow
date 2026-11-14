package com.ainative.v2.misc;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.BizException;
import com.ainative.v2.common.RequestContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 杂项接口:Ai 会话(确认式编排) / AI 用量配额 / 工单分享链接 / memory-store 视图。
 * 都是前端在用的轻量端点;memory-stores 直接映射 mm_store/mm_doc/mm_acl。
 */
@RestController
public class MiscController {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    public record CreateSessionRequest(String scene, String bizRef) {}
    public record MessageRequest(String contentMd) {}
    public record ConfirmRequest(Boolean confirmed) {}
    public record ShareRequest(Integer ttlHours) {}
    public record StoreRequest(String name, String kind, String description) {}
    public record DocRequest(String path, String contentMd) {}
    public record AclRequest(Long agentId, String perm) {}

    private final JdbcTemplate jdbc;

    public MiscController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private long ws() {
        return RequestContext.workspaceId();
    }

    // ================================================================ Ai 会话

    @GetMapping("/api/ai/sessions")
    public ApiResult<List<Map<String, Object>>> sessions(@RequestParam(required = false) String scene,
                                                         @RequestParam(defaultValue = "20") int limit) {
        RequestContext.requireUserId();
        int n = Math.max(1, Math.min(limit, 100));
        String sql = "SELECT s.id, s.scene, s.biz_ref AS bizRef, s.status, s.gmt_create AS gmtCreate,"
                + " s.gmt_modified AS gmtModified FROM ag_ai_session s WHERE s.workspace_id=? AND s.deleted_at IS NULL";
        if (scene != null && !scene.isBlank()) {
            sql += " AND s.scene='" + scene.replace("'", "''") + "'";
        }
        sql += " ORDER BY s.id DESC LIMIT " + n;
        return ApiResult.ok(jdbc.queryForList(sql, ws()));
    }

    @PostMapping("/api/ai/sessions")
    public ApiResult<Map<String, Object>> createSession(@RequestBody CreateSessionRequest req) {
        long uid = RequestContext.requireUserId();
        jdbc.update("INSERT INTO ag_ai_session (workspace_id, scene, biz_ref, user_id, status) VALUES (?,?,?,?,'OPEN')",
                ws(), req.scene(), req.bizRef(), uid);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM ag_ai_session WHERE workspace_id=?", Long.class, ws());
        return ApiResult.ok(sessionMap(id));
    }

    @GetMapping("/api/ai/sessions/{id}")
    public ApiResult<Map<String, Object>> session(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(sessionMap(id));
    }

    @PostMapping("/api/ai/sessions/{id}/confirm")
    public ApiResult<Map<String, Object>> confirm(@PathVariable long id, @RequestBody(required = false) ConfirmRequest req) {
        RequestContext.requireUserId();
        boolean ok = req == null || req.confirmed() == null || req.confirmed();
        jdbc.update("UPDATE ag_ai_session SET status=? WHERE id=? AND workspace_id=?",
                ok ? "CONFIRMED" : "CANCELED", id, ws());
        return ApiResult.ok(sessionMap(id));
    }

    @GetMapping("/api/ai/sessions/{id}/messages")
    public ApiResult<List<Map<String, Object>>> messages(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(jdbc.queryForList(
                "SELECT id, session_id AS sessionId, seq, role, content_md AS contentMd, gmt_create AS gmtCreate"
                        + " FROM ag_ai_message WHERE session_id=? AND deleted_at IS NULL ORDER BY seq", id));
    }

    @PostMapping("/api/ai/sessions/{id}/messages")
    public ApiResult<Map<String, Object>> addMessage(@PathVariable long id, @RequestBody MessageRequest req) {
        RequestContext.requireUserId();
        Integer seq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq),0)+1 FROM ag_ai_message WHERE session_id=?", Integer.class, id);
        jdbc.update("INSERT INTO ag_ai_message (workspace_id, session_id, seq, role, content_md) VALUES (?,?,?,'USER',?)",
                ws(), id, seq, req.contentMd());
        Long msgId = jdbc.queryForObject("SELECT MAX(id) FROM ag_ai_message WHERE session_id=?", Long.class, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", msgId);
        out.put("sessionId", id);
        out.put("seq", seq);
        out.put("status", "ACCEPTED");
        return ApiResult.ok(out);
    }

    private Map<String, Object> sessionMap(Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, scene, biz_ref AS bizRef, user_id AS userId, status,"
                        + " gmt_create AS gmtCreate, gmt_modified AS gmtModified FROM ag_ai_session WHERE id=?", id);
        if (rows.isEmpty()) {
            throw new BizException("22001", "会话不存在");
        }
        return rows.get(0);
    }

    // ================================================================ AI 用量

    @GetMapping("/api/ai-usage")
    public ApiResult<List<Map<String, Object>>> usage(@RequestParam(defaultValue = "30") int days) {
        RequestContext.requireUserId();
        int n = Math.max(1, Math.min(days, 365));
        return ApiResult.ok(jdbc.queryForList(
                "SELECT model, SUM(input_tokens) AS inputTokens, SUM(output_tokens) AS outputTokens, COUNT(*) AS calls"
                        + " FROM rt_ai_usage WHERE workspace_id=? AND gmt_create >= NOW() - INTERVAL " + n + " DAY"
                        + " GROUP BY model", ws()));
    }

    @GetMapping("/api/ai-usage/quota")
    public ApiResult<Map<String, Object>> quota() {
        RequestContext.requireUserId();
        Long used = jdbc.queryForObject(
                "SELECT COALESCE(SUM(COALESCE(tokens_in,0) + COALESCE(tokens_out,0)),0) FROM cv_usage WHERE workspace_id=?", Long.class, ws());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("usedTokens", used == null ? 0 : used);
        out.put("limitTokens", 0);
        out.put("unlimited", true);
        return ApiResult.ok(out);
    }

    // ================================================================ 工单分享

    @PostMapping("/api/workitems/{id}/share")
    public ApiResult<Map<String, Object>> share(@org.springframework.web.bind.annotation.PathVariable long id,
                                                @RequestBody(required = false) ShareRequest req) {
        RequestContext.requireUserId();
        String token = randomToken(32);
        java.time.LocalDateTime expires = req != null && req.ttlHours() != null && req.ttlHours() > 0
                ? java.time.LocalDateTime.now().plusHours(req.ttlHours())
                : null;
        jdbc.update("INSERT INTO cv_share (workspace_id, ticket_id, token, expires_at) VALUES (?,?,?,?)",
                ws(), id, token, expires);
        Long shareId = jdbc.queryForObject("SELECT MAX(id) FROM cv_share WHERE ticket_id=?", Long.class, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", shareId);
        out.put("token", token);
        out.put("url", "/share/workitems/" + token);
        return ApiResult.ok(out);
    }

    /** 公开分享视图(免登录):按令牌读取工单快照。 */
    @GetMapping("/api/share/workitems/{token}")
    public ApiResult<Map<String, Object>> sharedView(@PathVariable String token) {
        List<Map<String, Object>> shares = jdbc.queryForList(
                "SELECT id, ticket_id AS ticketId, expires_at FROM cv_share WHERE token=? AND deleted_at IS NULL", token);
        if (shares.isEmpty()) {
            throw new BizException("23001", "分享链接不存在");
        }
        Map<String, Object> share = shares.get(0);
        if (share.get("expires_at") instanceof java.sql.Timestamp ts && ts.toLocalDateTime().isBefore(java.time.LocalDateTime.now())) {
            throw new BizException("23002", "分享链接已过期");
        }
        long ticketId = ((Number) share.get("ticketId")).longValue();
        List<Map<String, Object>> tickets = jdbc.queryForList(
                "SELECT t.id, t.title, t.body_md AS contentMd, t.type, t.priority, s.name AS stateName"
                        + " FROM wi_ticket t LEFT JOIN wi_flow_state s ON s.id=t.state_id"
                        + " WHERE t.id=? AND t.deleted_at IS NULL", ticketId);
        if (tickets.isEmpty()) {
            throw new BizException("23003", "工单不存在或已删除");
        }
        Map<String, Object> out = new LinkedHashMap<>(tickets.get(0));
        out.put("notes", jdbc.queryForList(
                "SELECT author_type AS authorType, body_md AS contentMd, gmt_create AS createdAt FROM wi_note"
                        + " WHERE ticket_id=? AND deleted_at IS NULL ORDER BY id", ticketId));
        return ApiResult.ok(out);
    }

    // ================================================================ 记忆库

    @GetMapping("/api/memory-stores")
    public ApiResult<List<Map<String, Object>>> stores(@RequestParam(required = false) String keyword) {
        RequestContext.requireUserId();
        String like = keyword == null || keyword.isBlank() ? "%" : "%" + keyword.trim() + "%";
        return ApiResult.ok(jdbc.queryForList(
                "SELECT id, name, kind, description, gmt_create AS gmtCreate FROM mm_store"
                        + " WHERE workspace_id=? AND deleted_at IS NULL AND name LIKE ? ORDER BY id", ws(), like));
    }

    @PostMapping("/api/memory-stores")
    public ApiResult<Map<String, Object>> createStore(@RequestBody StoreRequest req) {
        RequestContext.requireUserId();
        if (req.name() == null || req.name().isBlank()) {
            throw new BizException("10001", "记忆库名称不能为空");
        }
        jdbc.update("INSERT INTO mm_store (workspace_id, name, kind, description) VALUES (?,?,?,?)",
                ws(), req.name().trim(), req.kind(), req.description());
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM mm_store WHERE workspace_id=?", Long.class, ws());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", req.name().trim());
        return ApiResult.ok(out);
    }

    /**
     * 记忆归属目录(前端 MemoryDirectory 契约:{owners:[{scope,ownerRef,name,squadNames,
     * storeId,permission,currentRevision,canCreate}], migration})。
     * 每个归属 = 一个记忆库(当前实现:项目级 store 归当前用户,智能体级 store 归各 agent)。
     */
    @GetMapping("/api/memory-stores/directory")
    public ApiResult<Map<String, Object>> directory() {
        long uid = RequestContext.requireUserId();
        List<Map<String, Object>> owners = new ArrayList<>();
        for (Map<String, Object> s : jdbc.queryForList(
                "SELECT id, name, kind FROM mm_store WHERE workspace_id=? AND deleted_at IS NULL ORDER BY id", ws())) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("scope", "PROJECT");
            o.put("ownerRef", uid);
            o.put("name", s.get("name"));
            o.put("squadNames", List.of());
            o.put("storeId", s.get("id"));
            o.put("permission", "ADMIN");
            o.put("currentRevision", jdbc.queryForObject(
                    "SELECT COUNT(*) FROM mm_doc WHERE store_id=? AND deleted_at IS NULL", Long.class, s.get("id")));
            o.put("canCreate", true);
            owners.add(o);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("owners", owners);
        out.put("migration", Map.of("migrated", 0, "pending", 0));
        return ApiResult.ok(out);
    }

    @GetMapping("/api/memory-stores/{id}")
    public ApiResult<Map<String, Object>> store(@PathVariable long id) {
        RequestContext.requireUserId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, kind, description, gmt_create AS gmtCreate FROM mm_store WHERE id=? AND deleted_at IS NULL", id);
        if (rows.isEmpty()) {
            throw new BizException("24001", "记忆库不存在");
        }
        return ApiResult.ok(rows.get(0));
    }

    @GetMapping("/api/memory-stores/{id}/documents")
    public ApiResult<List<Map<String, Object>>> documents(@PathVariable long id,
                                                          @RequestParam(required = false) String keyword) {
        RequestContext.requireUserId();
        String like = keyword == null || keyword.isBlank() ? "%" : "%" + keyword.trim() + "%";
        return ApiResult.ok(jdbc.queryForList(
                "SELECT id, store_id AS storeId, path, LEFT(content_md, 200) AS preview, content_hash AS contentHash,"
                        + " gmt_modified AS updatedAt FROM mm_doc WHERE store_id=? AND deleted_at IS NULL AND path LIKE ?"
                        + " ORDER BY path LIMIT 200", id, like));
    }

    @PostMapping("/api/memory-stores/{id}/documents")
    public ApiResult<Map<String, Object>> saveDocument(@PathVariable long id, @RequestBody DocRequest req) {
        long uid = RequestContext.requireUserId();
        if (req.path() == null || req.path().isBlank()) {
            throw new BizException("10001", "文档路径不能为空");
        }
        String hash = sha256(req.contentMd() == null ? "" : req.contentMd());
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id FROM mm_doc WHERE store_id=? AND path=?", id, req.path());
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO mm_doc (workspace_id, store_id, path, content_md, content_hash) VALUES (?,?,?,?,?)",
                    ws(), id, req.path(), req.contentMd(), hash);
        } else {
            jdbc.update("UPDATE mm_doc SET content_md=?, content_hash=? WHERE id=?",
                    req.contentMd(), hash, existing.get(0).get("id"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("storeId", id);
        out.put("path", req.path());
        out.put("contentHash", hash);
        return ApiResult.ok(out);
    }

    @GetMapping("/api/memory-stores/{id}/topics")
    public ApiResult<List<Map<String, Object>>> topics(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(jdbc.queryForList(
                "SELECT path, COUNT(*) AS docCount FROM mm_doc WHERE store_id=? AND deleted_at IS NULL"
                        + " GROUP BY SUBSTRING_INDEX(path, '/', 1) ORDER BY path", id));
    }

    @GetMapping("/api/memory-stores/{id}/acls")
    public ApiResult<List<Map<String, Object>>> acls(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(jdbc.queryForList(
                "SELECT a.id, a.agent_id AS agentId, g.name AS agentName, a.perm FROM mm_acl a"
                        + " LEFT JOIN ag_agent g ON g.id=a.agent_id WHERE a.store_id=? AND a.deleted_at IS NULL", id));
    }

    @PostMapping("/api/memory-stores/{id}/acls")
    public ApiResult<Map<String, Object>> grantAcl(@PathVariable long id, @RequestBody AclRequest req) {
        RequestContext.requireUserId();
        if (req.agentId() == null) {
            throw new BizException("10001", "智能体不能为空");
        }
        jdbc.update("INSERT INTO mm_acl (workspace_id, store_id, agent_id, perm) VALUES (?,?,?,?)",
                ws(), id, req.agentId(), req.perm() == null ? "READ" : req.perm());
        Long aclId = jdbc.queryForObject("SELECT MAX(id) FROM mm_acl WHERE store_id=?", Long.class, id);
        return ApiResult.ok(Map.of("id", aclId, "agentId", req.agentId()));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/api/memory-stores/{id}/acls/{aclId}")
    public ApiResult<Void> revokeAcl(@PathVariable long id, @PathVariable long aclId) {
        RequestContext.requireUserId();
        jdbc.update("UPDATE mm_acl SET deleted_at=NOW() WHERE id=? AND store_id=?", aclId, id);
        return ApiResult.ok(null);
    }

    @GetMapping("/api/memory-stores/{id}/history")
    public ApiResult<List<Map<String, Object>>> history(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(jdbc.queryForList(
                "SELECT id, path, LEFT(IFNULL(content_md,''), 120) AS preview, gmt_modified AS updatedAt"
                        + " FROM mm_doc WHERE store_id=? ORDER BY gmt_modified DESC LIMIT 100", id));
    }

    @GetMapping("/api/memory-stores/{id}/mutations")
    public ApiResult<List<Map<String, Object>>> mutations(@PathVariable long id) {
        RequestContext.requireUserId();
        // 变更流水以文档表的修改记录近似:按天聚合
        return ApiResult.ok(jdbc.queryForList(
                "SELECT DATE(gmt_modified) AS day, COUNT(*) AS changes FROM mm_doc WHERE store_id=?"
                        + " GROUP BY DATE(gmt_modified) ORDER BY day DESC LIMIT 60", id));
    }

    // ================================================================ 记忆导入

    @GetMapping("/api/memory-imports")
    public ApiResult<List<Map<String, Object>>> imports() {
        RequestContext.requireUserId();
        return ApiResult.ok(jdbc.queryForList(
                "SELECT id, store_id AS storeId, source, status, gmt_create AS gmtCreate FROM mm_import_snapshot"
                        + " WHERE workspace_id=? AND deleted_at IS NULL ORDER BY id DESC LIMIT 50", ws()));
    }

    @GetMapping("/api/memory-imports/{id}/status")
    public ApiResult<Map<String, Object>> importStatus(@PathVariable long id) {
        RequestContext.requireUserId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, status FROM mm_import_snapshot WHERE id=?", id);
        if (rows.isEmpty()) {
            throw new BizException("24002", "导入记录不存在");
        }
        return ApiResult.ok(rows.get(0));
    }

    @GetMapping("/api/memory-imports/{id}/receipts")
    public ApiResult<List<Map<String, Object>>> importReceipts(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(jdbc.queryForList(
                "SELECT id, store_id AS storeId, path, gmt_create AS createdAt FROM mm_import_receipt"
                        + " WHERE snapshot_id=? ORDER BY id", id));
    }

    // ================================================================ 工具

    private String randomToken(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private String sha256(String text) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
