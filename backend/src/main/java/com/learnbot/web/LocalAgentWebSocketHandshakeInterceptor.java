package com.learnbot.web;

import com.learnbot.security.UnauthorizedException;
import com.learnbot.service.LocalAgentAuthService;
import com.learnbot.service.LocalAgentToken;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Component
public class LocalAgentWebSocketHandshakeInterceptor implements HandshakeInterceptor {
    private static final String TOKEN_HEADER = "X-Local-Agent-Token";

    private final LocalAgentAuthService authService;

    public LocalAgentWebSocketHandshakeInterceptor(LocalAgentAuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        String token = request.getHeaders().getFirst(TOKEN_HEADER);
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            LocalAgentToken authenticated = authService.authenticate(token);
            attributes.put(LocalAgentWebSocketAttributes.USER_ID, authenticated.userId());
            attributes.put(LocalAgentWebSocketAttributes.AGENT_ID, authenticated.agentId());
            attributes.put(LocalAgentWebSocketAttributes.TOKEN_ID, authenticated.id());
            return true;
        } catch (UnauthorizedException ex) {
            return false;
        }
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
    }
}
