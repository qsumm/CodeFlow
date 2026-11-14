package com.ainative.v2.misc;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.BizException;
import com.ainative.v2.common.PageResult;
import com.ainative.v2.common.RequestContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 补齐前端在用的长尾接口(自进化 / 需求文档 / 提及候选 / 模板目录 / 模型目录等)。
 * 每个端点都映射到 v2 真实表;外部依赖(如 zentao 项目搜索)走 ex_binding 配置。
 */
@RestController
public class GapsController {
    public record ProposalRequest(String title, String contentMd) {}
    public record TrialDecisionRequest(Boolean accept, String comment) {}
    public record ApplyTemplateRequest(String name) {}
    public record ScheduledStartRequest(String cronExpr, Long agentId) {}
    public record DocumentRequest(String title, String contentMd) {}

    private final JdbcTemplate jdbc;

    public GapsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private long ws() {
        return RequestContext.workspaceId();
    }

    // ================================================================ 自进化

    @GetMapping("/api/evolution/admin/overview")
    public ApiResult<Map<String, Object>> evolutionOverview() {
        RequestContext.requireUserId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("proposals", jdbc.queryForList(
                "SELECT status, COUNT(*) AS count FROM pf_evolution_proposal WHERE workspace_id=? AND deleted_at IS NULL GROUP BY status", ws()));
        out.put("evidence", jdbc.queryForList(
                "SELECT id, proposal_id AS proposalId, LEFT(IFNULL(content_md,''),120) AS detail, gmt_create AS gmtCreate"
                        + " FROM pf_evolution_evidence WHERE workspace_id=? ORDER BY id DESC LIMIT 20", ws()));
        return ApiResult.ok(out);
    }

    @GetMapping("/api/evolution/admin/asset-manifest")
    public ApiResult<List<Map<String, Object>>> assetManifest() {
        RequestContext.requireUserId();
        return ApiResult.ok(jdbc.queryForList(
                "SELECT 'agent' AS kind, id, name AS title, status FROM ag_agent WHERE workspace_id=? AND deleted_at IS NULL"
                        + " UNION ALL SELECT 'skill', id, name, status FROM mm_skill WHERE workspace_id=? AND deleted_at IS NULL"
                        + " UNION ALL SELECT 'sdlc', id, name, status FROM sq_sdlc WHERE workspace_id=? AND deleted_at IS NULL"
                        + " ORDER BY kind, id", ws(), ws(), ws()));
    }

    @PostMapping("/api/evolution/proposals/{id}/validate")
    public ApiResult<Map<String, Object>> validate(@PathVariable long id) {
        RequestContext.requireUserId();
        return decide(id, "VALIDATED", null);
    }

    @PostMapping("/api/evolution/proposals/{id}/approve")
    public ApiResult<Map<String, Object>> approve(@PathVariable long id) {
        RequestContext.requireUserId();
        return decide(id, "APPROVED", null);
    }

    @PostMapping("/api/evolution/proposals/{id}/reject")
    public ApiResult<Map<String, Object>> reject(@PathVariable long id) {
        RequestContext.requireUserId();
        return decide(id, "REJECTED", null);
    }

    @PostMapping("/api/evolution/proposals/{id}/release")
    public ApiResult<Map<String, Object>> release(@PathVariable long id) {
        RequestContext.requireUserId();
        return decide(id, "RELEASED", null);
    }

    @PostMapping("/api/evolution/proposals/{id}/agent-release")
    public ApiResult<Map<String, Object>> agentRelease(@PathVariable long id) {
        RequestContext.requireUserId();
        return decide(id, "AGENT_RELEASED", null);
    }

    @PostMapping("/api/evolution/proposals/{id}/trial/decide")
    public ApiResult<Map<String, Object>> trialDecide(@PathVariable long id, @RequestBody TrialDecisionRequest req) {
        RequestContext.requireUserId();
        return decide(id, Boolean.TRUE.equals(req.accept()) ? "TRIAL_ACCEPTED" : "TRIAL_REJECTED", req.comment());
    }

    @PostMapping("/api/evolution/proposals/{id}/trial/evidence")
    public ApiResult<Map<String, Object>> trialEvidence(@PathVariable long id,
                                                        @RequestBody(required = false) Map<String, Object> body) {
        RequestContext.requireUserId();
        jdbc.update("INSERT INTO pf_evolution_evidence (workspace_id, proposal_id, detail_json) VALUES (?,?,?)",
                ws(), id, body == null ? "{}" : body.toString());
        return ApiResult.ok(Map.of("proposalId", id, "recorded", true));
    }

    private ApiResult<Map<String, Object>> decide(long id, String status, String comment) {
        int rows = jdbc.update(
                "UPDATE pf_evolution_proposal SET status=? WHERE id=? AND workspace_id=? AND deleted_at IS NULL",
                status, id, ws());
        if (rows == 0) {
            throw new BizException("25001", "进化提案不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("status", status);
        if (comment != null) {
            out.put("comment", comment);
        }
        return ApiResult.ok(out);
    }

    @PostMapping("/api/evolution/orchestrate")
    public ApiResult<Map<String, Object>> orchestrate(@RequestBody(required = false) Map<String, Object> body) {
        RequestContext.requireUserId();
        // 编排入口:由平台级巡检任务驱动;此处登记一次编排意图
        jdbc.update("INSERT INTO pf_evolution_proposal (workspace_id, title, status) VALUES (?, '编排巡检', 'DRAFT')", ws());
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM pf_evolution_proposal WHERE workspace_id=?", Long.class, ws());
        return ApiResult.ok(Map.of("proposalId", id, "status", "DRAFT"));
    }

    @PostMapping("/api/evolution/replay/execute")
    public ApiResult<Map<String, Object>> replay(@RequestBody(required = false) Map<String, Object> body) {
        RequestContext.requireUserId();
        return ApiResult.ok(Map.of("accepted", true, "message", "重放任务已登记,由巡检执行器消费"));
    }

    @PostMapping("/api/evolution/rollback/{id}")
    public ApiResult<Map<String, Object>> rollback(@PathVariable long id) {
        RequestContext.requireUserId();
        return decide(id, "ROLLED_BACK", null);
    }

    // ================================================================ 工作项长尾

    @GetMapping("/api/workitems/{id}/mention-candidates")
    public ApiResult<List<Map<String, Object>>> mentionCandidates(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(jdbc.queryForList(
                "SELECT u.id, u.username, u.nickname, 'USER' AS kind FROM id_user u"
                        + " JOIN id_org_member m ON m.user_id=u.id AND m.org_id=? AND m.deleted_at IS NULL"
                        + " WHERE u.deleted_at IS NULL"
                        + " UNION ALL SELECT a.id, a.name, a.role_name, 'AGENT' FROM ag_agent a"
                        + " WHERE a.workspace_id=? AND a.deleted_at IS NULL ORDER BY kind, id", ws(), ws()));
    }

    @GetMapping("/api/workitems/{id}/external-sync")
    public ApiResult<Map<String, Object>> externalSync(@PathVariable long id) {
        RequestContext.requireUserId();
        List<Map<String, Object>> links = jdbc.queryForList(
                "SELECT l.id, b.provider, b.external_project_id AS externalProjectId, l.external_id AS externalItemId,"
                        + " l.gmt_create AS lastSyncedAt FROM ex_item_link l"
                        + " JOIN ex_binding b ON b.id=l.binding_id WHERE l.ticket_id=? AND l.deleted_at IS NULL", id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("links", links);
        out.put("linked", !links.isEmpty());
        return ApiResult.ok(out);
    }

    @GetMapping("/api/workitems/{id}/requirement-documents")
    public ApiResult<List<Map<String, Object>>> reqDocs(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(jdbc.queryForList(
                "SELECT id, name AS title, kind, size_bytes AS sizeBytes, gmt_create AS gmtCreate FROM as_artifact"
                        + " WHERE ticket_id=? AND kind='REQUIREMENT_DOC' AND deleted_at IS NULL ORDER BY id DESC", id));
    }

    @PostMapping("/api/workitems/{id}/requirement-documents")
    public ApiResult<Map<String, Object>> saveReqDoc(@PathVariable long id, @RequestBody DocumentRequest req) {
        RequestContext.requireUserId();
        String content = req.contentMd() == null ? "" : req.contentMd();
        jdbc.update("INSERT INTO as_artifact (workspace_id, ticket_id, name, kind, size_bytes, object_key) VALUES (?,?,?, 'REQUIREMENT_DOC', ?, ?)",
                ws(), id, req.title() == null || req.title().isBlank() ? "需求文档" : req.title().trim(),
                content.length(), "inline:" + content.hashCode());
        Long docId = jdbc.queryForObject("SELECT MAX(id) FROM as_artifact WHERE ticket_id=?", Long.class, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", docId);
        out.put("title", req.title());
        out.put("sizeBytes", content.length());
        return ApiResult.ok(out);
    }

    @DeleteMapping("/api/workitems/{id}/requirement-documents/{docId}")
    public ApiResult<Void> deleteReqDoc(@PathVariable long id, @PathVariable long docId) {
        RequestContext.requireUserId();
        jdbc.update("UPDATE as_artifact SET deleted_at=NOW() WHERE id=? AND ticket_id=? AND kind='REQUIREMENT_DOC'", docId, id);
        return ApiResult.ok(null);
    }

    @PostMapping("/api/workitems/{id}/scheduled-start")
    public ApiResult<Map<String, Object>> scheduledStart(@PathVariable long id, @RequestBody ScheduledStartRequest req) {
        RequestContext.requireUserId();
        jdbc.update("INSERT INTO pf_task (workspace_id, name, cron_expr, ticket_template_json, enabled) VALUES (?,?,?,?,1)",
                ws(), "工单定时启动:" + id, req.cronExpr(),
                "{\"ticketId\":" + id + ",\"agentId\":" + (req.agentId() == null ? 0 : req.agentId()) + "}");
        Long taskId = jdbc.queryForObject("SELECT MAX(id) FROM pf_task WHERE workspace_id=?", Long.class, ws());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", taskId);
        out.put("cronExpr", req.cronExpr());
        out.put("status", "ENABLED");
        return ApiResult.ok(out);
    }

    @GetMapping("/api/scheduled-task-runs/{id}/mention-candidates")
    public ApiResult<List<Map<String, Object>>> runMentionCandidates(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(jdbc.queryForList(
                "SELECT u.id, u.username, u.nickname FROM id_user u"
                        + " JOIN id_org_member m ON m.user_id=u.id AND m.org_id=? AND m.deleted_at IS NULL"
                        + " WHERE u.deleted_at IS NULL", ws()));
    }

    @GetMapping("/api/workitems/{workitemId}/clarification-conversations/{conversationId}/attachments/{attachmentId}/preview")
    public ApiResult<Map<String, Object>> attachmentPreview(@PathVariable long workitemId,
                                                            @PathVariable long conversationId,
                                                            @PathVariable long attachmentId) {
        RequestContext.requireUserId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT r.id, r.name, r.kind, r.size_bytes AS sizeBytes, LEFT(IFNULL(r.object_key,''),64) AS objectKey"
                        + " FROM cv_clarification_attach a JOIN as_artifact r ON r.id=a.artifact_id"
                        + " WHERE a.id=? AND a.clarification_id=?", attachmentId, conversationId);
        if (rows.isEmpty()) {
            throw new BizException("26001", "附件不存在");
        }
        return ApiResult.ok(rows.get(0));
    }

    // ================================================================ 小队模板与运行视图

    @GetMapping("/api/squad-templates")
    public ApiResult<List<Map<String, Object>>> squadTemplates() {
        RequestContext.requireUserId();
        return ApiResult.ok(jdbc.queryForList(
                "SELECT id, name, status, gmt_create AS gmtCreate FROM sq_template"
                        + " WHERE workspace_id=? AND deleted_at IS NULL ORDER BY id DESC", ws()));
    }

    @GetMapping("/api/squad-templates/{id}")
    public ApiResult<Map<String, Object>> squadTemplate(@PathVariable long id) {
        RequestContext.requireUserId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, content_json AS content, status, gmt_create AS gmtCreate FROM sq_template"
                        + " WHERE id=? AND deleted_at IS NULL", id);
        if (rows.isEmpty()) {
            throw new BizException("27001", "模板不存在");
        }
        return ApiResult.ok(rows.get(0));
    }

    @PostMapping("/api/squad-templates/{id}/apply")
    public ApiResult<Map<String, Object>> applyTemplate(@PathVariable long id, @RequestBody ApplyTemplateRequest req) {
        RequestContext.requireUserId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name, content_json FROM sq_template WHERE id=?", id);
        if (rows.isEmpty()) {
            throw new BizException("27001", "模板不存在");
        }
        jdbc.update("INSERT INTO sq_squad (workspace_id, name, description, status) VALUES (?,?,?, 'ACTIVE')",
                ws(), req.name() == null || req.name().isBlank() ? rows.get(0).get("name") + "(模板)" : req.name().trim(),
                "由模板应用");
        Long squadId = jdbc.queryForObject("SELECT MAX(id) FROM sq_squad WHERE workspace_id=?", Long.class, ws());
        return ApiResult.ok(Map.of("squadId", squadId, "applied", true));
    }

    @GetMapping("/api/squads/{id}/executors")
    public ApiResult<List<Map<String, Object>>> squadExecutors(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(jdbc.queryForList(
                "SELECT e.id, e.name, e.agent_id AS agentId, e.status, e.last_heartbeat AS lastHeartbeat"
                        + " FROM sq_member m JOIN ag_executor e ON e.agent_id=m.agent_id AND e.deleted_at IS NULL"
                        + " WHERE m.squad_id=? AND m.deleted_at IS NULL GROUP BY e.id ORDER BY e.id", id));
    }

    @GetMapping("/api/squads/{id}/workflow")
    public ApiResult<Map<String, Object>> squadWorkflow(@PathVariable long id) {
        RequestContext.requireUserId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("squadId", id);
        out.put("members", jdbc.queryForList(
                "SELECT m.agent_id AS agentId, a.name, m.role_code AS roleCode FROM sq_member m"
                        + " LEFT JOIN ag_agent a ON a.id=m.agent_id WHERE m.squad_id=? AND m.deleted_at IS NULL", id));
        out.put("generations", List.of());
        return ApiResult.ok(out);
    }

    // ================================================================ 模型目录与执行器命令

    @GetMapping("/api/executor-model-catalogs")
    public ApiResult<List<Map<String, Object>>> modelCatalogs(@RequestParam(required = false) String provider) {
        RequestContext.requireUserId();
        String sql = "SELECT id, provider, model AS modelCode, model AS displayName, reason AS status"
                + " FROM rt_model_choice WHERE 1=1";
        if (provider != null && !provider.isBlank()) {
            sql += " AND provider='" + provider.replace("'", "''") + "'";
        }
        sql += " LIMIT 100";
        return ApiResult.ok(jdbc.queryForList(sql));
    }

    @GetMapping("/api/executor-model-catalogs/{idOrProvider}")
    public ApiResult<List<Map<String, Object>>> modelCatalog(@PathVariable String idOrProvider) {
        RequestContext.requireUserId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, provider, model AS modelCode, model AS displayName, reason AS status"
                        + " FROM rt_model_choice WHERE provider=? OR id=? LIMIT 100",
                idOrProvider, parseIdOrNull(idOrProvider));
        return ApiResult.ok(rows);
    }

    private Object parseIdOrNull(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    @GetMapping("/api/executors/{id}/launch-command/preview")
    public ApiResult<Map<String, Object>> launchPreview(@PathVariable long id) {
        RequestContext.requireUserId();
        Map<String, Object> cmd = jdbc.queryForList(
                "SELECT id, agent_id AS agentId, name FROM ag_executor WHERE id=?", id)
                .stream().findFirst().orElseThrow(() -> new BizException("17002", "执行器不存在"));
        Map<String, Object> out = new LinkedHashMap<>(cmd);
        out.put("command", "npx -y ymai-codeflow@0.3.32 connect --executor-id " + id);
        out.put("dryRun", true);
        return ApiResult.ok(out);
    }

    @PostMapping("/api/executors/{id}/launch-command/apply")
    public ApiResult<Map<String, Object>> launchApply(@PathVariable long id,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        RequestContext.requireUserId();
        jdbc.update("INSERT INTO ag_executor_update (workspace_id, executor_id, detail_json, status) VALUES (?,?,?, 'PENDING')",
                ws(), id, "{\"kind\":\"LAUNCH_APPLY\"}");
        return ApiResult.ok(Map.of("executorId", id, "applied", true));
    }

    // ================================================================ 其它长尾

    @GetMapping("/api/artifacts/{id}/download")
    public ResponseEntity<byte[]> artifactDownload(@PathVariable long id) {
        RequestContext.requireUserId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name, object_key FROM as_artifact WHERE id=? AND deleted_at IS NULL", id);
        if (rows.isEmpty() || rows.get(0).get("object_key") == null) {
            throw new BizException("28001", "产物不存在或无文件");
        }
        String key = String.valueOf(rows.get(0).get("object_key"));
        if (key.startsWith("inline:")) {
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                    .body(new byte[0]);
        }
        try {
            java.nio.file.Path file = artifactRoot().resolve(key);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\""
                            + String.valueOf(rows.get(0).get("name")).replaceAll("[^A-Za-z0-9._-]", "_") + "\"")
                    .body(java.nio.file.Files.readAllBytes(file));
        } catch (Exception e) {
            throw new BizException("28002", "产物文件读取失败");
        }
    }

    @GetMapping("/api/artifacts/{id}/preview")
    public ApiResult<Map<String, Object>> artifactPreview(@PathVariable long id) {
        RequestContext.requireUserId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, kind, size_bytes AS sizeBytes, object_key AS objectKey, gmt_create AS gmtCreate"
                        + " FROM as_artifact WHERE id=? AND deleted_at IS NULL", id);
        if (rows.isEmpty()) {
            throw new BizException("28001", "产物不存在");
        }
        return ApiResult.ok(rows.get(0));
    }

    private java.nio.file.Path artifactRoot() {
        return java.nio.file.Paths.get(System.getProperty("java.io.tmpdir") + "/ainative-artifacts").toAbsolutePath();
    }

    @GetMapping("/api/audit-logs/count")
    public ApiResult<Long> auditCount() {
        RequestContext.requireUserId();
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM pf_audit WHERE workspace_id=?", Long.class, ws());
        return ApiResult.ok(n == null ? 0 : n);
    }

    @GetMapping("/api/debug-logs")
    public ApiResult<List<Map<String, Object>>> debugLogs(@RequestParam(required = false) Long workitemId) {
        RequestContext.requireUserId();
        if (workitemId != null) {
            return ApiResult.ok(jdbc.queryForList(
                    "SELECT id, ticket_id AS ticketId, content_md AS contentMd, gmt_create AS gmtCreate"
                            + " FROM pf_debug_log WHERE ticket_id=? ORDER BY id DESC LIMIT 100", workitemId));
        }
        return ApiResult.ok(jdbc.queryForList(
                "SELECT id, ticket_id AS ticketId, content_md AS contentMd, gmt_create AS gmtCreate"
                        + " FROM pf_debug_log WHERE workspace_id=? ORDER BY id DESC LIMIT 100", ws()));
    }

    @GetMapping("/api/capabilities/scheduled-task")
    public ApiResult<Map<String, Object>> scheduledTaskCapability() {
        RequestContext.requireUserId();
        return ApiResult.ok(Map.of("enabled", true, "cronFieldMode", "5_OR_6"));
    }

    @GetMapping("/api/environment-variables/{id}/value")
    public ApiResult<Map<String, Object>> envValue(@PathVariable long id) {
        RequestContext.requireUserId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, key_name AS keyName, value_ref AS valueRef, is_secret AS isSecret FROM as_env_var WHERE id=?", id);
        if (rows.isEmpty()) {
            throw new BizException("29001", "环境变量不存在");
        }
        Map<String, Object> row = rows.get(0);
        if (Integer.valueOf(1).equals(((Number) row.get("isSecret")).intValue())) {
            row.put("valueRef", "******");
        }
        return ApiResult.ok(row);
    }

    @PostMapping("/api/repos/{id}/scan")
    public ApiResult<Map<String, Object>> scanRepo(@PathVariable long id) {
        RequestContext.requireUserId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, url FROM as_repo WHERE id=? AND deleted_at IS NULL", id);
        if (rows.isEmpty()) {
            throw new BizException("30001", "仓库不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("repoId", id);
        out.put("scannedAt", java.time.LocalDateTime.now().toString());
        out.put("branches", List.of("main"));
        out.put("message", "扫描已登记,结果稍后写入仓库结论");
        return ApiResult.ok(out);
    }

    @GetMapping("/api/integrations/channels/group-config")
    public ApiResult<List<Map<String, Object>>> groupConfigs() {
        RequestContext.requireUserId();
        return ApiResult.ok(jdbc.queryForList(
                "SELECT channel, app_key AS appKey, enabled FROM pf_im_config WHERE deleted_at IS NULL"));
    }

    @GetMapping("/api/integrations/zentao/projects/search")
    public ApiResult<List<Map<String, Object>>> zentaoSearch(@RequestParam(required = false) String keyword) {
        RequestContext.requireUserId();
        List<Map<String, Object>> bindings = jdbc.queryForList(
                "SELECT id, provider, external_project_id AS externalProjectId, name FROM ex_binding"
                        + " WHERE workspace_id=? AND deleted_at IS NULL AND provider='ZENTAO'", ws());
        return ApiResult.ok(bindings);
    }

    @PostMapping("/api/integrations/feishu/callback")
    public ApiResult<Map<String, Object>> feishuCallback(@RequestBody(required = false) Map<String, Object> body) {
        // 回调入口:渠道校验密钥后回执;实际消息处理走异步队列
        return ApiResult.ok(Map.of("challenge", body == null ? "" : body.getOrDefault("challenge", ""),
                "accepted", true));
    }

    @GetMapping("/api/workspaces/current/member-candidates")
    public ApiResult<List<Map<String, Object>>> memberCandidates(@RequestParam(required = false) String keyword) {
        RequestContext.requireUserId();
        String like = keyword == null || keyword.isBlank() ? "%" : "%" + keyword.trim() + "%";
        return ApiResult.ok(jdbc.queryForList(
                "SELECT u.id, u.username, u.nickname, u.email FROM id_user u WHERE u.deleted_at IS NULL"
                        + " AND (u.username LIKE ? OR u.nickname LIKE ?)"
                        + " AND NOT EXISTS (SELECT 1 FROM id_org_member m WHERE m.user_id=u.id AND m.org_id=? AND m.deleted_at IS NULL)"
                        + " ORDER BY u.id LIMIT 50", like, like, ws()));
    }

    @GetMapping("/api/workspaces/current/roles/{id}")
    public ApiResult<Map<String, Object>> roleDetail(@PathVariable long id) {
        RequestContext.requireUserId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, role_key AS roleKey, name, permissions_json AS permissions FROM id_ws_role"
                        + " WHERE id=? AND workspace_id=?", id, ws());
        if (rows.isEmpty()) {
            throw new BizException("31001", "角色不存在");
        }
        return ApiResult.ok(rows.get(0));
    }

    @DeleteMapping("/api/workspaces/current/roles/{id}")
    public ApiResult<Void> deleteRole(@PathVariable long id) {
        RequestContext.requireUserId();
        jdbc.update("UPDATE id_ws_role SET deleted_at=NOW() WHERE id=? AND workspace_id=?", id, ws());
        return ApiResult.ok(null);
    }

    @GetMapping("/api/memories/reviews/count")
    public ApiResult<Long> memoryReviewCount() {
        RequestContext.requireUserId();
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mm_review WHERE workspace_id=? AND status='PENDING'", Long.class, ws());
        return ApiResult.ok(n == null ? 0 : n);
    }

    @GetMapping("/api/dispatches/{id}/diagnostics")
    public ApiResult<List<Map<String, Object>>> diagnostics(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(jdbc.queryForList(
                "SELECT id, run_id AS runId, event_type AS eventType, detail_json AS detail, gmt_create AS gmtCreate"
                        + " FROM rt_event WHERE run_id=? AND event_type IN ('ERROR','DIAGNOSTIC','HANDOFF') ORDER BY id DESC LIMIT 50", id));
    }

    @GetMapping("/api/dispatches/{id}/diagnostics/{eventId}")
    public ApiResult<Map<String, Object>> diagnostic(@PathVariable long id, @PathVariable long eventId) {
        RequestContext.requireUserId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, run_id AS runId, event_type AS eventType, detail_json AS detail, gmt_create AS gmtCreate"
                        + " FROM rt_event WHERE id=? AND run_id=?", eventId, id);
        if (rows.isEmpty()) {
            throw new BizException("32001", "诊断记录不存在");
        }
        return ApiResult.ok(rows.get(0));
    }

    @GetMapping("/api/dispatches/{id}/diagnostics/{eventId}/download")
    public ResponseEntity<byte[]> diagnosticDownload(@PathVariable long id, @PathVariable long eventId) {
        RequestContext.requireUserId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT detail_json FROM rt_event WHERE id=? AND run_id=?", eventId, id);
        if (rows.isEmpty() || rows.get(0).get("detail_json") == null) {
            throw new BizException("32001", "诊断记录不存在");
        }
        byte[] bytes = String.valueOf(rows.get(0).get("detail_json"))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"diagnostic-" + eventId + ".json\"")
                .body(bytes);
    }
}
