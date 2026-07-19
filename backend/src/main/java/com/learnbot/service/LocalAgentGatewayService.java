package com.learnbot.service;

import com.learnbot.dto.LocalAgentConnectionState;
import com.learnbot.dto.LocalAgentStatusResponse;
import com.learnbot.dto.LocalAgentStatusSnapshot;
import com.learnbot.dto.LocalAgentWorkspaceSummary;
import com.learnbot.repository.LocalAgentDeviceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LocalAgentGatewayService {
    private static final Duration STALE_AFTER = Duration.ofSeconds(90);
    private final Map<UUID, Map<UUID, LocalAgentStatusSnapshot>> agentsByUser = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> selectedAgentsByUser = new ConcurrentHashMap<>();
    private final LocalAgentDeviceRepository deviceRepository;
    private final LocalAgentVersionPolicy versionPolicy;

    public LocalAgentGatewayService() {
        this(null, new LocalAgentVersionPolicy("0.1.0", "0.1.0",
                "/downloads/local-agent/stable/LearnBotLocalAgent.appinstaller"));
    }

    @Autowired
    public LocalAgentGatewayService(LocalAgentDeviceRepository deviceRepository, LocalAgentVersionPolicy versionPolicy) {
        this.deviceRepository = deviceRepository;
        this.versionPolicy = versionPolicy;
    }

    public LocalAgentStatusResponse status(UUID userId) {
        return selected(userId).map(this::toResponse).orElseGet(LocalAgentStatusResponse::disconnected);
    }

    public LocalAgentStatusResponse status(UUID userId, UUID agentId) {
        if (userId == null || agentId == null) return LocalAgentStatusResponse.disconnected();
        return snapshots(userId).stream().filter(s -> agentId.equals(s.agentId())).findFirst()
                .map(this::toResponse).orElseGet(LocalAgentStatusResponse::disconnected);
    }

    public List<LocalAgentStatusResponse> statuses(UUID userId) {
        return snapshots(userId).stream()
                .sorted(Comparator.comparing(LocalAgentStatusSnapshot::lastSeenAt).reversed())
                .map(this::toResponse).toList();
    }

    public void registerHeartbeat(UUID userId, UUID agentId, String version, List<String> capabilities,
                                  List<LocalAgentWorkspaceSummary> workspaces) {
        registerHeartbeat(userId, agentId, version, capabilities, workspaces, null, null, null, null);
    }

    public void registerHeartbeat(UUID userId, UUID agentId, String version, List<String> capabilities,
                                  List<LocalAgentWorkspaceSummary> workspaces, String configuredTransport,
                                  String activeTransport, Integer webSocketFailureCount,
                                  OffsetDateTime nextWebSocketRetryAt) {
        OffsetDateTime now = OffsetDateTime.now();
        LocalAgentStatusSnapshot current = Optional.ofNullable(agentsByUser.get(userId))
                .map(map -> map.get(agentId)).orElse(null);
        LocalAgentStatusSnapshot snapshot = new LocalAgentStatusSnapshot(
                agentId, userId, version, current == null ? now : current.connectedAt(), now,
                capabilities == null ? List.of() : capabilities, mergeWorkspaces(current, workspaces),
                normalizeTransport(configuredTransport), normalizeTransport(activeTransport),
                webSocketFailureCount == null ? 0 : Math.max(0, webSocketFailureCount), nextWebSocketRetryAt
        );
        agentsByUser.computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>()).put(agentId, snapshot);
        selectedAgentsByUser.putIfAbsent(userId, agentId);
        if (deviceRepository != null) deviceRepository.upsertHeartbeat(snapshot);
    }

    public void disconnect(UUID userId, UUID agentId) {
        agentsByUser.computeIfPresent(userId, (ignored, agents) -> {
            agents.remove(agentId);
            return agents.isEmpty() ? null : agents;
        });
        selectedAgentsByUser.computeIfPresent(userId, (ignored, selectedAgentId) -> {
            if (!selectedAgentId.equals(agentId)) return selectedAgentId;
            return Optional.ofNullable(agentsByUser.get(userId)).stream()
                    .flatMap(agents -> agents.values().stream())
                    .sorted(Comparator.comparing(LocalAgentStatusSnapshot::connectedAt)
                            .thenComparing(snapshot -> snapshot.agentId().toString()))
                    .map(LocalAgentStatusSnapshot::agentId)
                    .findFirst()
                    .orElse(null);
        });
    }

    public void select(UUID userId, UUID agentId) {
        if (userId != null && agentId != null) selectedAgentsByUser.put(userId, agentId);
    }

    public void clearSelection(UUID userId) {
        if (userId != null) selectedAgentsByUser.remove(userId);
    }

    public boolean hasApprovedWorkspace(UUID userId, UUID workspaceId) {
        return workspaceId != null && selected(userId).stream().flatMap(s -> s.workspaces().stream())
                .anyMatch(w -> workspaceId.equals(w.workspaceId()) && w.approved());
    }

    public boolean hasApprovedWorkspace(UUID userId, UUID agentId, UUID workspaceId) {
        return agentId != null && workspaceId != null && snapshots(userId).stream()
                .filter(s -> agentId.equals(s.agentId())).flatMap(s -> s.workspaces().stream())
                .anyMatch(w -> workspaceId.equals(w.workspaceId()) && w.approved());
    }

    public Optional<LocalAgentWorkspaceSummary> approvedWorkspace(UUID userId, UUID workspaceId) {
        if (workspaceId == null) return Optional.empty();
        return selected(userId).stream().flatMap(s -> s.workspaces().stream())
                .filter(w -> workspaceId.equals(w.workspaceId()) && w.approved()).findFirst();
    }

    public boolean isConnected(UUID userId, UUID agentId) {
        return agentId != null && snapshots(userId).stream().filter(s -> agentId.equals(s.agentId()))
                .anyMatch(s -> !isStale(s));
    }

    private List<LocalAgentWorkspaceSummary> mergeWorkspaces(LocalAgentStatusSnapshot current,
                                                              List<LocalAgentWorkspaceSummary> incoming) {
        Map<UUID, LocalAgentWorkspaceSummary> merged = new LinkedHashMap<>();
        if (current != null) current.workspaces().stream()
                .filter(w -> w.workspaceId() != null && w.approved())
                .forEach(w -> merged.put(w.workspaceId(), w));
        for (LocalAgentWorkspaceSummary workspace : incoming == null ? List.<LocalAgentWorkspaceSummary>of() : incoming) {
            if (workspace.workspaceId() != null) merged.put(workspace.workspaceId(), workspace);
        }
        return List.copyOf(merged.values());
    }

    private Optional<LocalAgentStatusSnapshot> selected(UUID userId) {
        if (userId == null) return Optional.empty();
        UUID selectedAgentId;
        if (deviceRepository != null) {
            selectedAgentId = deviceRepository.findSelectedByUser(userId)
                    .map(LocalAgentDevice::agentId)
                    .orElse(null);
        } else {
            selectedAgentId = selectedAgentsByUser.get(userId);
        }
        if (selectedAgentId == null) return Optional.empty();
        UUID expectedAgentId = selectedAgentId;
        return snapshots(userId).stream().filter(snapshot -> expectedAgentId.equals(snapshot.agentId())).findFirst();
    }

    private List<LocalAgentStatusSnapshot> snapshots(UUID userId) {
        if (userId == null) return List.of();
        Map<UUID, LocalAgentStatusSnapshot> merged = new LinkedHashMap<>();
        if (deviceRepository != null) deviceRepository.snapshots(userId).forEach(s -> merged.put(s.agentId(), s));
        Map<UUID, LocalAgentStatusSnapshot> memory = agentsByUser.get(userId);
        if (memory != null) memory.values().forEach(s -> merged.put(s.agentId(), s));
        return List.copyOf(merged.values());
    }

    private LocalAgentStatusResponse toResponse(LocalAgentStatusSnapshot snapshot) {
        LocalAgentConnectionState state = isStale(snapshot) ? LocalAgentConnectionState.STALE : LocalAgentConnectionState.CONNECTED;
        LocalAgentVersionPolicy.Decision update = versionPolicy.evaluate(snapshot.version());
        return new LocalAgentStatusResponse(
                state, snapshot.agentId(), snapshot.version(), snapshot.connectedAt(), snapshot.lastSeenAt(),
                snapshot.capabilities().stream().sorted().toList(),
                snapshot.workspaces().stream().sorted(Comparator.comparing(w -> w.name() == null ? "" : w.name())).toList(),
                snapshot.configuredTransport(), snapshot.activeTransport(), snapshot.webSocketFailureCount(),
                snapshot.nextWebSocketRetryAt(), state == LocalAgentConnectionState.CONNECTED
                        ? "Local Agent is connected."
                        : "Local Agent heartbeat is stale. Side-effectful local tools should wait for reconnect.",
                update.latestVersion(), update.minimumVersion(), update.updateState(), update.updateUri()
        );
    }

    private String normalizeTransport(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase();
    }

    private boolean isStale(LocalAgentStatusSnapshot snapshot) {
        return snapshot.lastSeenAt().isBefore(OffsetDateTime.now().minus(STALE_AFTER));
    }
}
