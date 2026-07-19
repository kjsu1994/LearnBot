package com.learnbot.repository;

import com.learnbot.service.LocalAgentEnrollment;
import com.learnbot.service.LocalAgentEnrollmentState;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class LocalAgentEnrollmentRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public LocalAgentEnrollmentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(LocalAgentEnrollment enrollment, String deviceCodeHash, String userCodeHash) {
        jdbc.update("""
                INSERT INTO local_agent_enrollments (
                    id, agent_id, device_code_hash, user_code_hash, state, label, client_name,
                    machine_name, os_name, os_version, architecture, agent_version,
                    installation_id, poll_interval_seconds, expires_at, created_at, updated_at
                ) VALUES (
                    :id, :agentId, :deviceCodeHash, :userCodeHash, :state, :label, :clientName,
                    :machineName, :osName, :osVersion, :architecture, :agentVersion,
                    :installationId, :pollIntervalSeconds, :expiresAt, :createdAt, :createdAt
                )
                """, params(enrollment)
                .addValue("deviceCodeHash", deviceCodeHash)
                .addValue("userCodeHash", userCodeHash));
    }

    public Optional<LocalAgentEnrollment> findByDeviceCodeHashForUpdate(String deviceCodeHash) {
        return one("""
                SELECT * FROM local_agent_enrollments
                WHERE device_code_hash = :deviceCodeHash
                FOR UPDATE
                """, new MapSqlParameterSource("deviceCodeHash", deviceCodeHash));
    }

    public Optional<LocalAgentEnrollment> findByUserCodeHash(String userCodeHash) {
        return one("""
                SELECT * FROM local_agent_enrollments
                WHERE user_code_hash = :userCodeHash
                """, new MapSqlParameterSource("userCodeHash", userCodeHash));
    }

    public Optional<LocalAgentEnrollment> findByIdForUpdate(UUID id) {
        return one("""
                SELECT * FROM local_agent_enrollments
                WHERE id = :id
                FOR UPDATE
                """, new MapSqlParameterSource("id", id));
    }

    public Optional<LocalAgentEnrollment> findCandidateForUpdate(UUID id, String candidateTokenHash) {
        return one("""
                SELECT * FROM local_agent_enrollments
                WHERE id = :id AND candidate_token_hash = :candidateTokenHash
                FOR UPDATE
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("candidateTokenHash", candidateTokenHash));
    }

    public boolean approve(UUID id, UUID userId, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE local_agent_enrollments
                SET state = 'APPROVED', user_id = :userId, approved_at = :now, updated_at = :now
                WHERE id = :id AND state = 'PENDING' AND expires_at > :now
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId)
                .addValue("now", now)) == 1;
    }

    public boolean deny(UUID id, UUID userId, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE local_agent_enrollments
                SET state = 'DENIED', user_id = :userId, denied_at = :now, updated_at = :now
                WHERE id = :id AND state = 'PENDING' AND expires_at > :now
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId)
                .addValue("now", now)) == 1;
    }

    public void expire(UUID id, OffsetDateTime now) {
        jdbc.update("""
                UPDATE local_agent_enrollments
                SET state = 'EXPIRED', updated_at = :now
                WHERE id = :id AND state IN ('PENDING', 'APPROVED')
                """, new MapSqlParameterSource().addValue("id", id).addValue("now", now));
    }

    public void recordPoll(UUID id, OffsetDateTime now) {
        jdbc.update("""
                UPDATE local_agent_enrollments
                SET last_polled_at = :now, updated_at = :now
                WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id).addValue("now", now));
    }

    public int recordSlowPoll(UUID id, OffsetDateTime now) {
        return jdbc.queryForObject("""
                UPDATE local_agent_enrollments
                SET last_polled_at = :now,
                    poll_violation_count = poll_violation_count + 1,
                    poll_interval_seconds = LEAST(poll_interval_seconds + 5, 60),
                    updated_at = :now
                WHERE id = :id
                RETURNING poll_interval_seconds
                """, new MapSqlParameterSource().addValue("id", id).addValue("now", now), Integer.class);
    }

    public boolean consume(UUID id, UUID userId, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE local_agent_enrollments
                SET state = 'CONSUMED', consumed_at = :now, updated_at = :now
                WHERE id = :id AND user_id = :userId AND state = 'APPROVED' AND expires_at > :now
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId)
                .addValue("now", now)) == 1;
    }

    public boolean stageCandidate(
            UUID id,
            UUID candidateTokenId,
            String candidateTokenHash,
            OffsetDateTime candidateExpiresAt,
            OffsetDateTime confirmBy,
            OffsetDateTime now
    ) {
        return jdbc.update("""
                UPDATE local_agent_enrollments
                SET candidate_token_id = :candidateTokenId,
                    candidate_token_hash = :candidateTokenHash,
                    candidate_expires_at = :candidateExpiresAt,
                    credential_confirm_by = :confirmBy,
                    credential_issued_at = :now,
                    updated_at = :now
                WHERE id = :id AND state = 'APPROVED' AND expires_at > :now
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("candidateTokenId", candidateTokenId)
                .addValue("candidateTokenHash", candidateTokenHash)
                .addValue("candidateExpiresAt", candidateExpiresAt)
                .addValue("confirmBy", confirmBy)
                .addValue("now", now)) == 1;
    }

    public RateLimitResult consumeRateLimit(
            String scope,
            String keyHash,
            OffsetDateTime now,
            OffsetDateTime resetBefore
    ) {
        return jdbc.queryForObject("""
                INSERT INTO local_agent_rate_limits (scope, key_hash, window_started_at, attempt_count)
                VALUES (:scope, :keyHash, :now, 1)
                ON CONFLICT (scope, key_hash) DO UPDATE SET
                    window_started_at = CASE
                        WHEN local_agent_rate_limits.window_started_at <= :resetBefore THEN :now
                        ELSE local_agent_rate_limits.window_started_at
                    END,
                    attempt_count = CASE
                        WHEN local_agent_rate_limits.window_started_at <= :resetBefore THEN 1
                        ELSE local_agent_rate_limits.attempt_count + 1
                    END
                RETURNING window_started_at, attempt_count
                """, new MapSqlParameterSource()
                .addValue("scope", scope)
                .addValue("keyHash", keyHash)
                .addValue("now", now)
                .addValue("resetBefore", resetBefore),
                (rs, rowNum) -> new RateLimitResult(
                        rs.getObject("window_started_at", OffsetDateTime.class),
                        rs.getInt("attempt_count")
                ));
    }

    public int cleanupRateLimits(OffsetDateTime olderThan) {
        return jdbc.update("""
                DELETE FROM local_agent_rate_limits
                WHERE window_started_at < :olderThan
                """, new MapSqlParameterSource("olderThan", olderThan));
    }

    private Optional<LocalAgentEnrollment> one(String sql, MapSqlParameterSource parameters) {
        List<LocalAgentEnrollment> rows = jdbc.query(sql, parameters, this::map);
        return rows.stream().findFirst();
    }

    private MapSqlParameterSource params(LocalAgentEnrollment value) {
        return new MapSqlParameterSource()
                .addValue("id", value.id())
                .addValue("agentId", value.agentId())
                .addValue("state", value.state().name())
                .addValue("label", value.label())
                .addValue("clientName", value.clientName())
                .addValue("machineName", value.machineName())
                .addValue("osName", value.osName())
                .addValue("osVersion", value.osVersion())
                .addValue("architecture", value.architecture())
                .addValue("agentVersion", value.agentVersion())
                .addValue("installationId", value.installationId())
                .addValue("pollIntervalSeconds", value.pollIntervalSeconds())
                .addValue("expiresAt", value.expiresAt())
                .addValue("createdAt", value.createdAt());
    }

    private LocalAgentEnrollment map(ResultSet rs, int rowNum) throws SQLException {
        return new LocalAgentEnrollment(
                rs.getObject("id", UUID.class),
                rs.getObject("agent_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                LocalAgentEnrollmentState.valueOf(rs.getString("state")),
                rs.getString("label"),
                rs.getString("client_name"),
                rs.getString("machine_name"),
                rs.getString("os_name"),
                rs.getString("os_version"),
                rs.getString("architecture"),
                rs.getString("agent_version"),
                rs.getObject("installation_id", UUID.class),
                rs.getInt("poll_interval_seconds"),
                rs.getInt("poll_violation_count"),
                rs.getObject("last_polled_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("approved_at", OffsetDateTime.class),
                rs.getObject("denied_at", OffsetDateTime.class),
                rs.getObject("consumed_at", OffsetDateTime.class),
                rs.getObject("candidate_token_id", UUID.class),
                rs.getObject("candidate_expires_at", OffsetDateTime.class),
                rs.getObject("credential_confirm_by", OffsetDateTime.class),
                rs.getObject("credential_issued_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    public record RateLimitResult(OffsetDateTime windowStartedAt, int attemptCount) {
    }
}
