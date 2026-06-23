package com.learnbot.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.AdminUserSummary;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SecurityRepository {
    public static final UUID DEFAULT_SPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final boolean tokenTypeColumnExists;
    private final boolean rememberLoginColumnExists;

    public SecurityRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.tokenTypeColumnExists = hasTokenTypeColumn();
        this.rememberLoginColumnExists = hasRememberLoginColumn();
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
                  AND deleted_at IS NULL
                """, new MapSqlParameterSource().addValue("email", email), (rs, rowNum) -> rs.getString("password_hash"));
        return hashes.stream().findFirst();
    }

    public Optional<AppUser> findUserByEmail(String email) {
        List<AppUser> users = jdbc.query("""
                SELECT id, email, display_name, role, status
                FROM app_users
                WHERE lower(email) = lower(:email)
                  AND status <> 'DELETED'
                  AND deleted_at IS NULL
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
                WHERE status <> 'DELETED'
                ORDER BY created_at DESC
                """, new MapSqlParameterSource(), (rs, rowNum) -> new UserSummary(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("role"),
                rs.getString("status")
        ));
    }

    public List<AdminUserSummary> listAdminUsers() {
        return listAdminUsers(null, false);
    }

    public List<AdminUserSummary> listAdminUsersForSpaces(List<UUID> spaceIds) {
        if (spaceIds == null || spaceIds.isEmpty()) {
            return List.of();
        }
        return listAdminUsers(spaceIds, true);
    }

    private List<AdminUserSummary> listAdminUsers(List<UUID> spaceIds, boolean scopedUsersOnly) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String scopeClause = "";
        if (scopedUsersOnly) {
            params.addValue("spaceIds", spaceIds);
            scopeClause = """
                    AND u.role = 'USER'
                    AND EXISTS (
                        SELECT 1
                        FROM space_members sm
                        JOIN spaces s ON s.id = sm.space_id
                        WHERE sm.user_id = u.id
                          AND sm.space_id IN (:spaceIds)
                          AND s.deleted_at IS NULL
                    )
                    """;
        }
        List<AdminUserRow> users = jdbc.query("""
                SELECT id, email, display_name, role, status
                FROM app_users u
                WHERE u.status <> 'DELETED'
                %s
                ORDER BY created_at DESC
                """.formatted(scopeClause), params, (rs, rowNum) -> new AdminUserRow(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("role"),
                rs.getString("status")
        ));
        if (users.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<SpaceSummary>> spacesByUser = new LinkedHashMap<>();
        users.forEach(user -> spacesByUser.put(user.id(), new ArrayList<>()));
        List<UUID> userIds = users.stream().map(AdminUserRow::id).toList();
        jdbc.query("""
                SELECT sm.user_id, s.id, s.name, s.description, sm.role, s.created_at
                FROM space_members sm
                JOIN spaces s ON s.id = sm.space_id
                WHERE sm.user_id IN (:userIds)
                  AND s.deleted_at IS NULL
                ORDER BY s.created_at ASC
                """, new MapSqlParameterSource().addValue("userIds", userIds), rs -> {
            while (rs.next()) {
                UUID userId = rs.getObject("user_id", UUID.class);
                List<SpaceSummary> spaces = spacesByUser.get(userId);
                if (spaces != null) {
                    spaces.add(mapSpace(rs, 0));
                }
            }
            return null;
        });

        return users.stream()
                .map(user -> new AdminUserSummary(
                        user.id(),
                        user.email(),
                        user.displayName(),
                        user.role(),
                        user.status(),
                        spacesByUser.getOrDefault(user.id(), List.of())
                ))
                .toList();
    }

    public int countActiveAdmins() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM app_users
                WHERE role IN ('MASTER', 'ADMIN')
                  AND status = 'ACTIVE'
                """, new MapSqlParameterSource(), Integer.class);
        return count == null ? 0 : count;
    }

    public boolean userHasActiveSpace(UUID userId, UUID spaceId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM space_members sm
                JOIN spaces s ON s.id = sm.space_id
                WHERE sm.user_id = :userId
                  AND sm.space_id = :spaceId
                  AND s.deleted_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("spaceId", spaceId), Integer.class);
        return count != null && count > 0;
    }

    public boolean usersShareActiveSpace(UUID actorId, UUID targetId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM space_members actor_sm
                JOIN space_members target_sm ON target_sm.space_id = actor_sm.space_id
                JOIN spaces s ON s.id = actor_sm.space_id
                WHERE actor_sm.user_id = :actorId
                  AND target_sm.user_id = :targetId
                  AND s.deleted_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("actorId", actorId)
                .addValue("targetId", targetId), Integer.class);
        return count != null && count > 0;
    }

    public void updateUser(UUID userId, String loginId, String displayName, String role) {
        jdbc.update("""
                UPDATE app_users
                SET email = :loginId,
                    display_name = :displayName,
                    role = :role,
                    updated_at = now()
                WHERE id = :userId
                  AND status <> 'DELETED'
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("loginId", loginId)
                .addValue("displayName", displayName)
                .addValue("role", role));
    }

    public void updatePasswordHash(UUID userId, String passwordHash) {
        jdbc.update("""
                UPDATE app_users
                SET password_hash = :passwordHash,
                    updated_at = now()
                WHERE id = :userId
                  AND status <> 'DELETED'
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("passwordHash", passwordHash));
    }

    public void deactivateUser(UUID userId) {
        jdbc.update("""
                UPDATE app_users
                SET status = 'DELETED',
                    deleted_at = now(),
                    updated_at = now()
                WHERE id = :userId
                  AND status <> 'DELETED'
                """, new MapSqlParameterSource().addValue("userId", userId));
    }

    public void revokeSessionsForUser(UUID userId) {
        jdbc.update("""
                UPDATE auth_sessions
                SET revoked_at = now()
                WHERE user_id = :userId
                  AND revoked_at IS NULL
                """, new MapSqlParameterSource().addValue("userId", userId));
    }

    public void updateLastLogin(UUID userId) {
        jdbc.update("""
                UPDATE app_users
                SET last_login_at = now(), updated_at = now()
                WHERE id = :userId
                """, new MapSqlParameterSource().addValue("userId", userId));
    }

    public void createSession(UUID sessionId, UUID userId, String tokenHash, OffsetDateTime expiresAt) {
        if (tokenTypeColumnExists) {
            createSession(sessionId, userId, tokenHash, expiresAt, "ACCESS");
            return;
        }
        jdbc.update("""
                INSERT INTO auth_sessions (id, user_id, token_hash, expires_at)
                VALUES (:id, :userId, :tokenHash, :expiresAt)
                """, new MapSqlParameterSource()
                .addValue("id", sessionId)
                .addValue("userId", userId)
                .addValue("tokenHash", tokenHash)
                .addValue("expiresAt", expiresAt));
    }

    public void createSession(UUID sessionId, UUID userId, String tokenHash, OffsetDateTime expiresAt, String tokenType) {
        if (!tokenTypeColumnExists) {
            createSession(sessionId, userId, tokenHash, expiresAt);
            return;
        }
        jdbc.update("""
                INSERT INTO auth_sessions (id, user_id, token_hash, expires_at, token_type)
                VALUES (:id, :userId, :tokenHash, :expiresAt, :tokenType)
                """, new MapSqlParameterSource()
                .addValue("id", sessionId)
                .addValue("userId", userId)
                .addValue("tokenHash", tokenHash)
                .addValue("expiresAt", expiresAt)
                .addValue("tokenType", tokenType));
    }

    public void createSession(UUID sessionId, UUID userId, String tokenHash, OffsetDateTime expiresAt, String tokenType,
                             boolean rememberLogin) {
        if (!tokenTypeColumnExists || !rememberLoginColumnExists) {
            createSession(sessionId, userId, tokenHash, expiresAt, tokenType);
            return;
        }
        jdbc.update("""
                INSERT INTO auth_sessions (id, user_id, token_hash, expires_at, token_type, remember_login)
                VALUES (:id, :userId, :tokenHash, :expiresAt, :tokenType, :rememberLogin)
                """, new MapSqlParameterSource()
                .addValue("id", sessionId)
                .addValue("userId", userId)
                .addValue("tokenHash", tokenHash)
                .addValue("expiresAt", expiresAt)
                .addValue("tokenType", tokenType)
                .addValue("rememberLogin", rememberLogin));
    }

    public Optional<AppUser> findUserBySessionTokenHash(String tokenHash) {
        if (!tokenTypeColumnExists) {
            return findUserByLegacySessionTokenHash(tokenHash);
        }
        List<AppUser> users = jdbc.query("""
                SELECT u.id, u.email, u.display_name, u.role, u.status
                FROM auth_sessions s
                JOIN app_users u ON u.id = s.user_id
                WHERE s.token_hash = :tokenHash
                AND s.revoked_at IS NULL
                  AND s.expires_at > now()
                  AND u.status = 'ACTIVE'
                  AND (s.token_type = 'ACCESS' OR s.token_type IS NULL)
                """, new MapSqlParameterSource().addValue("tokenHash", tokenHash), this::mapUser);
        return users.stream().findFirst();
    }

    public Optional<AppUser> findUserByRefreshTokenHash(String tokenHash) {
        if (!tokenTypeColumnExists) {
            return Optional.empty();
        }
        List<AppUser> users = jdbc.query("""
                SELECT u.id, u.email, u.display_name, u.role, u.status
                FROM auth_sessions s
                JOIN app_users u ON u.id = s.user_id
                WHERE s.token_hash = :tokenHash
                  AND s.revoked_at IS NULL
                  AND s.expires_at > now()
                  AND u.status = 'ACTIVE'
                  AND s.token_type = 'REFRESH'
                """, new MapSqlParameterSource().addValue("tokenHash", tokenHash), this::mapUser);
        return users.stream().findFirst();
    }

    public Optional<RefreshSession> findRefreshSession(String tokenHash) {
        if (!tokenTypeColumnExists) {
            return Optional.empty();
        }
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("tokenHash", tokenHash);
        List<RefreshSession> sessions;
        if (rememberLoginColumnExists) {
            sessions = jdbc.query("""
                    SELECT u.id, u.email, u.display_name, u.role, u.status, s.expires_at, s.remember_login
                    FROM auth_sessions s
                    JOIN app_users u ON u.id = s.user_id
                    WHERE s.token_hash = :tokenHash
                      AND s.revoked_at IS NULL
                      AND s.expires_at > now()
                      AND u.status = 'ACTIVE'
                      AND s.token_type = 'REFRESH'
                    """, params, this::mapRefreshSession);
        } else {
            sessions = jdbc.query("""
                    SELECT u.id, u.email, u.display_name, u.role, u.status, s.expires_at
                    FROM auth_sessions s
                    JOIN app_users u ON u.id = s.user_id
                    WHERE s.token_hash = :tokenHash
                      AND s.revoked_at IS NULL
                      AND s.expires_at > now()
                      AND u.status = 'ACTIVE'
                      AND s.token_type = 'REFRESH'
                    """, params, (rs, rowNum) -> {
                AppUser user = mapUser(rs, rowNum);
                OffsetDateTime refreshExpiresAt = rs.getObject("expires_at", OffsetDateTime.class);
                return new RefreshSession(user, refreshExpiresAt, inferRememberLoginFromRefreshExpiry(refreshExpiresAt));
            });
        }
        return sessions.stream().findFirst();
    }

    public boolean supportsRefreshSessions() {
        return tokenTypeColumnExists;
    }

    private boolean hasTokenTypeColumn() {
        try {
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'auth_sessions'
                      AND column_name = 'token_type'
                    """, new MapSqlParameterSource(), Integer.class);
            return count != null && count > 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean hasRememberLoginColumn() {
        try {
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'auth_sessions'
                      AND column_name = 'remember_login'
                    """, new MapSqlParameterSource(), Integer.class);
            return count != null && count > 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private Optional<AppUser> findUserByLegacySessionTokenHash(String tokenHash) {
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

    public Optional<SpaceSummary> findSpace(UUID spaceId) {
        List<SpaceSummary> spaces = jdbc.query("""
                SELECT s.id, s.name, s.description, 'ADMIN' AS role, s.created_at
                FROM spaces s
                WHERE s.id = :spaceId
                  AND s.deleted_at IS NULL
                """, new MapSqlParameterSource().addValue("spaceId", spaceId), this::mapSpace);
        return spaces.stream().findFirst();
    }

    public void updateSpace(UUID spaceId, String name, String description) {
        jdbc.update("""
                UPDATE spaces
                SET name = :name,
                    description = :description,
                    updated_at = now()
                WHERE id = :spaceId
                  AND deleted_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("spaceId", spaceId)
                .addValue("name", name)
                .addValue("description", description));
    }

    public void deleteSpace(UUID spaceId) {
        jdbc.update("""
                UPDATE spaces
                SET deleted_at = now(),
                    updated_at = now()
                WHERE id = :spaceId
                  AND deleted_at IS NULL
                """, new MapSqlParameterSource().addValue("spaceId", spaceId));
    }

    public int purgeDeletedUsersOlderThan(OffsetDateTime cutoff) {
        return jdbc.update("""
                DELETE FROM app_users
                WHERE status = 'DELETED'
                  AND deleted_at IS NOT NULL
                  AND deleted_at < :cutoff
                """, new MapSqlParameterSource().addValue("cutoff", cutoff));
    }

    public int purgeDeletedSpacesOlderThan(OffsetDateTime cutoff) {
        jdbc.update("""
                DELETE FROM data_sources
                WHERE space_id IN (
                    SELECT id
                    FROM spaces
                    WHERE deleted_at IS NOT NULL
                      AND deleted_at < :cutoff
                )
                """, new MapSqlParameterSource().addValue("cutoff", cutoff));
        jdbc.update("""
                DELETE FROM code_repositories
                WHERE space_id IN (
                    SELECT id
                    FROM spaces
                    WHERE deleted_at IS NOT NULL
                      AND deleted_at < :cutoff
                )
                """, new MapSqlParameterSource().addValue("cutoff", cutoff));
        return jdbc.update("""
                DELETE FROM spaces
                WHERE deleted_at IS NOT NULL
                  AND deleted_at < :cutoff
                """, new MapSqlParameterSource().addValue("cutoff", cutoff));
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

    public Optional<String> findSpaceMemberRole(UUID spaceId, UUID userId) {
        List<String> roles = jdbc.query("""
                SELECT role
                FROM space_members
                WHERE space_id = :spaceId
                  AND user_id = :userId
                """, new MapSqlParameterSource()
                .addValue("spaceId", spaceId)
                .addValue("userId", userId), (rs, rowNum) -> rs.getString("role"));
        return roles.stream().findFirst();
    }

    public void removeSpaceMember(UUID spaceId, UUID userId) {
        jdbc.update("""
                DELETE FROM space_members
                WHERE space_id = :spaceId
                  AND user_id = :userId
                """, new MapSqlParameterSource()
                .addValue("spaceId", spaceId)
                .addValue("userId", userId));
    }

    public int countSpaceMemberships(UUID userId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM space_members sm
                JOIN spaces s ON s.id = sm.space_id
                WHERE sm.user_id = :userId
                  AND s.deleted_at IS NULL
                """, new MapSqlParameterSource().addValue("userId", userId), Integer.class);
        return count == null ? 0 : count;
    }

    public List<SpaceSummary> listSpacesForUser(AppUser user) {
        return jdbc.query("""
                SELECT s.id, s.name, s.description, sm.role, s.created_at
                FROM space_members sm
                JOIN spaces s ON s.id = sm.space_id
                WHERE sm.user_id = :userId
                  AND s.deleted_at IS NULL
                ORDER BY s.created_at ASC
                """, new MapSqlParameterSource().addValue("userId", user.id()), this::mapSpace);
    }

    public List<SpaceSummary> listAllSpaces() {
        return jdbc.query("""
                SELECT s.id, s.name, s.description, 'ADMIN' AS role, s.created_at
                FROM spaces s
                WHERE s.deleted_at IS NULL
                ORDER BY s.created_at ASC
                """, new MapSqlParameterSource(), this::mapSpace);
    }

    public List<UUID> accessibleSpaceIds(AppUser user) {
        return jdbc.query("""
                SELECT space_id
                FROM space_members sm
                JOIN spaces s ON s.id = sm.space_id
                WHERE sm.user_id = :userId
                  AND s.deleted_at IS NULL
                ORDER BY s.created_at ASC
                """, new MapSqlParameterSource().addValue("userId", user.id()), (rs, rowNum) -> rs.getObject("space_id", UUID.class));
    }

    public boolean canAccessSpace(AppUser user, UUID spaceId) {
        if (spaceId == null) {
            return false;
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
                """, new MapSqlParameterSource().addValue("limit", limit == null ? 50 : Math.max(1, Math.min(limit, 50))), (rs, rowNum) ->
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

    private record AdminUserRow(UUID id, String email, String displayName, String role, String status) {
    }

    public record RefreshSession(AppUser user, OffsetDateTime refreshExpiresAt, boolean rememberLogin) {
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

    private RefreshSession mapRefreshSession(ResultSet rs, int rowNum) throws SQLException {
        return new RefreshSession(
                mapUser(rs, rowNum),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getBoolean("remember_login")
        );
    }

    private boolean inferRememberLoginFromRefreshExpiry(OffsetDateTime refreshExpiresAt) {
        if (refreshExpiresAt == null) {
            return false;
        }
        return refreshExpiresAt.isAfter(OffsetDateTime.now().plusDays(2));
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
