package com.ainative.workspace;

import com.ainative.json.JSONObject;
import com.ainative.redis.RedisManager;
import com.ainative.websocket.ExecutorSession;
import com.ainative.websocket.SessionRegistry;
import com.ainative.websocket.WsDispatchTransport;
import org.springframework.stereotype.Component;

@Component
public class WsWorkspaceCleanupTransport implements WorkspaceCleanupTransport {

    private final SessionRegistry sessionRegistry;
    private final RedisManager redisManager;

    public WsWorkspaceCleanupTransport(SessionRegistry sessionRegistry, RedisManager redisManager) {
        this.sessionRegistry = sessionRegistry;
        this.redisManager = redisManager;
    }

    @Override
    public void send(WorkspaceCleanupCandidate candidate) {
        JSONObject frame = new JSONObject(true);
        frame.put("type", "WORKITEM_WORKSPACE_CLEANUP");
        frame.put("executorId", candidate.getExecutorId());
        frame.put("tenantId", candidate.getTenantId());
        frame.put("workitemId", candidate.getWorkitemId());
        frame.put("workitemVersion", candidate.getWorkitemVersion());
        frame.put("publishedAt", candidate.getPublishedAt().getTime());
        String payload = frame.toJSONString();
        ExecutorSession session = sessionRegistry.findByExecutorId(candidate.getExecutorId());
        try {
            if (session != null && session.getSession().isOpen()) {
                session.sendText(payload);
            } else {
                redisManager.publish(WsDispatchTransport.BROADCAST_CHANNEL, payload);
            }
        } catch (Exception e) {
            throw new IllegalStateException("WebSocket workspace cleanup send failed", e);
        }
    }
}
