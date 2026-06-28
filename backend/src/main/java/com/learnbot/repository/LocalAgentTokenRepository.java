package com.learnbot.repository;

import com.learnbot.service.LocalAgentToken;
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
public class LocalAgentTokenRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public LocalAgentTokenRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(UUID id, UUID userId, UUID agentId, String label, String tokenHash, OffsetDateTime expiresAt) {
        jdbc.update("""
                INSERT INTO local_agent_tokens (id, user_id, agent_id, label, token_hash, expires_at)
                VALUES (:id, :userId, :agentId, :label, :tokenHash, :expiresAt)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId)
                .addValue("agentId", agentId)
                .addValue("label", label)
                .addValue("tokenHash", tokenHash)
                .addValue("expiresAt", expiresAt));
    }

    public Optional<LocalAgentToken> findByTokenHash(String tokenHash) {
        List<LocalAgentToken> tokens = jdbc.query("""
                SELECT id, user_id, agent_id, label, expires_at, revoked_at, last_seen_at, created_at
                FROM local_agent_tokens
                WHERE token_hash = :tokenHash
                """, new MapSqlParameterSource().addValue("tokenHash", tokenHash), this::mapToken);
        return tokens.stream().findFirst();
    }

    public List<LocalAgentToken> listByUser(UUID userId) {
        return jdbc.query("""
                SELECT id, user_id, agent_id, label, expires_at, revoked_at, last_seen_at, created_at
                FROM local_agent_tokens
                WHERE user_id = :userId
                ORDER BY created_at DESC
                """, new MapSqlParameterSource().addValue("userId", userId), this::mapToken);
    }

    public boolean revokeForUser(UUID userId, UUID id) {
        int updated = jdbc.update("""
                UPDATE local_agent_tokens
                SET revoked_at = COALESCE(revoked_at, now())
                WHERE id = :id
                  AND user_id = :userId
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId));
        return updated > 0;
    }

    public void markSeen(UUID id) {
        jdbc.update("""
                UPDATE local_agent_tokens
                SET last_seen_at = now()
                WHERE id = :id
                  AND revoked_at IS NULL
                """, new MapSqlParameterSource().addValue("id", id));
    }

    private LocalAgentToken mapToken(ResultSet rs, int rowNum) throws SQLException {
        return new LocalAgentToken(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("agent_id", UUID.class),
                rs.getString("label"),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("revoked_at", OffsetDateTime.class),
                rs.getObject("last_seen_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }
}
