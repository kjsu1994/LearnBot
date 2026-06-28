package com.learnbot.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.PatchApplySnapshot;
import com.learnbot.service.CodeAgentPatchSession;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CodeAgentPatchSessionRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CodeAgentPatchSessionRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public CodeAgentPatchSession createApplied(
            UUID repositoryId,
            UUID spaceId,
            UUID userId,
            String instruction,
            String diff,
            List<String> targetFiles,
            List<PatchApplySnapshot> snapshots,
            Map<String, String> afterHashes,
            List<String> warnings
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO code_agent_patch_sessions (
                    id, repository_id, space_id, user_id, instruction, diff, target_files,
                    before_snapshots, after_hashes, status, warnings, applied_at
                )
                VALUES (
                    :id, :repositoryId, :spaceId, :userId, :instruction, :diff, CAST(:targetFiles AS jsonb),
                    CAST(:snapshots AS jsonb), CAST(:afterHashes AS jsonb), 'APPLIED', CAST(:warnings AS jsonb), now()
                )
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("repositoryId", repositoryId)
                .addValue("spaceId", spaceId)
                .addValue("userId", userId)
                .addValue("instruction", instruction)
                .addValue("diff", diff)
                .addValue("targetFiles", toJson(targetFiles))
                .addValue("snapshots", toJson(snapshots))
                .addValue("afterHashes", toJson(afterHashes))
                .addValue("warnings", toJson(warnings)));
        return find(id).orElseThrow();
    }

    public Optional<CodeAgentPatchSession> find(UUID id) {
        List<CodeAgentPatchSession> sessions = jdbc.query("""
                SELECT id, repository_id, space_id, user_id, instruction, diff, target_files::text,
                       before_snapshots::text, after_hashes::text, status, warnings::text, test_results::text
                FROM code_agent_patch_sessions
                WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id), this::mapSession);
        return sessions.stream().findFirst();
    }

    public void markRolledBack(UUID id) {
        jdbc.update("""
                UPDATE code_agent_patch_sessions
                SET status = 'ROLLED_BACK', rolled_back_at = now()
                WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id));
    }

    public void appendTestResult(UUID id, Map<String, Object> result) {
        jdbc.update("""
                UPDATE code_agent_patch_sessions
                SET test_results = test_results || CAST(:result AS jsonb)
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("result", toJson(List.of(result))));
    }

    private CodeAgentPatchSession mapSession(ResultSet rs, int rowNum) throws SQLException {
        return new CodeAgentPatchSession(
                rs.getObject("id", UUID.class),
                rs.getObject("repository_id", UUID.class),
                rs.getObject("space_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("instruction"),
                rs.getString("diff"),
                fromJson(rs.getString("target_files"), new TypeReference<List<String>>() {}),
                fromJson(rs.getString("before_snapshots"), new TypeReference<List<PatchApplySnapshot>>() {}),
                fromJson(rs.getString("after_hashes"), new TypeReference<Map<String, String>>() {}),
                rs.getString("status"),
                fromJson(rs.getString("warnings"), new TypeReference<List<String>>() {}),
                fromJson(rs.getString("test_results"), new TypeReference<List<Map<String, Object>>>() {})
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid patch session JSON.", ex);
        }
    }

    private <T> T fromJson(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value == null || value.isBlank() ? "null" : value, type);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid patch session JSON.", ex);
        }
    }
}
