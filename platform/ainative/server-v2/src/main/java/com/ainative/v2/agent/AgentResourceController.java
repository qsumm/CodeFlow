package com.ainative.v2.agent;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.BizException;
import com.ainative.v2.common.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体子资源:版本 / 技能 / 仓库权限 / 记忆库授权 / 上线离线 / 提交评审。
 * 主资源 CRUD 在 AgentController,这里只挂 /api/agents/{id} 下的从属集合。
 */
@RestController
@RequestMapping("/api/agents")
public class AgentResourceController {
    public record IdRequest(Long id) {}
    public record PermRequest(String perm) {}
    public record ReviewRequest(String comment) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentResourceController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private long ws() {
        return RequestContext.workspaceId();
    }

    private void requireAgent(long agentId) {
        if (jdbc.queryForList("SELECT 1 FROM ag_agent WHERE id=? AND workspace_id=? AND deleted_at IS NULL",
                agentId, ws()).isEmpty()) {
            throw new BizException("17001", "智能体不存在");
        }
    }

    // ---------------------------------------------------------------- 版本

    @GetMapping("/{id}/versions")
    public ApiResult<List<Map<String, Object>>> versions(@PathVariable long id) {
        RequestContext.requireUserId();
        requireAgent(id);
        return ApiResult.ok(jdbc.queryForList(
                "SELECT id, agent_id AS agentId, version_no AS versionNo, snapshot_json AS snapshot,"
                        + " gmt_create AS createdAt FROM ag_version WHERE agent_id=? AND deleted_at IS NULL"
                        + " ORDER BY version_no DESC", id));
    }

    @GetMapping("/{id}/versions/{versionId}")
    public ApiResult<Map<String, Object>> version(@PathVariable long id, @PathVariable long versionId) {
        RequestContext.requireUserId();
        requireAgent(id);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, agent_id AS agentId, version_no AS versionNo, snapshot_json AS snapshot,"
                        + " gmt_create AS createdAt FROM ag_version WHERE id=? AND agent_id=?", versionId, id);
        if (rows.isEmpty()) {
            throw new BizException("17003", "版本不存在");
        }
        return ApiResult.ok(rows.get(0));
    }

    /** 发布新版本:把当前配置快照存档。 */
    @PostMapping("/{id}/submit")
    public ApiResult<Map<String, Object>> submit(@PathVariable long id) {
        RequestContext.requireUserId();
        requireAgent(id);
        Map<String, Object> agent = jdbc.queryForMap(
                "SELECT name, role_code, role_name, soul_md, status FROM ag_agent WHERE id=?", id);
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no),0)+1 FROM ag_version WHERE agent_id=?", Integer.class, id);
        String snapshot;
        try {
            snapshot = mapper.writeValueAsString(agent);
        } catch (Exception e) {
            snapshot = "{}";
        }
        jdbc.update("INSERT INTO ag_version (workspace_id, agent_id, version_no, snapshot_json) VALUES (?,?,?,?)",
                ws(), id, next, snapshot);
        Long versionId = jdbc.queryForObject("SELECT MAX(id) FROM ag_version WHERE agent_id=?", Long.class, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agentId", id);
        out.put("versionId", versionId);
        out.put("versionNo", next);
        out.put("status", "SUBMITTED");
        return ApiResult.ok(out);
    }

    @PostMapping("/{id}/approve")
    public ApiResult<Map<String, Object>> approve(@PathVariable long id) {
        RequestContext.requireUserId();
        requireAgent(id);
        jdbc.update("UPDATE ag_agent SET status='ACTIVE' WHERE id=?", id);
        return ApiResult.ok(Map.of("agentId", id, "status", "ACTIVE"));
    }

    @PostMapping("/{id}/reject")
    public ApiResult<Map<String, Object>> reject(@PathVariable long id, @RequestBody(required = false) ReviewRequest req) {
        RequestContext.requireUserId();
        requireAgent(id);
        jdbc.update("UPDATE ag_agent SET status='REJECTED' WHERE id=?", id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agentId", id);
        out.put("status", "REJECTED");
        out.put("comment", req == null ? null : req.comment());
        return ApiResult.ok(out);
    }

    @PostMapping("/{id}/rollback")
    public ApiResult<Map<String, Object>> rollback(@PathVariable long id, @RequestBody IdRequest req) {
        RequestContext.requireUserId();
        requireAgent(id);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT snapshot_json FROM ag_version WHERE id=? AND agent_id=?", req.id(), id);
        if (rows.isEmpty()) {
            throw new BizException("17003", "版本不存在");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> snap = mapper.readValue(String.valueOf(rows.get(0).get("snapshot_json")), Map.class);
            jdbc.update("UPDATE ag_agent SET name=?, role_code=?, role_name=?, soul_md=? WHERE id=?",
                    snap.get("name"), snap.get("role_code"), snap.get("role_name"), snap.get("soul_md"), id);
        } catch (Exception e) {
            throw new BizException("17004", "版本快照损坏");
        }
        return ApiResult.ok(Map.of("agentId", id, "versionId", req.id(), "status", "ROLLED_BACK"));
    }

    @GetMapping("/reviews/count")
    public ApiResult<Long> reviewCount() {
        RequestContext.requireUserId();
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ag_agent WHERE workspace_id=? AND status='SUBMITTED' AND deleted_at IS NULL",
                Long.class, ws());
        return ApiResult.ok(n == null ? 0L : n);
    }

    // ---------------------------------------------------------------- 技能

    @GetMapping("/{id}/skills")
    public ApiResult<List<Map<String, Object>>> skills(@PathVariable long id) {
        RequestContext.requireUserId();
        requireAgent(id);
        return ApiResult.ok(jdbc.queryForList(
                "SELECT r.id, r.skill_id AS skillId, s.name, s.version_label AS versionLabel"
                        + " FROM ag_skill_ref r LEFT JOIN mm_skill s ON s.id=r.skill_id"
                        + " WHERE r.agent_id=? AND r.deleted_at IS NULL ORDER BY r.id", id));
    }

    @PostMapping("/{id}/skills")
    public ApiResult<Void> addSkill(@PathVariable long id, @RequestBody IdRequest req) {
        RequestContext.requireUserId();
        requireAgent(id);
        if (req.id() == null) {
            throw new BizException("10001", "技能 ID 不能为空");
        }
        jdbc.update("INSERT INTO ag_skill_ref (workspace_id, agent_id, skill_id) VALUES (?,?,?)",
                ws(), id, req.id());
        return ApiResult.ok(null);
    }

    @DeleteMapping("/{id}/skills/{refId}")
    public ApiResult<Void> removeSkill(@PathVariable long id, @PathVariable long refId) {
        RequestContext.requireUserId();
        requireAgent(id);
        jdbc.update("UPDATE ag_skill_ref SET deleted_at=NOW() WHERE id=? AND agent_id=?", refId, id);
        return ApiResult.ok(null);
    }

    // ---------------------------------------------------------------- 仓库

    @GetMapping("/{id}/repos")
    public ApiResult<List<Map<String, Object>>> repos(@PathVariable long id) {
        RequestContext.requireUserId();
        requireAgent(id);
        return ApiResult.ok(jdbc.queryForList(
                "SELECT p.id, p.repo_id AS repoId, r.name, r.url, p.perm"
                        + " FROM ag_repo_perm p LEFT JOIN as_repo r ON r.id=p.repo_id"
                        + " WHERE p.agent_id=? AND p.deleted_at IS NULL ORDER BY p.id", id));
    }

    @PostMapping("/{id}/repos")
    public ApiResult<Void> addRepo(@PathVariable long id, @RequestBody IdRequest req) {
        RequestContext.requireUserId();
        requireAgent(id);
        if (req.id() == null) {
            throw new BizException("10001", "仓库 ID 不能为空");
        }
        jdbc.update("INSERT INTO ag_repo_perm (workspace_id, agent_id, repo_id, perm) VALUES (?,?,?, 'READ')",
                ws(), id, req.id());
        return ApiResult.ok(null);
    }

    @DeleteMapping("/{id}/repos/{refId}")
    public ApiResult<Void> removeRepo(@PathVariable long id, @PathVariable long refId) {
        RequestContext.requireUserId();
        requireAgent(id);
        jdbc.update("UPDATE ag_repo_perm SET deleted_at=NOW() WHERE id=? AND agent_id=?", refId, id);
        return ApiResult.ok(null);
    }

    // ---------------------------------------------------------------- 记忆库

    @GetMapping("/{id}/memories")
    public ApiResult<List<Map<String, Object>>> memories(@PathVariable long id) {
        RequestContext.requireUserId();
        requireAgent(id);
        return ApiResult.ok(jdbc.queryForList(
                "SELECT m.id, m.store_id AS storeId, s.name, m.perm FROM ag_memory_ref m"
                        + " LEFT JOIN mm_store s ON s.id=m.store_id"
                        + " WHERE m.agent_id=? AND m.deleted_at IS NULL ORDER BY m.id", id));
    }

    @PostMapping("/{id}/memories")
    public ApiResult<Void> addMemory(@PathVariable long id, @RequestBody IdRequest req) {
        RequestContext.requireUserId();
        requireAgent(id);
        if (req.id() == null) {
            throw new BizException("10001", "记忆库 ID 不能为空");
        }
        jdbc.update("INSERT INTO ag_memory_ref (workspace_id, agent_id, store_id, perm) VALUES (?,?,?, 'READ')",
                ws(), id, req.id());
        return ApiResult.ok(null);
    }

    @DeleteMapping("/{id}/memories/{refId}")
    public ApiResult<Void> removeMemory(@PathVariable long id, @PathVariable long refId) {
        RequestContext.requireUserId();
        requireAgent(id);
        jdbc.update("UPDATE ag_memory_ref SET deleted_at=NOW() WHERE id=? AND agent_id=?", refId, id);
        return ApiResult.ok(null);
    }

    // ---------------------------------------------------------------- 上下线

    @PostMapping("/{id}/online")
    public ApiResult<Map<String, Object>> online(@PathVariable long id) {
        RequestContext.requireUserId();
        requireAgent(id);
        jdbc.update("UPDATE ag_agent SET status='ACTIVE' WHERE id=?", id);
        jdbc.update("UPDATE ag_executor SET status='ONLINE' WHERE agent_id=? AND deleted_at IS NULL", id);
        return ApiResult.ok(Map.of("agentId", id, "status", "ACTIVE"));
    }

    @PostMapping("/{id}/offline")
    public ApiResult<Map<String, Object>> offline(@PathVariable long id) {
        RequestContext.requireUserId();
        requireAgent(id);
        jdbc.update("UPDATE ag_agent SET status='OFFLINE' WHERE id=?", id);
        jdbc.update("UPDATE ag_executor SET status='OFFLINE' WHERE agent_id=? AND deleted_at IS NULL", id);
        return ApiResult.ok(Map.of("agentId", id, "status", "OFFLINE"));
    }

    // ---------------------------------------------------------------- 环境变量

    @GetMapping("/{id}/environment-variables")
    public ApiResult<List<Map<String, Object>>> envVars(@PathVariable long id) {
        RequestContext.requireUserId();
        requireAgent(id);
        return ApiResult.ok(jdbc.queryForList(
                "SELECT r.id, r.env_key AS envKey, e.key_name AS keyName, e.is_secret AS secret FROM ag_env_ref r"
                        + " LEFT JOIN as_env_var e ON CAST(e.id AS CHAR)=r.env_key"
                        + " WHERE r.agent_id=? AND r.deleted_at IS NULL ORDER BY r.id", id));
    }

    @PostMapping("/{id}/environment-variables")
    public ApiResult<Void> bindEnvVar(@PathVariable long id, @RequestBody IdRequest req) {
        RequestContext.requireUserId();
        requireAgent(id);
        if (req.id() == null) {
            throw new BizException("10001", "环境变量 ID 不能为空");
        }
        jdbc.update("INSERT INTO ag_env_ref (workspace_id, agent_id, env_key) VALUES (?,?,?)",
                ws(), id, String.valueOf(req.id()));
        return ApiResult.ok(null);
    }

    @DeleteMapping("/{id}/environment-variables/{refId}")
    public ApiResult<Void> unbindEnvVar(@PathVariable long id, @PathVariable long refId) {
        RequestContext.requireUserId();
        requireAgent(id);
        jdbc.update("UPDATE ag_env_ref SET deleted_at=NOW() WHERE id=? AND agent_id=?", refId, id);
        return ApiResult.ok(null);
    }
}
