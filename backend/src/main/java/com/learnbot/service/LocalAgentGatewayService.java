package com.learnbot.service;

import com.learnbot.dto.LocalAgentConnectionState;
import com.learnbot.dto.LocalAgentStatusResponse;
import com.learnbot.dto.LocalAgentStatusSnapshot;
import com.learnbot.dto.LocalAgentWorkspaceSummary;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LocalAgentGatewayService {
    private static final Duration STALE_AFTER = Duration.ofSeconds(90);

    private final Map<UUID, LocalAgentStatusSnapshot> agentsByUser = new ConcurrentHashMap<>();

    public LocalAgentStatusResponse status(UUID userId) {
        return latest(userId)
                .map(this::toResponse)
                .orElseGet(LocalAgentStatusResponse::disconnected);
    }

    public void registerHeartbeat(
            UUID userId,
            UUID agentId,
            String version,
            List<String> capabilities,
            List<LocalAgentWorkspaceSummary> workspaces
    ) {
        registerHeartbeat(userId, agentId, version, capabilities, workspaces, null, null, null, null);
    }

    public void registerHeartbeat(
            UUID userId,
            UUID agentId,
            String version,
            List<String> capabilities,
            List<LocalAgentWorkspaceSummary> workspaces,
            String configuredTransport,
            String activeTransport,
            Integer webSocketFailureCount,
            OffsetDateTime nextWebSocketRetryAt
    ) {
        OffsetDateTime now = OffsetDateTime.now();
        LocalAgentStatusSnapshot current = agentsByUser.get(userId);
        OffsetDateTime connectedAt = current != null && current.agentId().equals(agentId)
                ? current.connectedAt()
                : now;
        agentsByUser.put(userId, new LocalAgentStatusSnapshot(
                agentId,
                userId,
                version,
                connectedAt,
                now,
                capabilities,
                workspaces,
                normalizeTransport(configuredTransport),
                normalizeTransport(activeTransport),
                webSocketFailureCount == null ? 0 : Math.max(0, webSocketFailureCount),
                nextWebSocketRetryAt
        ));
    }

    public void disconnect(UUID userId, UUID agentId) {
        agentsByUser.computeIfPresent(userId, (ignored, current) ->
                current.agentId().equals(agentId) ? null : current
        );
    }

    public boolean hasApprovedWorkspace(UUID userId, UUID workspaceId) {
        if (workspaceId == null) return false;
        return latest(userId)
                .stream()
                .flatMap(snapshot -> snapshot.workspaces().stream())
                .anyMatch(workspace -> workspaceId.equals(workspace.workspaceId()) && workspace.approved());
    }

    public Optional<LocalAgentWorkspaceSummary> approvedWorkspace(UUID userId, UUID workspaceId) {
        if (workspaceId == null) return Optional.empty();
        return latest(userId)
                .stream()
                .flatMap(snapshot -> snapshot.workspaces().stream())
                .filter(workspace -> workspaceId.equals(workspace.workspaceId()) && workspace.approved())
                .findFirst();
    }

    public boolean isConnected(UUID userId, UUID agentId) {
        if (agentId == null) return false;
        return latest(userId)
                .filter(snapshot -> agentId.equals(snapshot.agentId()))
                .filter(snapshot -> !isStale(snapshot))
                .isPresent();
    }

    private Optional<LocalAgentStatusSnapshot> latest(UUID userId) {
        if (userId == null) return Optional.empty();
        return Optional.ofNullable(agentsByUser.get(userId));
    }

    private LocalAgentStatusResponse toResponse(LocalAgentStatusSnapshot snapshot) {
        LocalAgentConnectionState state = isStale(snapshot)
                ? LocalAgentConnectionState.STALE
                : LocalAgentConnectionState.CONNECTED;
        return new LocalAgentStatusResponse(
                state,
                snapshot.agentId(),
                snapshot.version(),
                snapshot.connectedAt(),
                snapshot.lastSeenAt(),
                snapshot.capabilities().stream().sorted().toList(),
                snapshot.workspaces().stream()
                        .sorted(Comparator.comparing(workspace -> workspace.name() == null ? "" : workspace.name()))
                        .toList(),
                snapshot.configuredTransport(),
                snapshot.activeTransport(),
                snapshot.webSocketFailureCount(),
                snapshot.nextWebSocketRetryAt(),
                state == LocalAgentConnectionState.CONNECTED
                        ? "Local Agent is connected."
                        : "Local Agent heartbeat is stale. Side-effectful local tools should wait for reconnect."
        );
    }

    private String normalizeTransport(String transport) {
        if (transport == null || transport.isBlank()) return null;
        return transport.trim().toLowerCase();
    }

    private boolean isStale(LocalAgentStatusSnapshot snapshot) {
        return snapshot.lastSeenAt().isBefore(OffsetDateTime.now().minus(STALE_AFTER));
    }
}
