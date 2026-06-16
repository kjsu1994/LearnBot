package com.learnbot.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.AuditLogSummary;
import com.learnbot.dto.SpaceSummary;
import com.learnbot.dto.UserSummary;
import com.learnbot.service.AppUser;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SecurityRepository {
    public static final UUID DEFAULT_SPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SecurityRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public int countUsers() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM app_users", new MapSqlParameterSource(), Integer.class);
        return count == null ? 0 : count;
    }

    public AppUser createUser(String email, String passwordHash, String displayName, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_users (id, email, password_hash, display_name, role, status)
                VALUES (:id, :email, :passwordHash, :displayName, :role, 'ACTIVE')
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("email", email)
                .addValue("passwordHash", passwordHash)
                .addValue("displayName", displayName)
                .addValue("role", role));
        return findUserById(id).orElseThrow();
    }

    public Optional<String> findPasswordHashByEmail(String email) {
        List<String> hashes = jdbc.query("""
                SELECT password_hash
                FROM app_users
                WHERE lower(email) = lower(:email)
                  AND status = 'ACTIVE'
                """, new MapSqlParameterSource().addValue("email", email), (rs, rowNum) -> rs.getString("password_hash"));
        return hashes.stream().findFirst();
    }

    public Optional<AppUser> findUserByEmail(String email) {
        List<AppUser> users = jdbc.query("""
                SELECT id, email, display_name, role, status
                FROM app_users
                WHERE lower(email) = lower(:email)
                """, new MapSqlParameterSource().addValue("email", email), this::mapUser);
        return users.stream().findFirst();
    }

    public Optional<AppUser> findUserById(UUID userId) {
        List<AppUser> users = jdbc.query("""
                SELECT id, email, display_name, role, status
                FROM app_users
                WHERE id = :userId
                """, new MapSqlParameterSource().addValue("userId", userId), this::mapUser);
        return users.stream().findFirst();
    }

    public List<UserSummary> listUsers() {
        return jdbc.query("""
                SELECT id, email, display_name, role, status
                FROM app_users
                ORDER BY created_at DESC
                """, new MapSqlParameterSource(), (rs, rowNum) -> new UserSummary(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("role"),
                rs.getString("status")
        ));
    }

    public void updateLastLogin(UUID userId) {
        jdbc.update("""
                UPDATE app_users
                SET last_login_at = now(), updated_at = now()
                WHERE id = :userId
                """, new MapSqlParameterSource().addValue("userId", userId));
    }

    public void createSession(UUID sessionId, UUID userId, String tokenHash, OffsetDateTime expiresAt) {
        jdbc.update("""
                INSERT INTO auth_sessions (id, user_id, token_hash, expires_at)
                VALUES (:id, :userId, :tokenHash, :expiresAt)
                """, new MapSqlParameterSource()
                .addValue("id", sessionId)
                .addValue("userId", userId)
                .addValue("tokenHash", tokenHash)
                .addValue("expiresAt", expiresAt));
    }

    public Optional<AppUser> findUserBySessionTokenHash(String tokenHash) {
        List<AppUser> users = jdbc.query("""
                SELECT u.id, u.email, u.display_name, u.role, u.status
                FROM auth_sessions s
                JOIN app_users u ON u.id = s.user_id
                WHERE s.token_hash = :tokenHash
                  AND s.revoked_at IS NULL
                  AND s.expires_at > now()
                  AND u.status = 'ACTIVE'
                """, new MapSqlParameterSource().addValue("tokenHash", tokenHash), this::mapUser);
        return users.stream().findFirst();
    }

    public void revokeSession(String tokenHash) {
        jdbc.update("""
                UPDATE auth_sessions
                SET revoked_at = now()
                WHERE token_hash = :tokenHash
                  AND revoked_at IS NULL
                """, new MapSqlParameterSource().addValue("tokenHash", tokenHash));
    }

    public UUID createSpace(String name, String description, UUID createdBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO spaces (id, name, description, created_by)
                VALUES (:id, :name, :description, :createdBy)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", name)
                .addValue("description", description)
                .addValue("createdBy", createdBy));
        return id;
    }

    public void addSpaceMember(UUID spaceId, UUID userId, String role) {
        jdbc.update("""
                INSERT INTO space_members (space_id, user_id, role)
                VALUES (:spaceId, :userId, :role)
                ON CONFLICT (space_id, user_id) DO UPDATE
                SET role = EXCLUDED.role
                """, new MapSqlParameterSource()
                .addValue("spaceId", spaceId)
                .addValue("userId", userId)
                .addValue("role", role));
    }

    public List<SpaceSummary> listSpacesForUser(AppUser user) {
        if (user.isAdmin()) {
            return jdbc.query("""
                    SELECT s.id, s.name, s.description, COALESCE(sm.role, 'ADMIN') AS role, s.created_at
                    FROM spaces s
                    LEFT JOIN space_members sm ON sm.space_id = s.id AND sm.user_id = :userId
                    WHERE s.deleted_at IS NULL
                    ORDER BY s.created_at ASC
                    """, new MapSqlParameterSource().addValue("userId", user.id()), this::mapSpace);
        }
        return jdbc.query("""
                SELECT s.id, s.name, s.description, sm.role, s.created_at
                FROM space_members sm
                JOIN spaces s ON s.id = sm.space_id
                WHERE sm.user_id = :userId
                  AND s.deleted_at IS NULL
                ORDER BY s.created_at ASC
                """, new MapSqlParameterSource().addValue("userId", user.id()), this::mapSpace);
    }

    public List<UUID> accessibleSpaceIds(AppUser user) {
        if (user.isAdmin()) {
            return jdbc.query("""
                    SELECT id
                    FROM spaces
                    WHERE deleted_at IS NULL
                    ORDER BY created_at ASC
                    """, new MapSqlParameterSource(), (rs, rowNum) -> rs.getObject("id", UUID.class));
        }
        return jdbc.query("""
                SELECT space_id
                FROM space_members
                WHERE user_id = :userId
                """, new MapSqlParameterSource().addValue("userId", user.id()), (rs, rowNum) -> rs.getObject("space_id", UUID.class));
    }

    public boolean canAccessSpace(AppUser user, UUID spaceId) {
        if (spaceId == null) {
            return false;
        }
        if (user.isAdmin()) {
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM spaces
                    WHERE id = :spaceId AND deleted_at IS NULL
                    """, new MapSqlParameterSource().addValue("spaceId", spaceId), Integer.class);
            return count != null && count > 0;
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM space_members sm
                JOIN spaces s ON s.id = sm.space_id
                WHERE sm.user_id = :userId
                  AND sm.space_id = :spaceId
                  AND s.deleted_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("userId", user.id())
                .addValue("spaceId", spaceId), Integer.class);
        return count != null && count > 0;
    }

    public void createAuditLog(UUID actorUserId, String action, String targetType, String targetId, UUID spaceId, String message, Map<String, Object> metadata) {
        jdbc.update("""
                INSERT INTO audit_logs (id, actor_user_id, action, target_type, target_id, space_id, message, metadata)
                VALUES (:id, :actorUserId, :action, :targetType, :targetId, :spaceId, :message, CAST(:metadata AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("actorUserId", actorUserId)
                .addValue("action", action)
                .addValue("targetType", targetType)
                .addValue("targetId", targetId)
                .addValue("spaceId", spaceId)
                .addValue("message", message)
                .addValue("metadata", toJson(metadata)));
    }

    public List<AuditLogSummary> listAuditLogs(Integer limit) {
        return jdbc.query("""
                SELECT a.id, a.actor_user_id, u.email AS actor_email, a.action, a.target_type, a.target_id,
                       a.space_id, s.name AS space_name, a.message, a.metadata, a.created_at
                FROM audit_logs a
                LEFT JOIN app_users u ON u.id = a.actor_user_id
                LEFT JOIN spaces s ON s.id = a.space_id
                ORDER BY a.created_at DESC
                LIMIT :limit
                """, new MapSqlParameterSource().addValue("limit", limit == null ? 100 : Math.max(1, Math.min(limit, 300))), (rs, rowNum) ->
                new AuditLogSummary(
                        rs.getObject("id", UUID.class),
                        rs.getObject("actor_user_id", UUID.class),
                        rs.getString("actor_email"),
                        rs.getString("action"),
                        rs.getString("target_type"),
                        rs.getString("target_id"),
                        rs.getObject("space_id", UUID.class),
                        rs.getString("space_name"),
                        rs.getString("message"),
                        fromJson(rs.getString("metadata")),
                        rs.getObject("created_at", OffsetDateTime.class)
                ));
    }

    private AppUser mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new AppUser(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("role"),
                rs.getString("status")
        );
    }

    private SpaceSummary mapSpace(ResultSet rs, int rowNum) throws SQLException {
        return new SpaceSummary(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("role"),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid metadata.", ex);
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }
}
