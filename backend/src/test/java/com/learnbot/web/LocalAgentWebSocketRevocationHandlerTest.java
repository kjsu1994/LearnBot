package com.learnbot.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.LocalAgentConnectionState;
import com.learnbot.dto.LocalAgentWorkspaceSummary;
import com.learnbot.service.LocalAgentGatewayService;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalAgentWebSocketRevocationHandlerTest {
    private final LocalAgentWebSocketConnectionRegistry registry =
            new LocalAgentWebSocketConnectionRegistry(new ObjectMapper());
    private final LocalAgentGatewayService gatewayService = new LocalAgentGatewayService();
    private final LocalAgentWebSocketRevocationHandler handler =
            new LocalAgentWebSocketRevocationHandler(registry, gatewayService);

    @Test
    void revokedTokenClosesMatchingSessionAndDisconnectsAgent() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        WebSocketSession session = session(userId, agentId, tokenId);
        gatewayService.registerHeartbeat(userId, agentId, "0.1.0", List.of("file.read"), List.of(
                new LocalAgentWorkspaceSummary(workspaceId, "api", "C:/work/api", true)
        ));
        registry.register(userId, agentId, session);

        handler.onTokenRevoked(userId, tokenId);

        verify(session).close(any(CloseStatus.class));
        assertThat(gatewayService.status(userId).state()).isEqualTo(LocalAgentConnectionState.DISCONNECTED);
    }

    @Test
    void unrelatedRevokedTokenDoesNotCloseSession() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        WebSocketSession session = session(userId, agentId, tokenId);
        registry.register(userId, agentId, session);

        handler.onTokenRevoked(userId, UUID.randomUUID());

        verify(session, org.mockito.Mockito.never()).close(any(CloseStatus.class));
    }

    private WebSocketSession session(UUID userId, UUID agentId, UUID tokenId) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(LocalAgentWebSocketAttributes.USER_ID, userId);
        attributes.put(LocalAgentWebSocketAttributes.AGENT_ID, agentId);
        attributes.put(LocalAgentWebSocketAttributes.TOKEN_ID, tokenId);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getId()).thenReturn(UUID.randomUUID().toString());
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
