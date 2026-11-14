package com.ainative.v2.ws;

import com.ainative.v2.auth.JwtService;
import com.ainative.v2.common.BizException;
import com.ainative.v2.common.PageResult;
import com.ainative.v2.common.RequestContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作区(组织)域。
 *
 * <p>数据模型沿用 v1 的 org/org_member 迁移结果:v2 用 id_org 表示工作区,
 * id_org_member 表示成员关系。访问级别由「是否 owner」+ 全局 is_admin 推导:
 * owner / 平台管理员 = ADMIN,其余成员 = READ_WRITE(只读域控制在域服务内)。
 */
@Service
public class WorkspaceService {
    private final JdbcTemplate jdbc;
    private final JwtService jwt;

    public WorkspaceService(JdbcTemplate jdbc, JwtService jwt) {
        this.jdbc = jdbc;
        this.jwt = jwt;
    }

    // ---------------------------------------------------------------- 查询

    public List<Map<String, Object>> mine(long userId) {
        return jdbc.queryForList(
                "SELECT o.id, o.name, o.description, o.background, o.owner_id AS ownerId, o.version,"
                        + " o.gmt_create AS createdAt FROM id_org o"
                        + " JOIN id_org_member m ON m.org_id=o.id AND m.user_id=? AND m.deleted_at IS NULL"
                        + " WHERE o.deleted_at IS NULL ORDER BY o.id", userId);
    }

    public Map<String, Object> current(long workspaceId, long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, description, background, owner_id AS ownerId, version, gmt_create AS createdAt"
                        + " FROM id_org WHERE id=? AND deleted_at IS NULL", workspaceId);
        if (rows.isEmpty()) {
            throw new BizException("11001", "项目不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
        out.put("accessLevel", accessLevel(workspaceId, userId));
        out.put("isOwner", isOwner(workspaceId, userId));
        out.put("canManage", canManage(workspaceId, userId));
        return out;
    }

    public PageResult<Map<String, Object>> all(String keyword, int pageNum, int pageSize, long userId) {
        int limit = Math.max(1, Math.min(pageSize, 100));
        int offset = Math.max(0, (Math.max(pageNum, 1) - 1) * limit);
        StringBuilder where = new StringBuilder(" WHERE o.deleted_at IS NULL");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND o.name LIKE ?");
            args.add("%" + keyword.trim() + "%");
        }
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM id_org o" + where, Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        String sql = "SELECT o.id, o.name, o.description, o.owner_id AS ownerId, o.version, o.gmt_create AS createdAt,"
                + " (SELECT COUNT(*) FROM id_org_member m WHERE m.org_id=o.id AND m.deleted_at IS NULL) AS memberCount,"
                + " (SELECT u.nickname FROM id_user u WHERE u.id=o.owner_id) AS ownerName"
                + " FROM id_org o" + where + " ORDER BY o.id DESC LIMIT " + limit + " OFFSET " + offset;
        List<Map<String, Object>> list = jdbc.queryForList(sql, pageArgs.toArray());
        return new PageResult<>(list, total == null ? 0 : total, pageNum, limit);
    }

    /**
     * 成员列表(前端 MemberVO 契约):userId/username/email/nickname/joinedAt/owner/
     * accessLevel/identityTags/roleId/roleCode/roleName/readOnlyDomains。
     * identityTags 缺失会让成员页 `tags.length` 直接崩(Exception实测),必须给数组。
     */
    public List<Map<String, Object>> members(long workspaceId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT m.user_id AS userId, u.username, u.nickname, u.email, m.role,"
                        + " m.identity_tags_json,"
                        + " CASE WHEN o.owner_id=m.user_id THEN 'OWNER' ELSE COALESCE(m.role,'MEMBER') END AS roleKey,"
                        + " m.gmt_create AS joinedAt, o.owner_id AS _ownerId"
                        + " FROM id_org_member m JOIN id_org o ON o.id=m.org_id"
                        + " LEFT JOIN id_user u ON u.id=m.user_id"
                        + " WHERE m.org_id=? AND m.deleted_at IS NULL ORDER BY m.id", workspaceId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long userId = row.get("userId") instanceof Number n ? n.longValue() : 0L;
            long ownerId = row.get("_ownerId") instanceof Number n ? n.longValue() : 0L;
            boolean owner = ownerId == userId;
            String role = row.get("role") == null ? "MEMBER" : String.valueOf(row.get("role"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", userId);
            m.put("username", row.get("username"));
            m.put("email", row.get("email"));
            m.put("nickname", row.get("nickname"));
            m.put("joinedAt", row.get("joinedAt"));
            m.put("owner", owner);
            m.put("accessLevel", owner || isPlatformAdmin(userId) ? "ADMIN" : "READ_WRITE");
            Object tagsRaw = row.get("identity_tags_json");
            m.put("identityTags", parseTags(tagsRaw));
            // v2 用 role 字符串承载角色;roleId 留空,roleCode 给 role 字符串供 UI 展示
            m.put("roleId", null);
            m.put("roleCode", role);
            m.put("roleName", null);
            m.put("readOnlyDomains", List.of());
            m.remove("_ownerId");
            out.add(m);
        }
        return out;
    }

    /** identity_tags_json(JSON 列)→ 字符串数组;空/异常一律回空数组。 */
    private static List<String> parseTags(Object raw) {
        if (raw == null) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(String.valueOf(raw));
            List<String> tags = new ArrayList<>();
            if (node.isArray()) {
                node.forEach(n -> tags.add(n.asText()));
            }
            return tags;
        } catch (Exception e) {
            return List.of();
        }
    }

    public Map<String, Object> membership(long workspaceId, long userId) {
        // 前端 CurrentMembershipVO = MemberVO:进入项目时侧边栏"管"角标与权限判定都读它。
        Map<String, Object> self = null;
        for (Map<String, Object> m : members(workspaceId)) {
            if (((Number) m.get("userId")).longValue() == userId) {
                self = m;
                break;
            }
        }
        Map<String, Object> out = self == null ? new LinkedHashMap<>() : new LinkedHashMap<>(self);
        boolean owner = isOwner(workspaceId, userId);
        String level = accessLevel(workspaceId, userId);
        out.put("userId", userId);
        out.put("owner", owner);
        out.put("accessLevel", level);
        out.put("isOwner", owner);
        out.put("canManage", "ADMIN".equals(level));
        if (!out.containsKey("identityTags")) {
            out.put("identityTags", List.of());
        }
        if (!out.containsKey("readOnlyDomains")) {
            out.put("readOnlyDomains", List.of());
        }
        return out;
    }

    public List<Map<String, Object>> roles(long workspaceId) {
        return jdbc.queryForList(
                "SELECT id, role_key AS roleKey, name, permissions_json AS permissions FROM id_ws_role"
                        + " WHERE workspace_id=? AND deleted_at IS NULL ORDER BY id", workspaceId);
    }

    public List<Map<String, Object>> accessRequests(long workspaceId) {
        return jdbc.queryForList(
                "SELECT r.id, r.workspace_id AS workspaceId, r.user_id AS userId, r.status,"
                        + " r.gmt_create AS createdAt, u.username, u.nickname"
                        + " FROM id_ws_request r LEFT JOIN id_user u ON u.id=r.user_id"
                        + " WHERE r.workspace_id=? AND r.deleted_at IS NULL ORDER BY r.id DESC", workspaceId);
    }

    public PageResult<Map<String, Object>> recycleBin(String keyword, int pageNum, int pageSize) {
        int limit = Math.max(1, Math.min(pageSize, 100));
        int offset = Math.max(0, (Math.max(pageNum, 1) - 1) * limit);
        StringBuilder where = new StringBuilder(" WHERE deleted_at IS NOT NULL");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND name LIKE ?");
            args.add("%" + keyword.trim() + "%");
        }
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM id_org" + where, Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT id, name, description, owner_id AS ownerId, deleted_at AS deletedAt FROM id_org"
                        + where + " ORDER BY id DESC LIMIT " + limit + " OFFSET " + offset, pageArgs.toArray());
        return new PageResult<>(list, total == null ? 0 : total, pageNum, limit);
    }

    // ---------------------------------------------------------------- 写入

    @Transactional
    public Map<String, Object> create(String name, String description, long creatorId) {
        if (name == null || name.isBlank()) {
            throw new BizException("10001", "项目名称不能为空");
        }
        jdbc.update("INSERT INTO id_org (name, owner_id, description) VALUES (?,?,?)",
                name.trim(), creatorId, description);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM id_org", Long.class);
        jdbc.update("INSERT INTO id_org_member (org_id, user_id, role) VALUES (?,?, 'OWNER')", id, creatorId);
        return current(id, creatorId);
    }

    public Map<String, Object> update(long workspaceId, long userId, String name, String description,
                                      String background, Integer expectedVersion) {
        requireManage(workspaceId, userId);
        List<Object> args = new ArrayList<>();
        StringBuilder set = new StringBuilder();
        if (name != null && !name.isBlank()) {
            set.append(set.length() == 0 ? "" : ",").append("name=?");
            args.add(name.trim());
        }
        if (description != null) {
            set.append(set.length() == 0 ? "" : ",").append("description=?");
            args.add(description);
        }
        if (background != null) {
            set.append(set.length() == 0 ? "" : ",").append("background=?");
            args.add(background);
        }
        if (set.length() == 0) {
            return current(workspaceId, userId);
        }
        if (expectedVersion != null) {
            args.add(expectedVersion);
            args.add(workspaceId);
            int rows = jdbc.update("UPDATE id_org SET " + set + ", version=version+1 WHERE id=? AND version=?", args.toArray());
            if (rows == 0) {
                throw new BizException("13005", "数据已变更,请刷新后重试");
            }
        } else {
            args.add(workspaceId);
            jdbc.update("UPDATE id_org SET " + set + ", version=version+1 WHERE id=?", args.toArray());
        }
        return current(workspaceId, userId);
    }

    public Map<String, Object> delete(long workspaceId, long userId) {
        requireManage(workspaceId, userId);
        jdbc.update("UPDATE id_org SET deleted_at=NOW() WHERE id=? AND deleted_at IS NULL", workspaceId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", workspaceId);
        out.put("deleted", true);
        return out;
    }

    public Map<String, Object> restore(long workspaceId, long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT owner_id FROM id_org WHERE id=?", workspaceId);
        if (rows.isEmpty()) {
            throw new BizException("11001", "项目不存在");
        }
        long ownerId = rows.get(0).get("owner_id") instanceof Number n ? n.longValue() : 0L;
        if (ownerId != userId && !isPlatformAdmin(userId)) {
            throw new BizException("10403", "无权限");
        }
        jdbc.update("UPDATE id_org SET deleted_at=NULL WHERE id=?", workspaceId);
        return current(workspaceId, userId);
    }

    /** 切换工作区:校验成员身份后签发带 workspace 声明的新令牌。 */
    public Map<String, Object> switchWorkspace(long workspaceId, long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT o.id, o.name, o.deleted_at FROM id_org o WHERE o.id=?", workspaceId);
        if (rows.isEmpty() || rows.get(0).get("deleted_at") != null) {
            throw new BizException("11001", "项目不存在");
        }
        boolean member = !jdbc.queryForList(
                "SELECT 1 FROM id_org_member WHERE org_id=? AND user_id=? AND deleted_at IS NULL",
                workspaceId, userId).isEmpty();
        if (!member && !isPlatformAdmin(userId)) {
            throw new BizException("11001", "非该工作空间成员");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accessToken", jwt.issue(userId, workspaceId));
        out.put("workspace", current(workspaceId, userId));
        return out;
    }

    public void addMember(long workspaceId, long operatorId, long userId, String role) {
        requireManage(workspaceId, operatorId);
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id FROM id_org_member WHERE org_id=? AND user_id=?", workspaceId, userId);
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO id_org_member (org_id, user_id, role) VALUES (?,?,?)",
                    workspaceId, userId, role == null || role.isBlank() ? "MEMBER" : role);
        } else {
            jdbc.update("UPDATE id_org_member SET role=?, deleted_at=NULL WHERE id=?",
                    role == null || role.isBlank() ? "MEMBER" : role, existing.get(0).get("id"));
        }
    }

    public void updateMemberRole(long workspaceId, long operatorId, long userId, String role) {
        requireManage(workspaceId, operatorId);
        jdbc.update("UPDATE id_org_member SET role=? WHERE org_id=? AND user_id=?",
                role == null || role.isBlank() ? "MEMBER" : role, workspaceId, userId);
    }

    /** 成员身份标签(展示用):JSON 数组落 identity_tags_json。 */
    public void updateIdentityTags(long workspaceId, long operatorId, long userId, Object tags) {
        requireManage(workspaceId, operatorId);
        String json;
        try {
            json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    tags == null ? java.util.List.of() : tags);
        } catch (Exception e) {
            json = "[]";
        }
        jdbc.update("UPDATE id_org_member SET identity_tags_json=? WHERE org_id=? AND user_id=?",
                json, workspaceId, userId);
    }

    public void removeMember(long workspaceId, long operatorId, long userId) {
        requireManage(workspaceId, operatorId);
        List<Map<String, Object>> org = jdbc.queryForList("SELECT owner_id FROM id_org WHERE id=?", workspaceId);
        long ownerId = org.isEmpty() || !(org.get(0).get("owner_id") instanceof Number n) ? 0L : n.longValue();
        if (ownerId == userId) {
            throw new BizException("11002", "不能移除项目所有者");
        }
        jdbc.update("UPDATE id_org_member SET deleted_at=NOW() WHERE org_id=? AND user_id=?", workspaceId, userId);
    }

    public void transferOwner(long workspaceId, long operatorId, long newOwnerId) {
        requireManage(workspaceId, operatorId);
        if (jdbc.queryForList("SELECT 1 FROM id_user WHERE id=? AND deleted_at IS NULL", newOwnerId).isEmpty()) {
            throw new BizException("11003", "目标用户不存在");
        }
        jdbc.update("UPDATE id_org SET owner_id=? WHERE id=?", newOwnerId, workspaceId);
        addMember(workspaceId, operatorId, newOwnerId, "OWNER");
    }

    // ---------------------------------------------------------------- 申请

    public Map<String, Object> submitAccessRequest(long workspaceId, long userId) {
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id FROM id_ws_request WHERE workspace_id=? AND user_id=? AND status='PENDING' AND deleted_at IS NULL",
                workspaceId, userId);
        if (!existing.isEmpty()) {
            throw new BizException("11004", "已有待审批的申请");
        }
        jdbc.update("INSERT INTO id_ws_request (workspace_id, user_id, status) VALUES (?,?, 'PENDING')",
                workspaceId, userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workspaceId", workspaceId);
        out.put("status", "PENDING");
        return out;
    }

    public void decideAccessRequest(long workspaceId, long requestId, long operatorId, boolean approve) {
        requireManage(workspaceId, operatorId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT user_id FROM id_ws_request WHERE id=? AND workspace_id=? AND status='PENDING'",
                requestId, workspaceId);
        if (rows.isEmpty()) {
            throw new BizException("11005", "申请不存在或已处理");
        }
        long userId = ((Number) rows.get(0).get("user_id")).longValue();
        jdbc.update("UPDATE id_ws_request SET status=?, decided_by=?, decided_at=NOW() WHERE id=?",
                approve ? "APPROVED" : "REJECTED", operatorId, requestId);
        if (approve) {
            addMember(workspaceId, operatorId, userId, "MEMBER");
        }
    }

    public void cancelAccessRequest(long workspaceId, long requestId, long userId) {
        jdbc.update("UPDATE id_ws_request SET status='CANCELED' WHERE id=? AND workspace_id=? AND user_id=? AND status='PENDING'",
                requestId, workspaceId, userId);
    }

    // ---------------------------------------------------------------- 权限

    private boolean isOwner(long workspaceId, long userId) {
        List<Long> rows = jdbc.queryForList(
                "SELECT owner_id FROM id_org WHERE id=? AND deleted_at IS NULL", Long.class, workspaceId);
        return !rows.isEmpty() && rows.get(0) != null && rows.get(0) == userId;
    }

    private boolean isMember(long workspaceId, long userId) {
        return !jdbc.queryForList(
                "SELECT 1 FROM id_org_member WHERE org_id=? AND user_id=? AND deleted_at IS NULL",
                workspaceId, userId).isEmpty();
    }

    boolean isPlatformAdmin(long userId) {
        List<Integer> rows = jdbc.queryForList(
                "SELECT is_admin FROM id_user WHERE id=? AND deleted_at IS NULL", Integer.class, userId);
        return !rows.isEmpty() && rows.get(0) != null && rows.get(0) == 1;
    }

    public String accessLevel(long workspaceId, long userId) {
        if (isOwner(workspaceId, userId) || isPlatformAdmin(userId)) {
            return "ADMIN";
        }
        return isMember(workspaceId, userId) ? "READ_WRITE" : "READ_ONLY";
    }

    private boolean canManage(long workspaceId, long userId) {
        return "ADMIN".equals(accessLevel(workspaceId, userId));
    }

    private void requireManage(long workspaceId, long userId) {
        if (!canManage(workspaceId, userId)) {
            throw new BizException("10403", "无权限");
        }
    }

    long currentUserId() {
        return RequestContext.requireUserId();
    }
}
