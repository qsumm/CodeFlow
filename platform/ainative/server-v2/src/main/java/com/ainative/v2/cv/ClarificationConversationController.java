package com.ainative.v2.cv;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.BizException;
import com.ainative.v2.common.RequestContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
 * 工单澄清会话(前端澄清面板的数据源)。
 *
 * <p>实现策略:澄清会话复用 ag_chat/ag_chat_turn,以标题前缀
 * <code>clarification:{ticketId}:</code> 关联到工单 —— 不引入第三套会话表,
 * 澄清对话与智能体对话共用同一条真实 AI 通道(CONVERSATION_TURN)。
 * 附件落 as_artifact(ticket_id 关联),元数据行带 kind=CLARIFICATION。
 */
@RestController
public class ClarificationConversationController {
    private static final String TITLE_PREFIX = "clarification:";

    public record CreateRequest(Long agentId) {}
    public record TurnRequest(String contentMd, String clientMessageId, List<Long> attachmentIds) {}
    public record AttachRequest(Long artifactId, String name, Long sizeBytes) {}
    public record ElicitationReplyRequest(String action, String content) {}

    private final JdbcTemplate jdbc;
    private final ConversationTurnService turns;

    public ClarificationConversationController(JdbcTemplate jdbc, ConversationTurnService turns) {
        this.jdbc = jdbc;
        this.turns = turns;
    }

    private long ws() {
        return RequestContext.workspaceId();
    }

    private static String titleOf(long ticketId, long agentId) {
        return TITLE_PREFIX + ticketId + ":" + agentId;
    }

    // ---------------------------------------------------------------- 会话

    @GetMapping("/api/workitems/{workitemId}/clarification-conversations")
    public ApiResult<List<Map<String, Object>>> list(@PathVariable long workitemId,
                                                     @RequestParam(required = false) Long agentId) {
        RequestContext.requireUserId();
        StringBuilder sql = new StringBuilder(
                "SELECT c.id, c.agent_id AS agentId, a.name AS agentName, c.title, c.status,"
                        + " c.gmt_create AS gmtCreate, c.gmt_modified AS lastTurnAt FROM ag_chat c"
                        + " LEFT JOIN ag_agent a ON a.id=c.agent_id"
                        + " WHERE c.workspace_id=? AND c.deleted_at IS NULL AND c.title LIKE ?");
        List<Object> args = new ArrayList<>(List.of(ws(), TITLE_PREFIX + workitemId + ":%"));
        if (agentId != null) {
            sql.append(" AND c.agent_id=?");
            args.add(agentId);
        }
        sql.append(" ORDER BY c.id DESC LIMIT 50");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        for (Map<String, Object> row : rows) {
            decorate(row, workitemId, ((Number) row.get("id")).longValue());
        }
        return ApiResult.ok(rows);
    }

    /** 取或建:同一 (工单, 智能体) 只有一个澄清会话。 */
    @PostMapping("/api/workitems/{workitemId}/clarification-conversations")
    public ApiResult<Map<String, Object>> create(@PathVariable long workitemId, @RequestBody CreateRequest req) {
        RequestContext.requireUserId();
        long agentId = req.agentId() == null ? defaultAgentId() : req.agentId();
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id FROM ag_chat WHERE workspace_id=? AND title=? AND deleted_at IS NULL",
                ws(), titleOf(workitemId, agentId));
        long chatId;
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO ag_chat (workspace_id, agent_id, title, user_id, status) VALUES (?,?,?,?,'OPEN')",
                    ws(), agentId, titleOf(workitemId, agentId), RequestContext.requireUserId());
            chatId = jdbc.queryForObject("SELECT MAX(id) FROM ag_chat WHERE workspace_id=?", Long.class, ws());
        } else {
            chatId = ((Number) existing.get(0).get("id")).longValue();
        }
        Map<String, Object> out = detailMap(workitemId, chatId);
        return ApiResult.ok(out);
    }

    @GetMapping("/api/workitems/{workitemId}/clarification-conversations/{conversationId}")
    public ApiResult<Map<String, Object>> detail(@PathVariable long workitemId, @PathVariable long conversationId) {
        RequestContext.requireUserId();
        return ApiResult.ok(detailMap(workitemId, conversationId));
    }

    // ---------------------------------------------------------------- 回合

    @PostMapping("/api/workitems/{workitemId}/clarification-conversations/{conversationId}/turns")
    public ApiResult<Map<String, Object>> submitTurn(@PathVariable long workitemId,
                                                     @PathVariable long conversationId,
                                                     @RequestBody TurnRequest req) {
        RequestContext.requireUserId();
        requireConversation(workitemId, conversationId);
        Map<String, Object> chat = jdbc.queryForMap("SELECT agent_id FROM ag_chat WHERE id=?", conversationId);
        long agentId = ((Number) chat.get("agent_id")).longValue();
        String content = req.contentMd() == null ? "" : req.contentMd();
        if (req.attachmentIds() != null && !req.attachmentIds().isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (Long artifactId : req.attachmentIds()) {
                List<String> fileNames = jdbc.queryForList(
                        "SELECT name FROM as_artifact WHERE id=? AND ticket_id=?", String.class, artifactId, workitemId);
                if (!fileNames.isEmpty()) {
                    names.append(names.length() == 0 ? "" : ", ").append(fileNames.get(0));
                }
            }
            if (names.length() > 0) {
                content += "\n\n[附件] " + names;
            }
        }
        Map<String, Object> sent = turns.send(conversationId, agentId, RequestContext.requireUserId(), content);
        Map<String, Object> out = new LinkedHashMap<>(sent);
        out.put("clientMessageId", req.clientMessageId());
        return ApiResult.ok(out);
    }

    @PostMapping("/api/workitems/{workitemId}/clarification-conversations/{conversationId}/turns/{turnId}/cancel")
    public ApiResult<Map<String, Object>> cancelTurn(@PathVariable long workitemId, @PathVariable long conversationId,
                                                     @PathVariable long turnId) {
        RequestContext.requireUserId();
        requireConversation(workitemId, conversationId);
        jdbc.update("INSERT INTO ag_chat_turn_event (workspace_id, turn_id, event_type, detail_json) VALUES (?,?, 'TURN_CANCEL_REQUESTED', ?)",
                ws(), turnId, "{\"by\":\"user\"}");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("turnId", turnId);
        out.put("status", "CANCEL_REQUESTED");
        return ApiResult.ok(out);
    }

    @GetMapping("/api/workitems/{workitemId}/clarification-conversations/{conversationId}/turns/{turnId}/events")
    public ApiResult<List<Map<String, Object>>> turnEvents(@PathVariable long workitemId,
                                                           @PathVariable long conversationId,
                                                           @PathVariable long turnId,
                                                           @RequestParam(required = false) Long afterId) {
        RequestContext.requireUserId();
        requireConversation(workitemId, conversationId);
        String sql = "SELECT id, turn_id AS turnId, event_type AS eventType, detail_json AS payloadFragment,"
                + " gmt_create AS createdAt FROM ag_chat_turn_event WHERE turn_id=? AND deleted_at IS NULL";
        if (afterId != null) {
            sql += " AND id>" + afterId;
        }
        sql += " ORDER BY id LIMIT 200";
        return ApiResult.ok(jdbc.queryForList(sql, turnId));
    }

    @GetMapping("/api/workitems/{workitemId}/clarification-conversations/{conversationId}/events")
    public ApiResult<List<Map<String, Object>>> events(@PathVariable long workitemId,
                                                       @PathVariable long conversationId,
                                                       @RequestParam(required = false) Long afterId) {
        RequestContext.requireUserId();
        requireConversation(workitemId, conversationId);
        String sql = "SELECT e.id, e.turn_id AS turnId, e.event_type AS eventType,"
                + " e.detail_json AS payloadFragment, e.gmt_create AS createdAt FROM ag_chat_turn_event e"
                + " JOIN ag_chat_turn t ON t.id=e.turn_id AND t.chat_id=?"
                + " WHERE e.deleted_at IS NULL";
        if (afterId != null) {
            sql += " AND e.id>" + afterId;
        }
        sql += " ORDER BY e.id LIMIT 200";
        return ApiResult.ok(jdbc.queryForList(sql, conversationId));
    }

    // ---------------------------------------------------------------- 附件

    @PostMapping("/api/workitems/{workitemId}/clarification-conversations/{conversationId}/attachments")
    public ApiResult<Map<String, Object>> attach(@PathVariable long workitemId, @PathVariable long conversationId,
                                                 @RequestBody AttachRequest req) {
        RequestContext.requireUserId();
        requireConversation(workitemId, conversationId);
        if (req.artifactId() == null) {
            throw new BizException("10001", "附件 ID 不能为空");
        }
        jdbc.update("INSERT INTO cv_clarification_attach (workspace_id, clarification_id, artifact_id) VALUES (?,?,?)",
                ws(), conversationId, req.artifactId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", jdbc.queryForObject("SELECT MAX(id) FROM cv_clarification_attach WHERE clarification_id=?",
                Long.class, conversationId));
        out.put("artifactId", req.artifactId());
        out.put("name", req.name());
        return ApiResult.ok(out);
    }

    @GetMapping("/api/workitems/{workitemId}/clarification-conversations/{conversationId}/attachments")
    public ApiResult<List<Map<String, Object>>> attachments(@PathVariable long workitemId,
                                                            @PathVariable long conversationId) {
        RequestContext.requireUserId();
        requireConversation(workitemId, conversationId);
        return ApiResult.ok(jdbc.queryForList(
                "SELECT a.id, a.artifact_id AS artifactId, r.name, r.size_bytes AS sizeBytes,"
                        + " r.gmt_create AS createdAt FROM cv_clarification_attach a"
                        + " JOIN as_artifact r ON r.id=a.artifact_id"
                        + " WHERE a.clarification_id=? AND a.deleted_at IS NULL ORDER BY a.id", conversationId));
    }

    @DeleteMapping("/api/workitems/{workitemId}/clarification-conversations/{conversationId}/attachments/{attachmentId}")
    public ApiResult<Void> detach(@PathVariable long workitemId, @PathVariable long conversationId,
                                  @PathVariable long attachmentId) {
        RequestContext.requireUserId();
        jdbc.update("UPDATE cv_clarification_attach SET deleted_at=NOW() WHERE id=? AND clarification_id=?",
                attachmentId, conversationId);
        return ApiResult.ok(null);
    }

    // ---------------------------------------------------------------- 交互卡片

    @PostMapping("/api/workitems/{workitemId}/clarification-conversations/{conversationId}/elicitations/{requestId}/reply")
    public ApiResult<Map<String, Object>> elicitationReply(@PathVariable long workitemId,
                                                           @PathVariable long conversationId,
                                                           @PathVariable String requestId,
                                                           @RequestBody ElicitationReplyRequest req) {
        RequestContext.requireUserId();
        requireConversation(workitemId, conversationId);
        String detail;
        try {
            detail = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                    "requestId", requestId, "action", req.action(), "content", req.content() == null ? "" : req.content()));
        } catch (Exception e) {
            detail = "{}";
        }
        jdbc.update("INSERT INTO ag_chat_turn_event (workspace_id, turn_id, event_type, detail_json) VALUES (?,?, 'ACP_INTERACTION_REPLY', ?)",
                ws(), 0, detail);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", requestId);
        out.put("action", req.action());
        out.put("status", "REPLIED");
        return ApiResult.ok(out);
    }

    /** 斜杠命令探针:执行器在线时由下次回合带回;此处只登记探针意图。 */
    @PostMapping("/api/workitems/{workitemId}/clarification-conversations/{conversationId}/commands/refresh")
    public ApiResult<Map<String, Object>> refreshCommands(@PathVariable long workitemId,
                                                          @PathVariable long conversationId) {
        RequestContext.requireUserId();
        requireConversation(workitemId, conversationId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", true);
        out.put("availableCommands", List.of());
        return ApiResult.ok(out);
    }

    // ---------------------------------------------------------------- 内部

    private void requireConversation(long workitemId, long conversationId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM ag_chat WHERE id=? AND workspace_id=? AND title LIKE ? AND deleted_at IS NULL",
                conversationId, ws(), TITLE_PREFIX + workitemId + ":%");
        if (rows.isEmpty()) {
            throw new BizException("16001", "澄清会话不存在");
        }
    }

    private Map<String, Object> detailMap(long workitemId, long chatId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT c.id, c.agent_id AS agentId, a.name AS agentName, c.title, c.status,"
                        + " c.gmt_create AS gmtCreate, c.gmt_modified AS lastTurnAt FROM ag_chat c"
                        + " LEFT JOIN ag_agent a ON a.id=c.agent_id WHERE c.id=? AND c.deleted_at IS NULL", chatId);
        if (rows.isEmpty()) {
            throw new BizException("16001", "澄清会话不存在");
        }
        Map<String, Object> row = rows.get(0);
        decorate(row, workitemId, chatId);
        List<Map<String, Object>> turns = jdbc.queryForList(
                "SELECT t.id, t.role AS direction, t.content_md AS content, t.delivery_status AS status,"
                        + " t.gmt_create AS gmtCreate FROM ag_chat_turn t"
                        + " WHERE t.chat_id=? AND t.deleted_at IS NULL ORDER BY t.id", chatId);
        row.put("turns", turns);
        // 澄清面板没有本地会话缓存,直接把最近的回合事件作为 pending 交互卡片来源
        List<Map<String, Object>> elicitations = jdbc.queryForList(
                "SELECT e.id, e.turn_id AS turnId, e.detail_json AS detail, e.gmt_create AS createdAt"
                        + " FROM ag_chat_turn_event e JOIN ag_chat_turn t ON t.id=e.turn_id AND t.chat_id=?"
                        + " WHERE e.event_type='ACP_INTERACTION_REPLY' AND e.deleted_at IS NULL"
                        + " ORDER BY e.id DESC LIMIT 10", chatId);
        row.put("pendingElicitations", List.of());
        row.put("questionHistory", elicitations);
        row.put("answerDeliveries", List.of());
        return row;
    }

    /** 前端契约字段:在线状态/能力声明由执行器真实连接推导。 */
    private void decorate(Map<String, Object> row, long workitemId, long chatId) {
        long agentId = row.get("agentId") instanceof Number n ? n.longValue() : 0L;
        Long online = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ag_executor WHERE agent_id=? AND status='ONLINE' AND deleted_at IS NULL",
                Long.class, agentId);
        boolean executorOnline = online != null && online > 0;
        row.put("channelConversationId", "clarification-" + workitemId + "-" + chatId);
        row.put("executorOnline", executorOnline);
        row.put("streamingSupported", executorOnline);
        row.put("cancelSupported", executorOnline);
        row.put("acpInteractionSupported", executorOnline);
        row.put("cliSessionRef", null);
        row.put("processingStatus", null);
        row.put("processingTurnId", null);
    }

    private long defaultAgentId() {
        List<Map<String, Object>> agents = jdbc.queryForList(
                "SELECT id FROM ag_agent WHERE workspace_id=? AND deleted_at IS NULL ORDER BY id LIMIT 1", ws());
        if (agents.isEmpty()) {
            throw new BizException("17001", "工作区还没有智能体,无法发起澄清");
        }
        return ((Number) agents.get(0).get("id")).longValue();
    }
}
