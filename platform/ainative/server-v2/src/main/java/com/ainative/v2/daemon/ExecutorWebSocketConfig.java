package com.ainative.v2.daemon;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 端点:
 * <ul>
 *   <li>/ws/executor —— 执行器通道(与运行器 ymai-codeflow 协议兼容)</li>
 *   <li>/ws —— 浏览器实时通道(澄清流式回复等;鉴权走带内 auth 帧)</li>
 * </ul>
 */
@Configuration
@EnableWebSocket
public class ExecutorWebSocketConfig implements WebSocketConfigurer {
    private final ExecutorWsHandler handler;
    private final com.ainative.v2.realtime.BrowserWsHandler browserHandler;

    public ExecutorWebSocketConfig(ExecutorWsHandler handler,
                                   com.ainative.v2.realtime.BrowserWsHandler browserHandler) {
        this.handler = handler;
        this.browserHandler = browserHandler;
    }

    /**
     * 文本帧缓冲上限。
     *
     * <p>Spring 默认 8KB —— 任务包(workitem.md + sdlc + identity + roster)一旦超过就
     * 被服务端以 1009 直接关闭连接，运行节点只看到一句
     * "The decoded text message was too big"，派发静默失败。
     * 详细需求(带规格的工单正文)轻松超过 8KB，这里放宽到 4MB；
     * 真正的边界由 {@code DispatchLaunchService} 的派发前置校验给出可读提示。
     */
    private static final int TEXT_MESSAGE_BUFFER_BYTES = 4 * 1024 * 1024;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/executor").setAllowedOrigins("*");
        registry.addHandler(browserHandler, "/ws").setAllowedOrigins("*");
    }

    /**
     * 放宽单帧消息上限。
     *
     * <p>容器默认 8KB —— 任务包(workitem.md + sdlc + identity + roster)一旦超过就
     * 被服务端以 1009 直接关闭连接，运行节点只看到一句
     * "The decoded text message was too big"，派发静默失败。
     * 带规格的工单正文轻松超过 8KB，这里放宽到 4MB；
     * 真正的边界由 {@code DispatchLaunchService} 的派发前置校验给出可读提示。
     *
     * <p>自定义容器 bean 会顶掉 Boot 的默认容器（默认那个带 @ConditionalOnMissingBean）。
     */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(TEXT_MESSAGE_BUFFER_BYTES);
        container.setMaxBinaryMessageBufferSize(TEXT_MESSAGE_BUFFER_BYTES);
        container.setMaxSessionIdleTimeout(30 * 60 * 1000L);
        return container;
    }
}
