package com.learnbot.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.LocalAgentQueuedToolRequest;
import com.learnbot.service.LocalAgentToolPusher;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LocalAgentWebSocketConnectionRegistry implements LocalAgentToolPusher {
    private final ObjectMapper objectMapper;
    private final Map<ConnectionKey, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<TokenKey, ConnectionKey> connectionsByToken = new ConcurrentHashMap<>();

    public LocalAgentWebSocketConnectionRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(UUID userId, UUID agentId, WebSocketSession session) {
        if (userId == null || agentId == null || session == null) return;
        ConnectionKey connectionKey = new ConnectionKey(userId, agentId);
        sessions.put(connectionKey, session);
        UUID tokenId = sessionAttribute(session, LocalAgentWebSocketAttributes.TOKEN_ID);
        if (tokenId != null) {
            connectionsByToken.put(new TokenKey(userId, tokenId), connectionKey);
        }
    }

    public void unregister(UUID userId, UUID agentId, WebSocketSession session) {
        if (userId == null || agentId == null) return;
        ConnectionKey connectionKey = new ConnectionKey(userId, agentId);
        sessions.computeIfPresent(connectionKey, (ignored, current) ->
                Objects.equals(current.getId(), session.getId()) ? null : current
        );
        UUID tokenId = sessionAttribute(session, LocalAgentWebSocketAttributes.TOKEN_ID);
        if (tokenId != null) {
            connectionsByToken.remove(new TokenKey(userId, tokenId), connectionKey);
        }
    }

    public UUID closeTokenSession(UUID userId, UUID tokenId) {
        ConnectionKey connectionKey = connectionsByToken.remove(new TokenKey(userId, tokenId));
        if (connectionKey == null) return null;
        WebSocketSession session = sessions.remove(connectionKey);
        if (session != null && session.isOpen()) {
            try {
                session.close(CloseStatus.POLICY_VIOLATION.withReason("Local Agent token was revoked."));
            } catch (Exception ignored) {
            }
        }
        return connectionKey.agentId();
    }

    @Override
    public boolean sendToolRequest(LocalAgentQueuedToolRequest queued) {
        WebSocketSession session = sessions.get(new ConnectionKey(queued.request().userId(), queued.request().agentId()));
        if (session == null || !session.isOpen()) {
            return false;
        }
        try {
            Map<String, Object> envelope = new HashMap<>();
            envelope.put("type", "tool.request");
            envelope.put("messageId", UUID.randomUUID().toString());
            envelope.put("agentId", queued.request().agentId());
            envelope.put("requestId", queued.requestId());
            envelope.put("sentAt", OffsetDateTime.now().toString());
            envelope.put("payload", queued);
            synchronized (session) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(envelope)));
            }
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private record ConnectionKey(UUID userId, UUID agentId) {
    }

    private record TokenKey(UUID userId, UUID tokenId) {
    }

    @SuppressWarnings("unchecked")
    private <T> T sessionAttribute(WebSocketSession session, String name) {
        return (T) session.getAttributes().get(name);
    }
}
