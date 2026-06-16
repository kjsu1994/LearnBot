package com.learnbot.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.CodeChunkSummary;
import com.learnbot.dto.CodeFileSummary;
import com.learnbot.dto.CodeRepositorySummary;
import com.learnbot.dto.CodeSearchResult;
import com.learnbot.dto.IndexingJobSummary;
import com.learnbot.service.CodeFileRecord;
import com.learnbot.service.CodeRepositoryRecord;
import com.learnbot.service.ParsedCodeChunk;
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
public class CodeRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CodeRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public CodeRepositoryRecord createRepository(String name, String gitUrl, String branch, String authType, String localPath) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO code_repositories (id, name, git_url, branch, auth_type, local_path, status)
                VALUES (:id, :name, :gitUrl, :branch, :authType, :localPath, 'PENDING')
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", name)
                .addValue("gitUrl", gitUrl)
                .addValue("branch", branch)
                .addValue("authType", authType)
                .addValue("localPath", localPath));
        return findRepository(id).orElseThrow();
    }

    public List<CodeRepositorySummary> listRepositories() {
        return jdbc.query("""
                SELECT r.id, r.name, r.git_url, r.branch, r.auth_type, r.status, r.last_indexed_commit,
                       r.error_message, r.created_at, r.updated_at,
                       COALESCE(f.active_file_count, 0) AS active_file_count,
                       COALESCE(c.active_chunk_count, 0) AS active_chunk_count
                FROM code_repositories r
                LEFT JOIN (
                    SELECT repository_id, COUNT(*) AS active_file_count
                    FROM code_files
                    WHERE active
                    GROUP BY repository_id
                ) f ON f.repository_id = r.id
                LEFT JOIN (
                    SELECT repository_id, COUNT(*) AS active_chunk_count
                    FROM code_chunks
                    WHERE active
                    GROUP BY repository_id
                ) c ON c.repository_id = r.id
                ORDER BY r.created_at DESC
                """, this::mapRepositorySummary);
    }

    public Optional<CodeRepositoryRecord> findRepository(UUID repositoryId) {
        List<CodeRepositoryRecord> repositories = jdbc.query("""
                SELECT id, name, git_url, branch, auth_type, local_path, status, last_indexed_commit
                FROM code_repositories
                WHERE id = :repositoryId
                """, new MapSqlParameterSource().addValue("repositoryId", repositoryId), this::mapRepositoryRecord);
        return repositories.stream().findFirst();
    }

    public void updateRepositoryStatus(UUID repositoryId, String status, String errorMessage) {
        jdbc.update("""
                UPDATE code_repositories
                SET status = :status,
                    error_message = :errorMessage,
                    updated_at = now()
                WHERE id = :repositoryId
                """, new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("status", status)
                .addValue("errorMessage", errorMessage));
    }

    public void resetInterruptedJobs() {
        jdbc.update("""
                UPDATE indexing_jobs
                SET status = 'FAILED',
                    error_message = '서버 재시작으로 인덱싱 작업이 중단되었습니다. 다시 인덱싱하세요.',
                    finished_at = now()
                WHERE status IN ('RUNNING', 'CANCELLING')
                """, new MapSqlParameterSource());
        jdbc.update("""
                UPDATE code_repositories
                SET status = CASE
                        WHEN last_indexed_commit IS NULL THEN 'PENDING'
                        ELSE 'INDEXED'
                    END,
                    error_message = '서버 재시작으로 이전 인덱싱 작업이 중단되었습니다.',
                    updated_at = now()
                WHERE status = 'INDEXING'
                """, new MapSqlParameterSource());
    }

    public void markRepositoryIndexed(UUID repositoryId, String commitHash) {
        jdbc.update("""
                UPDATE code_repositories
                SET status = 'INDEXED',
                    last_indexed_commit = :commitHash,
                    error_message = NULL,
                    updated_at = now()
                WHERE id = :repositoryId
                """, new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("commitHash", commitHash));
    }

    public UUID createJob(UUID repositoryId, String jobType) {
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO indexing_jobs (id, repository_id, job_type, status, started_at)
                VALUES (:id, :repositoryId, :jobType, 'RUNNING', now())
                """, new MapSqlParameterSource()
                .addValue("id", jobId)
                .addValue("repositoryId", repositoryId)
                .addValue("jobType", jobType));
        return jobId;
    }

    public void updateJobProgress(UUID jobId, int totalFiles, int processedFiles, int totalChunks, int failedFiles) {
        jdbc.update("""
                UPDATE indexing_jobs
                SET total_files = :totalFiles,
                    processed_files = :processedFiles,
                    total_chunks = :totalChunks,
                    failed_files = :failedFiles
                WHERE id = :jobId
                """, new MapSqlParameterSource()
                .addValue("jobId", jobId)
                .addValue("totalFiles", totalFiles)
                .addValue("processedFiles", processedFiles)
                .addValue("totalChunks", totalChunks)
                .addValue("failedFiles", failedFiles));
    }

    public void finishJob(UUID jobId, String status, String commitHash, String errorMessage) {
        jdbc.update("""
                UPDATE indexing_jobs
                SET status = :status,
                    commit_hash = :commitHash,
                    error_message = :errorMessage,
                    finished_at = now()
                WHERE id = :jobId
                """, new MapSqlParameterSource()
                .addValue("jobId", jobId)
                .addValue("status", status)
                .addValue("commitHash", commitHash)
                .addValue("errorMessage", errorMessage));
    }

    public List<IndexingJobSummary> listJobs(UUID repositoryId) {
        return jdbc.query("""
                SELECT id, repository_id, job_type, status, total_files, processed_files, total_chunks,
                       failed_files, commit_hash, error_message, started_at, finished_at, created_at
                FROM indexing_jobs
                WHERE repository_id = :repositoryId
                ORDER BY created_at DESC
                LIMIT 20
                """, new MapSqlParameterSource().addValue("repositoryId", repositoryId), this::mapJobSummary);
    }

    public Optional<IndexingJobSummary> findJob(UUID jobId) {
        List<IndexingJobSummary> jobs = jdbc.query("""
                SELECT id, repository_id, job_type, status, total_files, processed_files, total_chunks,
                       failed_files, commit_hash, error_message, started_at, finished_at, created_at
                FROM indexing_jobs
                WHERE id = :jobId
                """, new MapSqlParameterSource().addValue("jobId", jobId), this::mapJobSummary);
        return jobs.stream().findFirst();
    }

    public Optional<IndexingJobSummary> findRunningJob(UUID repositoryId) {
        List<IndexingJobSummary> jobs = jdbc.query("""
                SELECT id, repository_id, job_type, status, total_files, processed_files, total_chunks,
                       failed_files, commit_hash, error_message, started_at, finished_at, created_at
                FROM indexing_jobs
                WHERE repository_id = :repositoryId
                  AND status IN ('RUNNING', 'CANCELLING')
                ORDER BY created_at DESC
                LIMIT 1
                """, new MapSqlParameterSource().addValue("repositoryId", repositoryId), this::mapJobSummary);
        return jobs.stream().findFirst();
    }

    public void updateJobStatus(UUID jobId, String status, String message) {
        jdbc.update("""
                UPDATE indexing_jobs
                SET status = :status,
                    error_message = :message
                WHERE id = :jobId
                """, new MapSqlParameterSource()
                .addValue("jobId", jobId)
                .addValue("status", status)
                .addValue("message", message));
    }

    public UUID createFile(UUID repositoryId, UUID indexVersion, String filePath, String language, String contentHash) {
        UUID fileId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO code_files (id, repository_id, index_version, file_path, language, content_hash, active)
                VALUES (:id, :repositoryId, :indexVersion, :filePath, :language, :contentHash, FALSE)
                """, new MapSqlParameterSource()
                .addValue("id", fileId)
                .addValue("repositoryId", repositoryId)
                .addValue("indexVersion", indexVersion)
                .addValue("filePath", filePath)
                .addValue("language", language)
                .addValue("contentHash", contentHash));
        return fileId;
    }

    public List<CodeFileSummary> listActiveFiles(UUID repositoryId, String query, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("query", query == null || query.isBlank() ? null : "%" + query.trim() + "%")
                .addValue("limit", Math.max(1, Math.min(limit, 200)));

        return jdbc.query("""
                SELECT f.id, f.repository_id, f.file_path, f.language, f.content_hash, f.updated_at,
                       COUNT(c.id) AS chunk_count
                FROM code_files f
                LEFT JOIN code_chunks c ON c.file_id = f.id AND c.active
                WHERE f.active
                  AND (CAST(:repositoryId AS uuid) IS NULL OR f.repository_id = CAST(:repositoryId AS uuid))
                  AND (CAST(:query AS varchar) IS NULL OR f.file_path ILIKE CAST(:query AS varchar))
                GROUP BY f.id
                ORDER BY f.file_path
                LIMIT :limit
                """, params, this::mapFileSummary);
    }

    public Optional<CodeFileRecord> findActiveFile(UUID repositoryId, UUID fileId) {
        List<CodeFileRecord> files = jdbc.query("""
                SELECT id, repository_id, index_version, file_path, language, content_hash
                FROM code_files
                WHERE id = :fileId
                  AND active
                  AND (CAST(:repositoryId AS uuid) IS NULL OR repository_id = CAST(:repositoryId AS uuid))
                """, new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("fileId", fileId), this::mapFileRecord);
        return files.stream().findFirst();
    }

    public List<CodeChunkSummary> listActiveChunksForFile(UUID fileId) {
        return jdbc.query("""
                SELECT id, chunk_index, chunk_type, symbol_name, class_name, method_name, control_name,
                       event_name, line_start, line_end, content, metadata
                FROM code_chunks
                WHERE file_id = :fileId AND active
                ORDER BY chunk_index ASC
                """, new MapSqlParameterSource().addValue("fileId", fileId), this::mapChunkSummary);
    }

    public void addChunks(UUID repositoryId, UUID fileId, UUID indexVersion, String filePath, List<ParsedCodeChunk> chunks, List<List<Double>> embeddings) {
        for (int i = 0; i < chunks.size(); i++) {
            ParsedCodeChunk chunk = chunks.get(i);
            jdbc.update("""
                    INSERT INTO code_chunks (
                        id, repository_id, file_id, index_version, file_path, chunk_index, chunk_type,
                        symbol_name, class_name, method_name, namespace_name, control_name, event_name,
                        line_start, line_end, content, metadata, embedding, active
                    )
                    VALUES (
                        :id, :repositoryId, :fileId, :indexVersion, :filePath, :chunkIndex, :chunkType,
                        :symbolName, :className, :methodName, :namespaceName, :controlName, :eventName,
                        :lineStart, :lineEnd, :content, CAST(:metadata AS jsonb), CAST(:embedding AS vector), FALSE
                    )
                    """, new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("repositoryId", repositoryId)
                    .addValue("fileId", fileId)
                    .addValue("indexVersion", indexVersion)
                    .addValue("filePath", filePath)
                    .addValue("chunkIndex", chunk.chunkIndex())
                    .addValue("chunkType", chunk.chunkType())
                    .addValue("symbolName", chunk.symbolName())
                    .addValue("className", chunk.className())
                    .addValue("methodName", chunk.methodName())
                    .addValue("namespaceName", chunk.namespaceName())
                    .addValue("controlName", chunk.controlName())
                    .addValue("eventName", chunk.eventName())
                    .addValue("lineStart", chunk.lineStart())
                    .addValue("lineEnd", chunk.lineEnd())
                    .addValue("content", chunk.content())
                    .addValue("metadata", toJson(chunk.metadata()))
                    .addValue("embedding", vectorLiteral(embeddings.get(i))));
        }
    }

    public void activateIndex(UUID repositoryId, UUID indexVersion) {
        jdbc.update("""
                UPDATE code_files
                SET active = FALSE
                WHERE repository_id = :repositoryId
                """, new MapSqlParameterSource().addValue("repositoryId", repositoryId));
        jdbc.update("""
                UPDATE code_chunks
                SET active = FALSE
                WHERE repository_id = :repositoryId
                """, new MapSqlParameterSource().addValue("repositoryId", repositoryId));
        jdbc.update("""
                UPDATE code_files
                SET active = TRUE, updated_at = now()
                WHERE repository_id = :repositoryId AND index_version = :indexVersion
                """, new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("indexVersion", indexVersion));
        jdbc.update("""
                UPDATE code_chunks
                SET active = TRUE
                WHERE repository_id = :repositoryId AND index_version = :indexVersion
                """, new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("indexVersion", indexVersion));
    }

    public List<CodeSearchResult> search(UUID repositoryId, String query, List<Double> embedding, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("query", query)
                .addValue("likeQuery", "%" + query + "%")
                .addValue("embedding", vectorLiteral(embedding))
                .addValue("limit", limit);

        return jdbc.query("""
                SELECT c.id AS chunk_id,
                       c.repository_id,
                       c.file_id,
                       r.name AS repository_name,
                       c.file_path,
                       c.chunk_type,
                       c.symbol_name,
                       c.class_name,
                       c.method_name,
                       c.namespace_name,
                       c.control_name,
                       c.event_name,
                       c.chunk_index,
                       c.line_start,
                       c.line_end,
                       c.content,
                       c.metadata,
                       (
                         0.45 * (1 - (c.embedding <=> CAST(:embedding AS vector))) +
                         0.25 * ts_rank(c.search_vector, plainto_tsquery('simple', :query)) +
                         CASE WHEN c.file_path ILIKE :likeQuery THEN 0.16 ELSE 0 END +
                         CASE WHEN c.symbol_name ILIKE :likeQuery THEN 0.14 ELSE 0 END +
                         CASE WHEN c.method_name ILIKE :likeQuery THEN 0.14 ELSE 0 END +
                         CASE WHEN c.class_name ILIKE :likeQuery THEN 0.12 ELSE 0 END +
                         CASE WHEN c.control_name ILIKE :likeQuery THEN 0.12 ELSE 0 END +
                         CASE WHEN c.event_name ILIKE :likeQuery THEN 0.12 ELSE 0 END
                       ) AS score
                FROM code_chunks c
                JOIN code_repositories r ON r.id = c.repository_id
                WHERE c.active
                  AND (CAST(:repositoryId AS uuid) IS NULL OR c.repository_id = CAST(:repositoryId AS uuid))
                ORDER BY score DESC
                LIMIT :limit
                """, params, this::mapSearchResult);
    }

    public List<CodeSearchResult> keywordSearch(UUID repositoryId, String query, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("query", query)
                .addValue("likeQuery", "%" + query + "%")
                .addValue("limit", limit);

        return jdbc.query("""
                SELECT c.id AS chunk_id,
                       c.repository_id,
                       c.file_id,
                       r.name AS repository_name,
                       c.file_path,
                       c.chunk_type,
                       c.symbol_name,
                       c.class_name,
                       c.method_name,
                       c.namespace_name,
                       c.control_name,
                       c.event_name,
                       c.chunk_index,
                       c.line_start,
                       c.line_end,
                       c.content,
                       c.metadata,
                       (
                         ts_rank(c.search_vector, plainto_tsquery('simple', :query)) +
                         CASE WHEN c.file_path ILIKE :likeQuery THEN 0.35 ELSE 0 END +
                         CASE WHEN c.symbol_name ILIKE :likeQuery THEN 0.35 ELSE 0 END +
                         CASE WHEN c.method_name ILIKE :likeQuery THEN 0.35 ELSE 0 END +
                         CASE WHEN c.class_name ILIKE :likeQuery THEN 0.25 ELSE 0 END +
                         CASE WHEN c.control_name ILIKE :likeQuery THEN 0.25 ELSE 0 END +
                         CASE WHEN c.event_name ILIKE :likeQuery THEN 0.25 ELSE 0 END
                       ) AS score
                FROM code_chunks c
                JOIN code_repositories r ON r.id = c.repository_id
                WHERE c.active
                  AND (CAST(:repositoryId AS uuid) IS NULL OR c.repository_id = CAST(:repositoryId AS uuid))
                  AND (
                    c.search_vector @@ plainto_tsquery('simple', :query)
                    OR c.content ILIKE :likeQuery
                    OR c.file_path ILIKE :likeQuery
                    OR c.symbol_name ILIKE :likeQuery
                    OR c.method_name ILIKE :likeQuery
                    OR c.class_name ILIKE :likeQuery
                    OR c.control_name ILIKE :likeQuery
                    OR c.event_name ILIKE :likeQuery
                  )
                ORDER BY score DESC
                LIMIT :limit
                """, params, this::mapSearchResult);
    }

    public List<CodeSearchResult> relatedChunks(UUID repositoryId, List<String> filePaths, int centerChunkIndex, int limit) {
        if (filePaths == null || filePaths.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("filePaths", filePaths)
                .addValue("minIndex", Math.max(0, centerChunkIndex - 1))
                .addValue("maxIndex", centerChunkIndex + 1)
                .addValue("limit", limit);

        return jdbc.query("""
                SELECT c.id AS chunk_id,
                       c.repository_id,
                       c.file_id,
                       r.name AS repository_name,
                       c.file_path,
                       c.chunk_type,
                       c.symbol_name,
                       c.class_name,
                       c.method_name,
                       c.namespace_name,
                       c.control_name,
                       c.event_name,
                       c.chunk_index,
                       c.line_start,
                       c.line_end,
                       c.content,
                       c.metadata,
                       0.05 AS score
                FROM code_chunks c
                JOIN code_repositories r ON r.id = c.repository_id
                WHERE c.active
                  AND c.repository_id = :repositoryId
                  AND c.file_path IN (:filePaths)
                  AND c.chunk_index BETWEEN :minIndex AND :maxIndex
                ORDER BY c.file_path, c.chunk_index
                LIMIT :limit
                """, params, this::mapSearchResult);
    }

    private CodeRepositoryRecord mapRepositoryRecord(ResultSet rs, int rowNum) throws SQLException {
        return new CodeRepositoryRecord(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("git_url"),
                rs.getString("branch"),
                rs.getString("auth_type"),
                rs.getString("local_path"),
                rs.getString("status"),
                rs.getString("last_indexed_commit")
        );
    }

    private CodeRepositorySummary mapRepositorySummary(ResultSet rs, int rowNum) throws SQLException {
        return new CodeRepositorySummary(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("git_url"),
                rs.getString("branch"),
                rs.getString("auth_type"),
                rs.getString("status"),
                rs.getString("last_indexed_commit"),
                rs.getString("error_message"),
                rs.getInt("active_file_count"),
                rs.getInt("active_chunk_count"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private CodeFileRecord mapFileRecord(ResultSet rs, int rowNum) throws SQLException {
        return new CodeFileRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("repository_id", UUID.class),
                rs.getObject("index_version", UUID.class),
                rs.getString("file_path"),
                rs.getString("language"),
                rs.getString("content_hash")
        );
    }

    private CodeFileSummary mapFileSummary(ResultSet rs, int rowNum) throws SQLException {
        return new CodeFileSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("repository_id", UUID.class),
                rs.getString("file_path"),
                rs.getString("language"),
                rs.getString("content_hash"),
                rs.getInt("chunk_count"),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private CodeChunkSummary mapChunkSummary(ResultSet rs, int rowNum) throws SQLException {
        String content = rs.getString("content");
        String preview = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (preview.length() > 260) {
            preview = preview.substring(0, 260) + "...";
        }
        return new CodeChunkSummary(
                rs.getObject("id", UUID.class),
                rs.getInt("chunk_index"),
                rs.getString("chunk_type"),
                rs.getString("symbol_name"),
                rs.getString("class_name"),
                rs.getString("method_name"),
                rs.getString("control_name"),
                rs.getString("event_name"),
                rs.getInt("line_start"),
                rs.getInt("line_end"),
                preview,
                fromJson(rs.getString("metadata"))
        );
    }

    private IndexingJobSummary mapJobSummary(ResultSet rs, int rowNum) throws SQLException {
        return new IndexingJobSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("repository_id", UUID.class),
                rs.getString("job_type"),
                rs.getString("status"),
                rs.getInt("total_files"),
                rs.getInt("processed_files"),
                rs.getInt("total_chunks"),
                rs.getInt("failed_files"),
                rs.getString("commit_hash"),
                rs.getString("error_message"),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private CodeSearchResult mapSearchResult(ResultSet rs, int rowNum) throws SQLException {
        return new CodeSearchResult(
                rs.getObject("chunk_id", UUID.class),
                rs.getObject("repository_id", UUID.class),
                rs.getObject("file_id", UUID.class),
                rs.getString("repository_name"),
                rs.getString("file_path"),
                rs.getString("chunk_type"),
                rs.getString("symbol_name"),
                rs.getString("class_name"),
                rs.getString("method_name"),
                rs.getString("namespace_name"),
                rs.getString("control_name"),
                rs.getString("event_name"),
                rs.getInt("chunk_index"),
                rs.getInt("line_start"),
                rs.getInt("line_end"),
                rs.getString("content"),
                rs.getDouble("score"),
                fromJson(rs.getString("metadata"))
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

    private String vectorLiteral(List<Double> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(values.get(i));
        }
        return builder.append(']').toString();
    }
}
