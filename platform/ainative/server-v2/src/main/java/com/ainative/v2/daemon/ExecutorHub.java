package com.ainative.v2.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 在线执行器会话注册表:executorId → WebSocketSession。 */
@Component
public class ExecutorHub {
    private static final Logger log = LoggerFactory.getLogger(ExecutorHub.class);
    private final Map<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public void register(long executorId, WebSocketSession session) {
        WebSocketSession previous = sessions.put(executorId, session);
        if (previous != null && previous.isOpen()) {
            try {
                previous.close();
            } catch (IOException ignored) {
            }
        }
        log.info("executor connected executorId={} online={}", executorId, sessions.size());
    }

    public void unregister(long executorId, WebSocketSession session) {
        sessions.remove(executorId, session);
    }

    public boolean isOnline(long executorId) {
        WebSocketSession s = sessions.get(executorId);
        return s != null && s.isOpen();
    }

    /** 主动断开某执行器(令牌轮换/删除时)。 */
    public void close(long executorId) {
        WebSocketSession session = sessions.remove(executorId);
        if (session != null && session.isOpen()) {
            try {
                session.close();
            } catch (IOException ignored) {
            }
        }
    }

    public Set<Long> onlineIds() {
        return sessions.keySet();
    }

    /** 发送 JSON 帧;执行器不在线返回 false。 */
    public boolean send(long executorId, Object frame) {
        WebSocketSession session = sessions.get(executorId);
        if (session == null || !session.isOpen()) {
            return false;
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(mapper.writeValueAsString(frame)));
            }
            return true;
        } catch (Exception e) {
            log.warn("send frame failed executorId={} error={}", executorId, e.getMessage());
            return false;
        }
    }
}
