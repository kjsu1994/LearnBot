package com.learnbot.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.LocalAgentConnectionState;
import com.learnbot.service.LocalAgentGatewayService;
import com.learnbot.service.LocalAgentToolGatewayService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class LocalAgentWebSocketHandlerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LocalAgentGatewayService gatewayService = new LocalAgentGatewayService();
    private final LocalAgentToolGatewayService toolGatewayService = mock(LocalAgentToolGatewayService.class);
    private final LocalAgentWebSocketConnectionRegistry connectionRegistry = mock(LocalAgentWebSocketConnectionRegistry.class);
    private final LocalAgentWebSocketHandler handler = new LocalAgentWebSocketHandler(
            objectMapper,
            gatewayService,
            toolGatewayService,
            connectionRegistry
    );

    @Test
    void helloRegistersHeartbeatAndSendsAck() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        WebSocketSession session = session(userId, agentId);
        String message = """
                {
                  "type": "hello",
                  "messageId": "hello-1",
                  "agentId": "%s",
                  "payload": {
                    "agentId": "%s",
                    "version": "0.1.0",
                    "capabilities": ["file.read", "git.status"],
                    "configuredTransport": "websocket",
                    "activeTransport": "websocket",
                    "webSocketFailureCount": 0,
                    "workspaces": [
                      { "workspaceId": "%s", "name": "api", "rootPath": "C:/work/api", "approved": true }
                    ]
                  }
                }
                """.formatted(agentId, agentId, workspaceId);

        handler.handleTextMessage(session, new TextMessage(message));

        var status = gatewayService.status(userId);
        assertThat(status.state()).isEqualTo(LocalAgentConnectionState.CONNECTED);
        assertThat(status.agentId()).isEqualTo(agentId);
        assertThat(status.capabilities()).containsExactly("file.read", "git.status");
        assertThat(status.configuredTransport()).isEqualTo("websocket");
        assertThat(status.activeTransport()).isEqualTo("websocket");
        assertThat(status.webSocketFailureCount()).isZero();
        assertThat(gatewayService.hasApprovedWorkspace(userId, workspaceId)).isTrue();
        verify(connectionRegistry).register(userId, agentId, session);

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(sent.capture());
        assertThat(sent.getValue().getPayload()).contains("\"type\":\"tool.ack\"");
    }

    @Test
    void mismatchedAgentIdSendsErrorAndDoesNotRegister() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID authenticatedAgentId = UUID.randomUUID();
        UUID claimedAgentId = UUID.randomUUID();
        WebSocketSession session = session(userId, authenticatedAgentId);
        String message = """
                {
                  "type": "hello",
                  "messageId": "hello-1",
                  "agentId": "%s",
                  "payload": {
                    "agentId": "%s",
                    "version": "0.1.0",
                    "capabilities": ["file.read"],
                    "workspaces": []
                  }
                }
                """.formatted(claimedAgentId, claimedAgentId);

        handler.handleTextMessage(session, new TextMessage(message));

        assertThat(gatewayService.status(userId).state()).isEqualTo(LocalAgentConnectionState.DISCONNECTED);
        verify(connectionRegistry, never()).register(userId, authenticatedAgentId, session);
        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(sent.capture());
        assertThat(sent.getValue().getPayload()).contains("\"type\":\"error\"");
    }

    @Test
    void toolResponseCompletesThroughGatewayAndSendsAck() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        WebSocketSession session = session(userId, agentId);
        String message = """
                {
                  "type": "tool.response",
                  "messageId": "response-1",
                  "agentId": "%s",
                  "requestId": "%s",
                  "payload": {
                    "sessionId": "%s",
                    "requestId": "%s",
                    "userId": "%s",
                    "agentId": "%s",
                    "workspaceId": "%s",
                    "executionTarget": "USER_LOCAL_AGENT",
                    "toolName": "file.read",
                    "status": "SUCCEEDED",
                    "output": { "content": "ok" },
                    "warnings": []
                  }
                }
                """.formatted(agentId, requestId, sessionId, requestId, userId, agentId, workspaceId);

        handler.handleTextMessage(session, new TextMessage(message));

        ArgumentCaptor<com.learnbot.dto.LocalAgentToolResponse> response =
                ArgumentCaptor.forClass(com.learnbot.dto.LocalAgentToolResponse.class);
        verify(toolGatewayService).complete(response.capture());
        assertThat(response.getValue().requestId()).isEqualTo(requestId);
        assertThat(response.getValue().userId()).isEqualTo(userId);
        assertThat(response.getValue().agentId()).isEqualTo(agentId);

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(sent.capture());
        assertThat(sent.getValue().getPayload()).contains("\"type\":\"tool.ack\"");
    }

    private WebSocketSession session(UUID userId, UUID agentId) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(LocalAgentWebSocketAttributes.USER_ID, userId);
        attributes.put(LocalAgentWebSocketAttributes.AGENT_ID, agentId);
        attributes.put(LocalAgentWebSocketAttributes.TOKEN_ID, UUID.randomUUID());
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }
}
