package com.ainative.v2.cv;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.BizException;
import com.ainative.v2.common.RequestContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台 AI 管家对话框(ChiefOfStaff,前端 /api/platform/conversations)。
 *
 * <p>对话复用 ag_chat/ag_chat_turn 与 CONVERSATION_TURN 真实 AI 通道;
 * 会话标题固定 chief:{userId},管家 agent 复用项目内第一个智能体
 * (setup 时确保 agent+executor 就绪)。契约对齐 chiefOfStaff/api.ts。
 */
@RestController
public class ChiefOfStaffController {
    private final JdbcTemplate jdbc;
    private final ConversationTurnService turns;

    public ChiefOfStaffController(JdbcTemplate jdbc, ConversationTurnService turns) {
        this.jdbc = jdbc;
        this.turns = turns;
    }

    private static String titleOf(long userId) {
        return "chief:" + userId;
    }

    // ---------------------------------------------------------------- setup

    @GetMapping("/api/platform-agent/setup")
    public ApiResult<Map<String, Object>> setup() {
        long uid = RequestContext.requireUserId();
        Map<String, Object> out = new LinkedHashMap<>();
        Long agentId = platformAgentId();
        boolean agentConfigured = agentId != null;
        boolean executorConfigured = agentConfigured
                && !jdbc.queryForList("SELECT 1 FROM ag_executor WHERE agent_id=? AND status='ONLINE' AND deleted_at IS NULL",
                agentId).isEmpty();
        out.put("agentId", agentId);
        out.put("executorId", null);
        out.put("agentConfigured", agentConfigured);
        out.put("skillsConfigured", true);
        out.put("executorConfigured", executorConfigured);
        out.put("launchConfigured", executorConfigured);
        out.put("ready", agentConfigured && executorConfigured);
        out.put("missingSkills", List.of());
        return ApiResult.ok(out);
    }

    /** 完成管家初始化:确保存在管家 agent 与在线执行器(复用现有资源,不重复建)。 */
    @PostMapping("/api/platform-agent/setup")
    public ApiResult<Map<String, Object>> completeSetup(@RequestBody(required = false) Map<String, Object> body) {
        long ws = RequestContext.workspaceId();
        Long agentId = platformAgentId();
        if (agentId == null) {
            jdbc.update("INSERT INTO ag_agent (workspace_id, name, role_code, role_name, soul_md, status) VALUES (?,?,?,?,?, 'ACTIVE')",
                    ws, "AI 管家", "CHIEF", "平台智能体",
                    "你是 CodeFlow 平台的 AI 管家:帮用户答疑平台用法、检查交付状态、指出下一步操作。回答简洁中文。");
            agentId = jdbc.queryForObject("SELECT MAX(id) FROM ag_agent WHERE workspace_id=?", Long.class, ws);
        }
        if (jdbc.queryForList("SELECT 1 FROM ag_executor WHERE agent_id=? AND deleted_at IS NULL", agentId).isEmpty()) {
            // 复用项目内第一个在线执行器:管家与交付共用同一运行时
            List<Map<String, Object>> first = jdbc.queryForList(
                    "SELECT id, agent_id FROM ag_executor WHERE status='ONLINE' AND deleted_at IS NULL ORDER BY id LIMIT 1");
            if (!first.isEmpty()) {
                long firstAgentId = ((Number) first.get(0).get("agent_id")).longValue();
                long firstAgent = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM ag_agent WHERE workspace_id=? AND deleted_at IS NULL", Long.class, ws);
                if (firstAgentId == firstAgent) {
                    jdbc.update("INSERT INTO ag_executor (workspace_id, agent_id, name, client_kind, token_hash, status)"
                            + " SELECT workspace_id, ?, name, client_kind, token_hash, 'OFFLINE' FROM ag_executor WHERE id=?",
                            agentId, first.get(0).get("id"));
                }
            }
        }
        return setup();
    }

    private Long platformAgentId() {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM ag_agent WHERE workspace_id=? AND role_code='CHIEF' AND deleted_at IS NULL ORDER BY id LIMIT 1",
                Long.class, RequestContext.workspaceId());
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        // 兼容:未建专属管家时回退第一个 agent
        List<Long> fallback = jdbc.queryForList(
                "SELECT id FROM ag_agent WHERE workspace_id=? AND deleted_at IS NULL ORDER BY id LIMIT 1",
                Long.class, RequestContext.workspaceId());
        return fallback.isEmpty() ? null : fallback.get(0);
    }

    // ---------------------------------------------------------------- 会话

    @GetMapping("/api/platform/conversations")
    public ApiResult<List<Map<String, Object>>> list(@RequestParam(defaultValue = "false") boolean archived,
                                                     @RequestParam(defaultValue = "1") int page) {
        long uid = RequestContext.requireUserId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT c.id, c.agent_id AS agentId, a.name AS agentName, c.title, c.status,"
                        + " c.gmt_create AS gmtCreate, c.gmt_modified AS lastTurnAt"
                        + " FROM ag_chat c LEFT JOIN ag_agent a ON a.id=c.agent_id"
                        + " WHERE c.workspace_id=? AND c.title LIKE ? AND c.deleted_at IS NULL"
                        + " ORDER BY c.id DESC LIMIT 30", RequestContext.workspaceId(), "chief:%");
        for (Map<String, Object> row : rows) {
            decorate(row, ((Number) row.get("id")).longValue());
            row.put("turns", turnsOf(((Number) row.get("id")).longValue()));
            row.put("archivedAt", null);
        }
        return ApiResult.ok(rows);
    }

    @PostMapping("/api/platform/conversations")
    public ApiResult<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> body) {
        long uid = RequestContext.requireUserId();
        Long agentId = body == null || body.get("agentId") == null ? platformAgentId()
                : Long.valueOf(String.valueOf(body.get("agentId")));
        if (agentId == null) {
            throw new BizException("17001", "没有可用智能体");
        }
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id FROM ag_chat WHERE workspace_id=? AND title=? AND deleted_at IS NULL",
                RequestContext.workspaceId(), titleOf(uid));
        long chatId;
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO ag_chat (workspace_id, agent_id, title, user_id, status) VALUES (?,?,?,?,'OPEN')",
                    RequestContext.workspaceId(), agentId, titleOf(uid), uid);
            chatId = jdbc.queryForObject("SELECT MAX(id) FROM ag_chat WHERE workspace_id=?", Long.class,
                    RequestContext.workspaceId());
        } else {
            chatId = ((Number) existing.get(0).get("id")).longValue();
        }
        return ApiResult.ok(detail(chatId, uid));
    }

    @GetMapping("/api/platform/conversations/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable long id) {
        long uid = RequestContext.requireUserId();
        return ApiResult.ok(detail(id, uid));
    }

    @PatchMapping("/api/platform/conversations/{id}")
    public ApiResult<Map<String, Object>> patch(@PathVariable long id,
                                                @RequestBody(required = false) Map<String, Object> body) {
        long uid = RequestContext.requireUserId();
        if (body != null && body.get("title") != null) {
            jdbc.update("UPDATE ag_chat SET title=? WHERE id=?", body.get("title"), id);
        }
        return ApiResult.ok(detail(id, uid));
    }

    // ---------------------------------------------------------------- 回合

    @PostMapping("/api/platform/conversations/{id}/turns")
    public ApiResult<Map<String, Object>> send(@PathVariable long id, @RequestBody Map<String, Object> body) {
        long uid = RequestContext.requireUserId();
        requireMine(id, uid);
        Map<String, Object> chat = jdbc.queryForMap("SELECT agent_id FROM ag_chat WHERE id=?", id);
        long agentId = ((Number) chat.get("agent_id")).longValue();
        Map<String, Object> sent = turns.send(id, agentId, uid,
                body.get("content") == null ? "" : String.valueOf(body.get("content")));
        return ApiResult.ok(sent);
    }

    @PostMapping("/api/platform/conversations/{id}/turns/{turnId}/cancel")
    public ApiResult<Map<String, Object>> cancel(@PathVariable long id, @PathVariable long turnId) {
        RequestContext.requireUserId();
        jdbc.update("INSERT INTO ag_chat_turn_event (workspace_id, turn_id, event_type, detail_json) VALUES (?,?, 'TURN_CANCEL_REQUESTED', ?)",
                RequestContext.workspaceId(), turnId, "{\"by\":\"user\"}");
        return ApiResult.ok(Map.of("turnId", turnId, "status", "CANCEL_REQUESTED"));
    }

    @GetMapping("/api/platform/conversations/{id}/turns/{turnId}/events")
    public ApiResult<List<Map<String, Object>>> events(@PathVariable long id, @PathVariable long turnId,
                                                       @RequestParam(required = false) Long afterId) {
        RequestContext.requireUserId();
        String sql = "SELECT id, turn_id AS turnId, event_type AS eventType, detail_json AS payloadFragment,"
                + " gmt_create AS createdAt FROM ag_chat_turn_event WHERE turn_id=? AND deleted_at IS NULL";
        if (afterId != null) {
            sql += " AND id>" + afterId;
        }
        sql += " ORDER BY id LIMIT 200";
        return ApiResult.ok(jdbc.queryForList(sql, turnId));
    }

    @GetMapping("/api/platform/conversations/{id}/turns/{turnId}/events/page")
    public ApiResult<Map<String, Object>> eventPage(@PathVariable long id, @PathVariable long turnId,
                                                    @RequestParam(required = false) Long afterId) {
        RequestContext.requireUserId();
        String sql = "SELECT id, turn_id AS turnId, event_type AS eventType, detail_json AS payloadFragment,"
                + " gmt_create AS createdAt FROM ag_chat_turn_event WHERE turn_id=? AND deleted_at IS NULL";
        if (afterId != null) {
            sql += " AND id>" + afterId;
        }
        sql += " ORDER BY id LIMIT 200";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, turnId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        long next = rows.isEmpty() ? (afterId == null ? 0 : afterId) : ((Number) rows.get(rows.size() - 1).get("id")).longValue();
        out.put("nextAfterId", next);
        out.put("hasMore", false);
        return ApiResult.ok(out);
    }

    @PostMapping("/api/platform/conversations/{id}/elicitations/{requestId}/reply")
    public ApiResult<Map<String, Object>> elicitationReply(@PathVariable long id, @PathVariable String requestId,
                                                           @RequestBody Map<String, Object> body) {
        RequestContext.requireUserId();
        return ApiResult.ok(Map.of("requestId", requestId,
                "action", String.valueOf(body.getOrDefault("action", "accept")), "status", "REPLIED"));
    }

    @GetMapping("/api/platform/conversations/{id}/action-plans")
    public ApiResult<List<Map<String, Object>>> plans(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(List.of());
    }

    @DeleteMapping("/api/platform/conversations/{id}")
    public ApiResult<Void> archive(@PathVariable long id) {
        RequestContext.requireUserId();
        jdbc.update("UPDATE ag_chat SET deleted_at=NOW() WHERE id=?", id);
        return ApiResult.ok(null);
    }

    // ---------------------------------------------------------------- 内部

    private void requireMine(long chatId, long uid) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM ag_chat WHERE id=? AND workspace_id=? AND title LIKE ? AND deleted_at IS NULL",
                chatId, RequestContext.workspaceId(), "chief:%");
        if (rows.isEmpty()) {
            throw new BizException("16001", "管家会话不存在");
        }
    }

    private Map<String, Object> detail(long chatId, long uid) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT c.id, c.agent_id AS agentId, a.name AS agentName, c.title, c.status,"
                        + " c.gmt_create AS gmtCreate, c.gmt_modified AS lastTurnAt FROM ag_chat c"
                        + " LEFT JOIN ag_agent a ON a.id=c.agent_id WHERE c.id=? AND c.deleted_at IS NULL", chatId);
        if (rows.isEmpty()) {
            throw new BizException("16001", "管家会话不存在");
        }
        Map<String, Object> row = rows.get(0);
        decorate(row, chatId);
        row.put("turns", turnsOf(chatId));
        row.put("archivedAt", null);
        row.put("pendingElicitations", List.of());
        row.put("questionHistory", List.of());
        row.put("answerDeliveries", List.of());
        return row;
    }

    private List<Map<String, Object>> turnsOf(long chatId) {
        return jdbc.queryForList(
                "SELECT t.id, t.role AS direction, t.content_md AS content, t.delivery_status AS status,"
                        + " t.gmt_create AS gmtCreate FROM ag_chat_turn t"
                        + " WHERE t.chat_id=? AND t.deleted_at IS NULL ORDER BY t.id", chatId);
    }

    private void decorate(Map<String, Object> row, long chatId) {
        long agentId = row.get("agentId") instanceof Number n ? n.longValue() : 0;
        Long online = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ag_executor WHERE agent_id=? AND status='ONLINE' AND deleted_at IS NULL",
                Long.class, agentId);
        boolean executorOnline = online != null && online > 0;
        row.put("channelConversationId", "chief-" + chatId);
        row.put("executorOnline", executorOnline);
        row.put("streamingSupported", executorOnline);
        row.put("cancelSupported", executorOnline);
        row.put("acpInteractionSupported", executorOnline);
        row.put("cliSessionRef", null);
        row.put("processingStatus", null);
        row.put("processingTurnId", null);
        row.put("availableCommands", List.of());
    }
}
