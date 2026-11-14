package com.ainative.v2.cv;

import com.ainative.v2.common.BizException;
import com.ainative.v2.daemon.ExecutorHub;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 澄清会话执行(与运行器 CONVERSATION_TURN 协议对齐):
 * 平台把用户输入下发为 CONVERSATION_TURN,运行器执行 claude 后回 CONVERSATION_TURN_ACK,
 * 平台把回复落 ag_chat_turn 并推进会话状态。
 */
@Service
public class ConversationTurnService {
    private static final Logger log = LoggerFactory.getLogger(ConversationTurnService.class);

    private final JdbcTemplate jdbc;
    private final ExecutorHub hub;
    private final ObjectMapper mapper = new ObjectMapper();
    /** 回合幂等留底:chatId+turnId → 已落库的回复(turnId 在会话内唯一,跨会话会重复) */
    private final Map<String, Map<String, Object>> done = new ConcurrentHashMap<>();

    public ConversationTurnService(JdbcTemplate jdbc, ExecutorHub hub) {
        this.jdbc = jdbc;
        this.hub = hub;
    }

    /** 用户发送消息:落库 USER 回合 → 下发给在线执行器;无在线执行器时排队等待重放。 */
    @Transactional
    public Map<String, Object> send(long chatId, long agentId, long userId, String contentMd) {
        if (contentMd == null || contentMd.isBlank()) {
            throw new BizException("10001", "消息不能为空");
        }
        Integer seq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq),0)+1 FROM ag_chat_turn WHERE chat_id=?", Integer.class, chatId);
        int nextSeq = seq == null ? 1 : seq;
        jdbc.update("INSERT INTO ag_chat_turn (workspace_id, chat_id, seq, role, content_md, delivery_status) VALUES (?,?,?,'USER',?, 'PENDING')",
                workspaceId(), chatId, nextSeq, contentMd);
        jdbc.update("INSERT INTO ag_chat_turn_event (workspace_id, turn_id, event_type, detail_json) VALUES (?,?,'USER_MESSAGE',?)",
                workspaceId(), nextSeq, null);

        // 找到该智能体的在线执行器并下发
        // DB 里的 ONLINE 可能已是僵尸(运行器进程已退出),逐个用 hub 校验真实连接
        Long executorId = onlineExecutorOf(agentId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conversationId", chatId);
        out.put("turnId", nextSeq);
        out.put("seq", nextSeq);
        if (executorId == null) {
            // 运行器离线不等于消息丢失:回合已落库为 PENDING,执行器上线后由
            // replayPending 重投;接口返回成功,前端展示"等待运行器"而不是报错。
            out.put("status", "QUEUED");
            log.info("conversation turn queued (no online executor) chatId={} seq={}", chatId, nextSeq);
            return out;
        }
        Map<String, Object> frame = conversationFrame(chatId, nextSeq, agentId, contentMd);
        boolean sent = hub.send(executorId, frame);
        if (!sent) {
            out.put("status", "QUEUED");
            log.info("conversation turn queued (send failed) chatId={} seq={}", chatId, nextSeq);
            return out;
        }
        markDelivery(chatId, nextSeq, "SENT");
        out.put("status", "SENT");
        log.info("conversation turn sent chatId={} turnId={} executorId={}", chatId, nextSeq, executorId);
        return out;
    }

    /**
     * 执行器上线后重放积压回合(仅该智能体、最近 20 条 PENDING)。
     * 返回成功投递条数。失败不影响上线流程,下次上线继续重试。
     */
    public int replayPending(long executorId, long agentId) {
        List<Map<String, Object>> pending;
        try {
            pending = jdbc.queryForList(
                    "SELECT t.chat_id, t.seq, t.content_md FROM ag_chat_turn t"
                            + " JOIN ag_chat c ON c.id=t.chat_id AND c.agent_id=?"
                            + " WHERE t.role='USER' AND t.delivery_status='PENDING' AND t.deleted_at IS NULL"
                            + " ORDER BY t.id LIMIT 20", agentId);
        } catch (Exception e) {
            log.warn("conversation replay query failed executorId={} error={}", executorId, e.getMessage());
            return 0;
        }
        int sent = 0;
        for (Map<String, Object> row : pending) {
            long chatId = num(row.get("chat_id"));
            int seq = ((Number) row.get("seq")).intValue();
            String content = String.valueOf(row.get("content_md"));
            try {
                if (hub.send(executorId, conversationFrame(chatId, seq, agentId, content))) {
                    markDelivery(chatId, seq, "SENT");
                    sent++;
                } else {
                    break;
                }
            } catch (Exception e) {
                log.warn("conversation replay failed chatId={} seq={} error={}", chatId, seq, e.getMessage());
                break;
            }
        }
        if (sent > 0) {
            log.info("conversation replay sent executorId={} count={}", executorId, sent);
        }
        return sent;
    }

    private Map<String, Object> conversationFrame(long chatId, int seq, long agentId, String contentMd) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "CONVERSATION_TURN");
        frame.put("conversationId", chatId);
        frame.put("turnId", seq);
        frame.put("agentId", agentId);
        frame.put("content", contentMd);
        frame.put("dispatchAttempt", 0);
        frame.put("systemPrompt", systemPrompt(agentId));
        return frame;
    }

    private Long onlineExecutorOf(long agentId) {
        List<Long> executors = jdbc.queryForList(
                "SELECT id FROM ag_executor WHERE agent_id=? AND status='ONLINE' AND deleted_at IS NULL ORDER BY id",
                Long.class, agentId);
        return executors.stream().filter(hub::isOnline).findFirst().orElse(null);
    }

    private void markDelivery(long chatId, int seq, String status) {
        jdbc.update("UPDATE ag_chat_turn SET delivery_status=? WHERE chat_id=? AND seq=? AND role='USER'",
                status, chatId, seq);
    }

    /** 收到运行器 ACK:落 ASSISTANT 回合。 */
    @Transactional
    public void handleAck(Map<String, Object> frame) {
        long chatId = num(frame.get("conversationId"));
        long turnId = num(frame.get("turnId"));
        String status = String.valueOf(frame.getOrDefault("status", "FAILED"));
        String reply = String.valueOf(frame.getOrDefault("replyMarkdown", ""));
        String error = String.valueOf(frame.getOrDefault("error", ""));
        if (chatId == 0 || turnId == 0) {
            return;
        }
        String key = chatId + ":" + turnId;
        if (done.containsKey(key)) {
            log.info("conversation ack replayed chatId={} turnId={}", chatId, turnId);
            return;
        }
        markDelivery(chatId, (int) turnId, "ANSWERED");
        Integer seq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq),0)+1 FROM ag_chat_turn WHERE chat_id=?", Integer.class, chatId);
        if ("SUCCESS".equals(status)) {
            jdbc.update("INSERT INTO ag_chat_turn (workspace_id, chat_id, seq, role, content_md) VALUES (?,?,?,'ASSISTANT',?)",
                    workspaceId(), chatId, seq, reply);
        } else {
            jdbc.update("INSERT INTO ag_chat_turn (workspace_id, chat_id, seq, role, content_md) VALUES (?,?,?,'ASSISTANT',?)",
                    workspaceId(), chatId, seq, "对话失败:" + error);
        }
        Object usage = frame.get("usage");
        if (usage instanceof Map<?, ?> u && u.get("input_tokens") != null) {
            long in = num(u.get("input_tokens"));
            long out = num(u.get("output_tokens"));
            jdbc.update("INSERT INTO ag_chat_usage (workspace_id, chat_id, tokens_in, tokens_out) VALUES (?,?,?,?)",
                    workspaceId(), chatId, in, out);
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("status", status);
        record.put("replyMarkdown", reply);
        done.put(key, record);
        log.info("conversation ack chatId={} turnId={} status={}", chatId, turnId, status);
    }

    private String systemPrompt(long agentId) {
        List<String> rows = jdbc.queryForList("SELECT soul_md FROM ag_agent WHERE id=?", String.class, agentId);
        String soul = rows.isEmpty() || rows.get(0) == null ? "" : rows.get(0);
        return "你是 AINative 的数字员工。" + (soul.isBlank() ? "" : "\n" + soul);
    }

    private long workspaceId() {
        return com.ainative.v2.common.RequestContext.workspaceId();
    }

    private static long num(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? 0 : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
