package com.ainative.v2.pf;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.BizException;
import com.ainative.v2.common.PageResult;
import com.ainative.v2.common.RequestContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台用户与管理员治理(平台管理员域)。
 *
 * <p>与 /api/users/me 的区别:这里是管理员对「全平台账号」的增删改查,
 * 包括启停、重置密码、管理员授权、IM 身份代绑、项目管理。
 */
@RestController
@RequestMapping("/api/platform")
public class PlatformAdminController {
    public record ProfileRequest(String nickname, String email) {}
    public record PasswordRequest(String newPassword) {}
    public record ImRequest(String externalUserId) {}
    public record MembershipRequest(Long workspaceId) {}
    public record IdpRequest(String name, Map<String, Object> metadata) {}
    public record ConnectionRequest(Long idpId, String entityId, Map<String, Object> detail) {}

    private final JdbcTemplate jdbc;

    public PlatformAdminController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- 用户

    @GetMapping("/users")
    public ApiResult<PageResult<Map<String, Object>>> users(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        RequestContext.requireUserId();
        int limit = Math.max(1, Math.min(size, 100));
        int offset = Math.max(0, (Math.max(page, 1) - 1) * limit);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (username LIKE ? OR nickname LIKE ? OR email LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM id_user" + where, Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, username, nickname, email, is_admin AS isAdmin, status,"
                        + " gmt_create AS createdAt, deleted_at AS deletedAt FROM id_user" + where
                        + " ORDER BY id DESC LIMIT " + limit + " OFFSET " + offset, pageArgs.toArray());
        return ApiResult.ok(new PageResult<>(rows, total == null ? 0 : total, page, limit));
    }

    @GetMapping("/users/{id}")
    public ApiResult<Map<String, Object>> user(@PathVariable long id) {
        RequestContext.requireUserId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, username, nickname, email, is_admin AS isAdmin, status,"
                        + " gmt_create AS createdAt, deleted_at AS deletedAt FROM id_user WHERE id=?", id);
        if (rows.isEmpty()) {
            throw new BizException("20001", "用户不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
        out.put("workspaces", workspacesOf(id));
        return ApiResult.ok(out);
    }

    @PutMapping("/users/{id}/profile")
    public ApiResult<Map<String, Object>> updateProfile(@PathVariable long id, @RequestBody ProfileRequest req) {
        RequestContext.requireUserId();
        if (req.nickname() != null) {
            jdbc.update("UPDATE id_user SET nickname=? WHERE id=?", req.nickname(), id);
        }
        if (req.email() != null) {
            jdbc.update("UPDATE id_user SET email=? WHERE id=?", req.email(), id);
        }
        return user(id);
    }

    @PutMapping("/users/{id}/password")
    public ApiResult<Void> resetPassword(@PathVariable long id, @RequestBody PasswordRequest req) {
        RequestContext.requireUserId();
        if (req.newPassword() == null || req.newPassword().length() < 6) {
            throw new BizException("10001", "密码至少 6 位");
        }
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(req.newPassword());
        jdbc.update("UPDATE id_user SET password_hash=? WHERE id=?", hash, id);
        return ApiResult.ok(null);
    }

    @PostMapping("/users/{id}/delete")
    public ApiResult<Void> deleteUser(@PathVariable long id) {
        RequestContext.requireUserId();
        jdbc.update("UPDATE id_user SET deleted_at=NOW(), status='DISABLED' WHERE id=?", id);
        return ApiResult.ok(null);
    }

    @PostMapping("/users/{id}/restore")
    public ApiResult<Void> restoreUser(@PathVariable long id) {
        RequestContext.requireUserId();
        jdbc.update("UPDATE id_user SET deleted_at=NULL, status='ACTIVE' WHERE id=?", id);
        return ApiResult.ok(null);
    }

    // ---------------------------------------------------------------- IM 身份

    @GetMapping("/users/{id}/im-identity")
    public ApiResult<List<Map<String, Object>>> imIdentity(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(jdbc.queryForList(
                "SELECT id, channel AS provider, external_id AS externalUserId FROM id_user_im WHERE user_id=?", id));
    }

    @PutMapping("/users/{id}/im-identity/{provider}")
    public ApiResult<Void> bindIm(@PathVariable long id, @PathVariable String provider, @RequestBody ImRequest req) {
        RequestContext.requireUserId();
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id FROM id_user_im WHERE user_id=? AND channel=?", id, provider);
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO id_user_im (user_id, channel, external_id) VALUES (?,?,?)",
                    id, provider, req.externalUserId());
        } else {
            jdbc.update("UPDATE id_user_im SET external_id=? WHERE id=?", req.externalUserId(), existing.get(0).get("id"));
        }
        return ApiResult.ok(null);
    }

    @DeleteMapping("/users/{id}/im-identity/{provider}")
    public ApiResult<Void> unbindIm(@PathVariable long id, @PathVariable String provider) {
        RequestContext.requireUserId();
        jdbc.update("DELETE FROM id_user_im WHERE user_id=? AND channel=?", id, provider);
        return ApiResult.ok(null);
    }

    @PostMapping("/users/{id}/im-identity/{provider}/test")
    public ApiResult<Map<String, Object>> testIm(@PathVariable long id, @PathVariable String provider) {
        RequestContext.requireUserId();
        boolean bound = !jdbc.queryForList(
                "SELECT 1 FROM id_user_im WHERE user_id=? AND channel=?", id, provider).isEmpty();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", provider);
        out.put("bound", bound);
        out.put("reachable", bound);
        out.put("message", bound ? "绑定存在" : "未绑定");
        return ApiResult.ok(out);
    }

    // ---------------------------------------------------------------- 项目归属

    @GetMapping("/users/{id}/workspaces")
    public ApiResult<List<Map<String, Object>>> workspacesOfUser(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(workspacesOf(id));
    }

    @PostMapping("/users/{id}/workspaces")
    public ApiResult<Void> addToWorkspace(@PathVariable long id, @RequestBody MembershipRequest req) {
        RequestContext.requireUserId();
        if (req.workspaceId() == null) {
            throw new BizException("10001", "项目 ID 不能为空");
        }
        if (jdbc.queryForList("SELECT 1 FROM id_org_member WHERE org_id=? AND user_id=?",
                req.workspaceId(), id).isEmpty()) {
            jdbc.update("INSERT INTO id_org_member (org_id, user_id, role) VALUES (?,?, 'MEMBER')", req.workspaceId(), id);
        } else {
            jdbc.update("UPDATE id_org_member SET deleted_at=NULL WHERE org_id=? AND user_id=?", req.workspaceId(), id);
        }
        return ApiResult.ok(null);
    }

    @DeleteMapping("/users/{id}/workspaces/{workspaceId}")
    public ApiResult<Void> removeFromWorkspace(@PathVariable long id, @PathVariable long workspaceId) {
        RequestContext.requireUserId();
        jdbc.update("UPDATE id_org_member SET deleted_at=NOW() WHERE org_id=? AND user_id=?", workspaceId, id);
        return ApiResult.ok(null);
    }

    private List<Map<String, Object>> workspacesOf(long userId) {
        return jdbc.queryForList(
                "SELECT o.id, o.name, m.role, m.gmt_create AS joinedAt FROM id_org_member m"
                        + " JOIN id_org o ON o.id=m.org_id"
                        + " WHERE m.user_id=? AND m.deleted_at IS NULL AND o.deleted_at IS NULL ORDER BY o.id", userId);
    }

    // ---------------------------------------------------------------- 管理员

    @GetMapping("/admins")
    public ApiResult<List<Map<String, Object>>> admins() {
        RequestContext.requireUserId();
        return ApiResult.ok(jdbc.queryForList(
                "SELECT id, username, nickname, email, gmt_create AS createdAt FROM id_user"
                        + " WHERE is_admin=1 AND deleted_at IS NULL ORDER BY id"));
    }

    @GetMapping("/admins/candidates")
    public ApiResult<List<Map<String, Object>>> adminCandidates(@RequestParam(required = false) String keyword) {
        RequestContext.requireUserId();
        String like = keyword == null || keyword.isBlank() ? "%" : "%" + keyword.trim() + "%";
        return ApiResult.ok(jdbc.queryForList(
                "SELECT id, username, nickname, email FROM id_user WHERE is_admin=0 AND deleted_at IS NULL"
                        + " AND (username LIKE ? OR nickname LIKE ?) ORDER BY id LIMIT 50", like, like));
    }

    @PostMapping("/admins/{userId}")
    public ApiResult<Void> grantAdmin(@PathVariable long userId) {
        RequestContext.requireUserId();
        jdbc.update("UPDATE id_user SET is_admin=1 WHERE id=?", userId);
        return ApiResult.ok(null);
    }

    @DeleteMapping("/admins/{userId}")
    public ApiResult<Void> revokeAdmin(@PathVariable long userId) {
        long uid = RequestContext.requireUserId();
        if (uid == userId) {
            throw new BizException("20002", "不能撤销自己的管理员权限");
        }
        jdbc.update("UPDATE id_user SET is_admin=0 WHERE id=?", userId);
        return ApiResult.ok(null);
    }

    // ---------------------------------------------------------------- 联邦身份

    @GetMapping("/federation/overview")
    public ApiResult<Map<String, Object>> federationOverview() {
        RequestContext.requireUserId();
        List<Map<String, Object>> idpRows = idpList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("idps", idpRows);
        out.put("connections", connectionList());
        out.put("enabled", !idpRows.isEmpty());
        return ApiResult.ok(out);
    }

    @GetMapping("/federation/idps")
    public ApiResult<List<Map<String, Object>>> idps() {
        RequestContext.requireUserId();
        return ApiResult.ok(idpList());
    }

    private List<Map<String, Object>> idpList() {
        return jdbc.queryForList(
                "SELECT id, name, metadata_json AS metadata, status, gmt_create AS createdAt FROM id_idp"
                        + " WHERE deleted_at IS NULL ORDER BY id");
    }

    @PostMapping("/federation/idps")
    public ApiResult<Map<String, Object>> createIdp(@RequestBody IdpRequest req) {
        RequestContext.requireUserId();
        if (req.name() == null || req.name().isBlank()) {
            throw new BizException("10001", "名称不能为空");
        }
        jdbc.update("INSERT INTO id_idp (name, status) VALUES (?, 'ACTIVE')", req.name().trim());
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM id_idp", Long.class);
        return ApiResult.ok(Map.of("id", id, "name", req.name().trim()));
    }

    @DeleteMapping("/federation/idps/{id}")
    public ApiResult<Void> deleteIdp(@PathVariable long id) {
        RequestContext.requireUserId();
        jdbc.update("UPDATE id_idp SET deleted_at=NOW() WHERE id=?", id);
        return ApiResult.ok(null);
    }

    @GetMapping("/federation/connections")
    public ApiResult<List<Map<String, Object>>> connections() {
        RequestContext.requireUserId();
        return ApiResult.ok(connectionList());
    }

    private List<Map<String, Object>> connectionList() {
        return jdbc.queryForList(
                "SELECT c.id, c.idp_id AS idpId, i.name AS idpName, c.entity_id AS entityId,"
                        + " c.detail_json AS detail, c.gmt_create AS createdAt FROM id_idp_connection c"
                        + " LEFT JOIN id_idp i ON i.id=c.idp_id WHERE c.deleted_at IS NULL ORDER BY c.id");
    }

    @PostMapping("/federation/connections")
    public ApiResult<Map<String, Object>> createConnection(@RequestBody ConnectionRequest req) {
        RequestContext.requireUserId();
        if (req.idpId() == null) {
            throw new BizException("10001", "IdP 不能为空");
        }
        jdbc.update("INSERT INTO id_idp_connection (idp_id, entity_id) VALUES (?,?)", req.idpId(), req.entityId());
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM id_idp_connection", Long.class);
        return ApiResult.ok(Map.of("id", id, "idpId", req.idpId()));
    }

    @DeleteMapping("/federation/connections/{id}")
    public ApiResult<Void> deleteConnection(@PathVariable long id) {
        RequestContext.requireUserId();
        jdbc.update("UPDATE id_idp_connection SET deleted_at=NOW() WHERE id=?", id);
        return ApiResult.ok(null);
    }
}
