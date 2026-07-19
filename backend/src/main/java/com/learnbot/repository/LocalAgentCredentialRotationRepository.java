package com.learnbot.repository;

import com.learnbot.service.LocalAgentCredentialRotation;
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
public class LocalAgentCredentialRotationRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public LocalAgentCredentialRotationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void cancelPending(UUID userId, UUID agentId, OffsetDateTime now) {
        jdbc.update("""
                UPDATE local_agent_credential_rotations
                SET cancelled_at = :now
                WHERE user_id = :userId AND agent_id = :agentId
                  AND confirmed_at IS NULL AND cancelled_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("agentId", agentId)
                .addValue("now", now));
    }

    public void create(LocalAgentCredentialRotation rotation, String candidateTokenHash) {
        jdbc.update("""
                INSERT INTO local_agent_credential_rotations (
                    id, user_id, agent_id, current_token_id, candidate_token_id,
                    candidate_token_hash, candidate_expires_at, confirm_by, created_at
                ) VALUES (
                    :id, :userId, :agentId, :currentTokenId, :candidateTokenId,
                    :candidateTokenHash, :candidateExpiresAt, :confirmBy, :createdAt
                )
                """, new MapSqlParameterSource()
                .addValue("id", rotation.id())
                .addValue("userId", rotation.userId())
                .addValue("agentId", rotation.agentId())
                .addValue("currentTokenId", rotation.currentTokenId())
                .addValue("candidateTokenId", rotation.candidateTokenId())
                .addValue("candidateTokenHash", candidateTokenHash)
                .addValue("candidateExpiresAt", rotation.candidateExpiresAt())
                .addValue("confirmBy", rotation.confirmBy())
                .addValue("createdAt", rotation.createdAt()));
    }

    public Optional<LocalAgentCredentialRotation> findCandidateForUpdate(UUID id, String candidateTokenHash) {
        List<LocalAgentCredentialRotation> rows = jdbc.query("""
                SELECT * FROM local_agent_credential_rotations
                WHERE id = :id AND candidate_token_hash = :candidateTokenHash
                FOR UPDATE
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("candidateTokenHash", candidateTokenHash), this::map);
        return rows.stream().findFirst();
    }

    public boolean confirm(UUID id, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE local_agent_credential_rotations
                SET confirmed_at = :now
                WHERE id = :id AND confirmed_at IS NULL AND cancelled_at IS NULL AND confirm_by > :now
                """, new MapSqlParameterSource().addValue("id", id).addValue("now", now)) == 1;
    }

    public void cancel(UUID id, OffsetDateTime now) {
        jdbc.update("""
                UPDATE local_agent_credential_rotations
                SET cancelled_at = COALESCE(cancelled_at, :now)
                WHERE id = :id AND confirmed_at IS NULL
                """, new MapSqlParameterSource().addValue("id", id).addValue("now", now));
    }

    private LocalAgentCredentialRotation map(ResultSet rs, int rowNum) throws SQLException {
        return new LocalAgentCredentialRotation(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("agent_id", UUID.class),
                rs.getObject("current_token_id", UUID.class),
                rs.getObject("candidate_token_id", UUID.class),
                rs.getObject("candidate_expires_at", OffsetDateTime.class),
                rs.getObject("confirm_by", OffsetDateTime.class),
                rs.getObject("confirmed_at", OffsetDateTime.class),
                rs.getObject("cancelled_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }
}
