package com.rigour.collaboration.infrastructure.realtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** 注册协作 WebSocket 入口；鉴权仍由 Gateway 的可信上下文链路负责。 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class CollaborationWebSocketConfiguration implements WebSocketConfigurer {
    private final CollaborationWebSocketHandler handler;

    public CollaborationWebSocketConfiguration(CollaborationWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/v1/collaboration").setAllowedOriginPatterns("*");
    }
}
