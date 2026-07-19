package com.learnbot.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.LocalAgentStatusSnapshot;
import com.learnbot.dto.LocalAgentWorkspaceSummary;
import com.learnbot.service.LocalAgentDevice;
import com.learnbot.service.LocalAgentEnrollment;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class LocalAgentDeviceRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<List<LocalAgentWorkspaceSummary>> WORKSPACE_LIST = new TypeReference<>() { };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LocalAgentDeviceRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void ensureDevice(UUID userId, UUID agentId, String label, OffsetDateTime approvedAt) {
        lockUser(userId);
        jdbc.update("""
                INSERT INTO local_agent_devices (agent_id, user_id, label, approved_at)
                VALUES (:agentId, :userId, :label, :approvedAt)
                ON CONFLICT (agent_id) DO UPDATE SET
                    label = COALESCE(EXCLUDED.label, local_agent_devices.label),
                    approved_at = COALESCE(local_agent_devices.approved_at, EXCLUDED.approved_at),
                    updated_at = now()
                WHERE local_agent_devices.user_id = EXCLUDED.user_id
                """, new MapSqlParameterSource()
                .addValue("agentId", agentId)
                .addValue("userId", userId)
                .addValue("label", label)
                .addValue("approvedAt", approvedAt));
        selectFirstActiveIfNoneLocked(userId, approvedAt);
    }

    @Transactional
    public void upsertEnrollment(LocalAgentEnrollment enrollment, OffsetDateTime now) {
        lockUser(enrollment.userId());
        jdbc.update("""
                INSERT INTO local_agent_devices (
                    agent_id, user_id, installation_id, label, client_name, machine_name,
                    os_name, os_version, architecture, agent_version, approved_at, updated_at
                ) VALUES (
                    :agentId, :userId, :installationId, :label, :clientName, :machineName,
                    :osName, :osVersion, :architecture, :agentVersion, :approvedAt, :now
                )
                ON CONFLICT (agent_id) DO UPDATE SET
                    installation_id = EXCLUDED.installation_id,
                    label = COALESCE(EXCLUDED.label, local_agent_devices.label),
                    client_name = EXCLUDED.client_name,
                    machine_name = EXCLUDED.machine_name,
                    os_name = EXCLUDED.os_name,
                    os_version = EXCLUDED.os_version,
                    architecture = EXCLUDED.architecture,
                    agent_version = EXCLUDED.agent_version,
                    approved_at = COALESCE(local_agent_devices.approved_at, EXCLUDED.approved_at),
                    updated_at = :now
                WHERE local_agent_devices.user_id = EXCLUDED.user_id
                """, new MapSqlParameterSource()
                .addValue("agentId", enrollment.agentId())
                .addValue("userId", enrollment.userId())
                .addValue("installationId", enrollment.installationId())
                .addValue("label", enrollment.label())
                .addValue("clientName", enrollment.clientName())
                .addValue("machineName", enrollment.machineName())
                .addValue("osName", enrollment.osName())
                .addValue("osVersion", enrollment.osVersion())
                .addValue("architecture", enrollment.architecture())
                .addValue("agentVersion", enrollment.agentVersion())
                .addValue("approvedAt", enrollment.approvedAt())
                .addValue("now", now));
        selectFirstActiveIfNoneLocked(enrollment.userId(), now);
    }

    @Transactional
    public void upsertHeartbeat(LocalAgentStatusSnapshot snapshot) {
        lockUser(snapshot.userId());
        jdbc.update("""
                INSERT INTO local_agent_devices (
                    agent_id, user_id, agent_version, capabilities, workspaces,
                    configured_transport, active_transport, websocket_failure_count,
                    next_websocket_retry_at, approved_at, last_seen_at, updated_at
                ) VALUES (
                    :agentId, :userId, :version, CAST(:capabilities AS jsonb), CAST(:workspaces AS jsonb),
                    :configuredTransport, :activeTransport, :webSocketFailureCount,
                    :nextWebSocketRetryAt, :connectedAt, :lastSeenAt, :lastSeenAt
                )
                ON CONFLICT (agent_id) DO UPDATE SET
                    agent_version = EXCLUDED.agent_version,
                    capabilities = EXCLUDED.capabilities,
                    workspaces = EXCLUDED.workspaces,
                    configured_transport = EXCLUDED.configured_transport,
                    active_transport = EXCLUDED.active_transport,
                    websocket_failure_count = EXCLUDED.websocket_failure_count,
                    next_websocket_retry_at = EXCLUDED.next_websocket_retry_at,
                    last_seen_at = EXCLUDED.last_seen_at,
                    updated_at = EXCLUDED.last_seen_at
                WHERE local_agent_devices.user_id = EXCLUDED.user_id
                  AND local_agent_devices.revoked_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("agentId", snapshot.agentId())
                .addValue("userId", snapshot.userId())
                .addValue("version", snapshot.version())
                .addValue("capabilities", json(snapshot.capabilities()))
                .addValue("workspaces", json(snapshot.workspaces()))
                .addValue("configuredTransport", snapshot.configuredTransport())
                .addValue("activeTransport", snapshot.activeTransport())
                .addValue("webSocketFailureCount", snapshot.webSocketFailureCount())
                .addValue("nextWebSocketRetryAt", snapshot.nextWebSocketRetryAt())
                .addValue("connectedAt", snapshot.connectedAt())
                .addValue("lastSeenAt", snapshot.lastSeenAt()));
        selectFirstActiveIfNoneLocked(snapshot.userId(), snapshot.lastSeenAt());
    }

    public List<LocalAgentDevice> listActiveByUser(UUID userId) {
        return jdbc.query("""
                SELECT * FROM local_agent_devices
                WHERE user_id = :userId AND revoked_at IS NULL
                ORDER BY selected_at DESC NULLS LAST, last_seen_at DESC NULLS LAST, created_at ASC, agent_id ASC
                """, new MapSqlParameterSource("userId", userId), this::map);
    }

    public List<LocalAgentDevice> listRegisteredByUser(UUID userId) {
        return jdbc.query("""
                SELECT * FROM local_agent_devices
                WHERE user_id = :userId
                  AND revoked_at IS NULL
                  AND installation_id IS NOT NULL
                  AND machine_name IS NOT NULL
                ORDER BY selected_at DESC NULLS LAST, last_seen_at DESC NULLS LAST, created_at ASC, agent_id ASC
                """, new MapSqlParameterSource("userId", userId), this::map);
    }

    public Optional<LocalAgentDevice> findActiveByUserAndAgent(UUID userId, UUID agentId) {
        List<LocalAgentDevice> rows = jdbc.query("""
                SELECT * FROM local_agent_devices
                WHERE user_id = :userId AND agent_id = :agentId AND revoked_at IS NULL
                """, new MapSqlParameterSource().addValue("userId", userId).addValue("agentId", agentId), this::map);
        return rows.stream().findFirst();
    }

    public Optional<LocalAgentDevice> findSelectedByUser(UUID userId) {
        List<LocalAgentDevice> rows = jdbc.query("""
                SELECT * FROM local_agent_devices
                WHERE user_id = :userId AND selected_at IS NOT NULL AND revoked_at IS NULL
                """, new MapSqlParameterSource("userId", userId), this::map);
        return rows.stream().findFirst();
    }

    public List<LocalAgentDevice> findOtherActiveByInstallation(
            UUID userId,
            UUID installationId,
            UUID agentId
    ) {
        if (installationId == null) return List.of();
        return jdbc.query("""
                SELECT * FROM local_agent_devices
                WHERE user_id = :userId
                  AND installation_id = :installationId
                  AND agent_id <> :agentId
                  AND revoked_at IS NULL
                FOR UPDATE
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("installationId", installationId)
                .addValue("agentId", agentId), this::map);
    }

    public void lockUserForUpdate(UUID userId) {
        lockUser(userId);
    }

    @Transactional
    public boolean selectForUser(UUID userId, UUID agentId, OffsetDateTime now) {
        lockUser(userId);
        if (findActiveByUserAndAgent(userId, agentId).isEmpty()) return false;
        jdbc.update("""
                UPDATE local_agent_devices
                SET selected_at = NULL, updated_at = :now
                WHERE user_id = :userId AND selected_at IS NOT NULL
                """, new MapSqlParameterSource().addValue("userId", userId).addValue("now", now));
        return jdbc.update("""
                UPDATE local_agent_devices
                SET selected_at = :now, updated_at = :now
                WHERE user_id = :userId AND agent_id = :agentId AND revoked_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("agentId", agentId)
                .addValue("now", now)) == 1;
    }

    @Transactional
    public Optional<UUID> selectFirstActiveIfNone(UUID userId, OffsetDateTime now) {
        lockUser(userId);
        return selectFirstActiveIfNoneLocked(userId, now);
    }

    @Transactional
    public boolean revoke(UUID userId, UUID agentId, OffsetDateTime now) {
        lockUser(userId);
        boolean revoked = jdbc.update("""
                UPDATE local_agent_devices
                SET revoked_at = COALESCE(revoked_at, :now), selected_at = NULL, updated_at = :now
                WHERE user_id = :userId AND agent_id = :agentId
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("agentId", agentId)
                .addValue("now", now)) == 1;
        if (revoked) selectFirstActiveIfNoneLocked(userId, now);
        return revoked;
    }

    public List<LocalAgentStatusSnapshot> snapshots(UUID userId) {
        return listActiveByUser(userId).stream()
                .filter(device -> device.lastSeenAt() != null)
                .map(this::snapshot)
                .toList();
    }

    private LocalAgentStatusSnapshot snapshot(LocalAgentDevice device) {
        OffsetDateTime connectedAt = device.approvedAt() == null ? device.createdAt() : device.approvedAt();
        return new LocalAgentStatusSnapshot(
                device.agentId(), device.userId(), device.agentVersion(), connectedAt,
                device.lastSeenAt() == null ? connectedAt : device.lastSeenAt(),
                device.capabilities(), device.workspaces(), device.configuredTransport(),
                device.activeTransport(), device.webSocketFailureCount(), device.nextWebSocketRetryAt()
        );
    }

    private LocalAgentDevice map(ResultSet rs, int rowNum) throws SQLException {
        return new LocalAgentDevice(
                rs.getObject("agent_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("installation_id", UUID.class),
                rs.getString("label"),
                rs.getString("client_name"),
                rs.getString("machine_name"),
                rs.getString("os_name"),
                rs.getString("os_version"),
                rs.getString("architecture"),
                rs.getString("agent_version"),
                read(rs.getString("capabilities"), STRING_LIST),
                read(rs.getString("workspaces"), WORKSPACE_LIST),
                rs.getString("configured_transport"),
                rs.getString("active_transport"),
                rs.getInt("websocket_failure_count"),
                rs.getObject("next_websocket_retry_at", OffsetDateTime.class),
                rs.getObject("selected_at", OffsetDateTime.class),
                rs.getObject("approved_at", OffsetDateTime.class),
                rs.getObject("last_seen_at", OffsetDateTime.class),
                rs.getObject("revoked_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private Optional<UUID> selectFirstActiveIfNoneLocked(UUID userId, OffsetDateTime now) {
        Optional<LocalAgentDevice> selected = findSelectedByUser(userId);
        if (selected.isPresent()) return selected.map(LocalAgentDevice::agentId);
        List<UUID> candidates = jdbc.query("""
                SELECT agent_id FROM local_agent_devices
                WHERE user_id = :userId AND revoked_at IS NULL
                ORDER BY created_at ASC, agent_id ASC
                LIMIT 1
                FOR UPDATE
                """, new MapSqlParameterSource("userId", userId),
                (rs, rowNum) -> rs.getObject("agent_id", UUID.class));
        if (candidates.isEmpty()) return Optional.empty();
        UUID selectedAgentId = candidates.get(0);
        jdbc.update("""
                UPDATE local_agent_devices
                SET selected_at = :now, updated_at = :now
                WHERE user_id = :userId AND agent_id = :agentId AND revoked_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("agentId", selectedAgentId)
                .addValue("now", now));
        return Optional.of(selectedAgentId);
    }

    private void lockUser(UUID userId) {
        jdbc.query("""
                SELECT pg_advisory_xact_lock(hashtextextended(CAST(:userId AS text), 0))
                """, new MapSqlParameterSource("userId", userId), rs -> null);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Local Agent status cannot be serialized.", ex);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception ex) {
            throw new IllegalStateException("Stored Local Agent status is invalid.", ex);
        }
    }
}
