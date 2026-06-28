package com.learnbot.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.LocalAgentHeartbeatRequest;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolResponse;
import com.learnbot.security.UnauthorizedException;
import com.learnbot.service.LocalAgentGatewayService;
import com.learnbot.service.LocalAgentToolGatewayService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class LocalAgentWebSocketHandler extends TextWebSocketHandler {
    private final ObjectMapper objectMapper;
    private final LocalAgentGatewayService gatewayService;
    private final LocalAgentToolGatewayService toolGatewayService;
    private final LocalAgentWebSocketConnectionRegistry connectionRegistry;

    public LocalAgentWebSocketHandler(
            ObjectMapper objectMapper,
            LocalAgentGatewayService gatewayService,
            LocalAgentToolGatewayService toolGatewayService,
            LocalAgentWebSocketConnectionRegistry connectionRegistry
    ) {
        this.objectMapper = objectMapper;
        this.gatewayService = gatewayService;
        this.toolGatewayService = toolGatewayService;
        this.connectionRegistry = connectionRegistry;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode envelope = objectMapper.readTree(message.getPayload());
        String type = envelope.path("type").asText("");
        switch (type) {
            case "hello", "heartbeat" -> handleHeartbeat(session, envelope);
            case "tool.response" -> handleToolResponse(session, envelope);
            case "ping" -> send(session, "pong", envelope.path("messageId").asText(null), null, Map.of());
            default -> send(session, "error", envelope.path("messageId").asText(null), null, Map.of(
                    "message", "Unsupported Local Agent WebSocket message type.",
                    "type", type
            ));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID userId = sessionAttribute(session, LocalAgentWebSocketAttributes.USER_ID);
        UUID agentId = sessionAttribute(session, LocalAgentWebSocketAttributes.AGENT_ID);
        if (userId != null && agentId != null) {
            connectionRegistry.unregister(userId, agentId, session);
            gatewayService.disconnect(userId, agentId);
        }
    }

    private void handleHeartbeat(WebSocketSession session, JsonNode envelope) throws Exception {
        UUID userId = sessionAttribute(session, LocalAgentWebSocketAttributes.USER_ID);
        UUID agentId = sessionAttribute(session, LocalAgentWebSocketAttributes.AGENT_ID);
        if (userId == null || agentId == null) {
            throw new UnauthorizedException("Local Agent WebSocket session is not authenticated.");
        }
        if (envelope.hasNonNull("agentId") && !agentId.equals(UUID.fromString(envelope.get("agentId").asText()))) {
            send(session, "error", envelope.path("messageId").asText(null), null, Map.of(
                    "message", "Local Agent message agent id does not match the authenticated token."
            ));
            return;
        }
        LocalAgentHeartbeatRequest request = objectMapper.treeToValue(envelope.path("payload"), LocalAgentHeartbeatRequest.class);
        if (!agentId.equals(request.agentId())) {
            send(session, "error", envelope.path("messageId").asText(null), null, Map.of(
                    "message", "Local Agent heartbeat agent id does not match the authenticated token."
            ));
            return;
        }
        gatewayService.registerHeartbeat(
                userId,
                agentId,
                request.version(),
                request.capabilities() == null
                        ? List.of()
                        : request.capabilities().stream().map(LocalAgentToolName::wireName).toList(),
                request.workspaces() == null ? List.of() : request.workspaces()
        );
        connectionRegistry.register(userId, agentId, session);
        send(session, "tool.ack", envelope.path("messageId").asText(null), null, Map.of("accepted", true));
    }

    private void handleToolResponse(WebSocketSession session, JsonNode envelope) throws Exception {
        UUID userId = sessionAttribute(session, LocalAgentWebSocketAttributes.USER_ID);
        UUID agentId = sessionAttribute(session, LocalAgentWebSocketAttributes.AGENT_ID);
        if (userId == null || agentId == null) {
            throw new UnauthorizedException("Local Agent WebSocket session is not authenticated.");
        }
        UUID requestId = envelope.hasNonNull("requestId") ? UUID.fromString(envelope.get("requestId").asText()) : null;
        if (requestId == null) {
            send(session, "error", envelope.path("messageId").asText(null), null, Map.of(
                    "message", "Local Agent tool.response requires requestId."
            ));
            return;
        }
        LocalAgentToolResponse response = objectMapper.treeToValue(envelope.path("payload"), LocalAgentToolResponse.class);
        if (!requestId.equals(response.requestId())) {
            send(session, "error", envelope.path("messageId").asText(null), requestId, Map.of(
                    "message", "Local Agent tool.response request id does not match the envelope."
            ));
            return;
        }
        if (!userId.equals(response.userId()) || !agentId.equals(response.agentId())) {
            send(session, "error", envelope.path("messageId").asText(null), requestId, Map.of(
                    "message", "Local Agent tool.response does not match the authenticated session."
            ));
            return;
        }
        toolGatewayService.complete(response);
        send(session, "tool.ack", envelope.path("messageId").asText(null), requestId, Map.of("accepted", true));
    }

    private void send(WebSocketSession session, String type, String messageId, UUID requestId, Map<String, Object> payload) throws Exception {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("type", type);
        envelope.put("messageId", messageId == null ? UUID.randomUUID().toString() : messageId);
        envelope.put("agentId", sessionAttribute(session, LocalAgentWebSocketAttributes.AGENT_ID));
        envelope.put("requestId", requestId);
        envelope.put("sentAt", OffsetDateTime.now().toString());
        envelope.put("payload", payload);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(envelope)));
    }

    @SuppressWarnings("unchecked")
    private <T> T sessionAttribute(WebSocketSession session, String name) {
        return (T) session.getAttributes().get(name);
    }
}
