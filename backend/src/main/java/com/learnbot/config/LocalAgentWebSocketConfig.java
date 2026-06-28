package com.learnbot.config;

import com.learnbot.web.LocalAgentWebSocketHandler;
import com.learnbot.web.LocalAgentWebSocketHandshakeInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@ConditionalOnProperty(prefix = "learnbot.local-agent", name = "websocket-enabled", havingValue = "true")
public class LocalAgentWebSocketConfig implements WebSocketConfigurer {
    private final LocalAgentWebSocketHandler handler;
    private final LocalAgentWebSocketHandshakeInterceptor handshakeInterceptor;

    public LocalAgentWebSocketConfig(LocalAgentWebSocketHandler handler, LocalAgentWebSocketHandshakeInterceptor handshakeInterceptor) {
        this.handler = handler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/local-agents/ws")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
