package com.ainative.v2.daemon;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** 执行器 WebSocket 端点:/ws/executor(与运行器 ymai-codeflow 协议兼容)。 */
@Configuration
@EnableWebSocket
public class ExecutorWebSocketConfig implements WebSocketConfigurer {
    private final ExecutorWsHandler handler;

    public ExecutorWebSocketConfig(ExecutorWsHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/executor").setAllowedOrigins("*");
    }
}
