package com.learnbot.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeChunkSummary;
import com.learnbot.dto.CodeAnalysisDiagnosticSummary;
import com.learnbot.dto.CodeFileSummary;
import com.learnbot.dto.CodeRepositorySummary;
import com.learnbot.dto.CodeSearchResult;
import com.learnbot.dto.IndexingJobSummary;
import com.learnbot.dto.IndexingJobFailureSummary;
import com.learnbot.service.ActiveCodeFileSnapshot;
import com.learnbot.service.CodeGraph;
import com.learnbot.service.CodeGraphEdge;
import com.learnbot.service.CodeGraphNode;
import com.learnbot.service.CodeGraphEnrichmentJob;
import com.learnbot.service.CodeAnalysisDiagnostic;
import com.learnbot.service.CodeFileRecord;
import com.learnbot.service.CodeRepositoryRecord;
import com.learnbot.service.EncryptedGitCredential;
import com.learnbot.service.ParsedCodeChunk;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class CodeRepository {
    private static final Logger log = LoggerFactory.getLogger(CodeRepository.class);
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final LearnBotProperties properties;

    public CodeRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper, LearnBotProperties properties) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public CodeRepositoryRecord createRepository(String name, String gitUrl, String branch, String authType, String localPath, UUID spaceId, UUID createdBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO code_repositories (
                    id, name, source_type, source_label, git_url, branch, auth_type, local_path, status, space_id, created_by
                )
                VALUES (
                    :id, :name, 'GIT', :gitUrl, :gitUrl, :branch, :authType, :localPath, 'PENDING', :spaceId, :createdBy
                )
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", name)
                .addValue("gitUrl", gitUrl)
                .addValue("branch", branch)
                .addValue("authType", authType)
                .addValue("localPath", localPath)
                .addValue("spaceId", spaceId)
                .addValue("createdBy", createdBy));
        return findRepository(id).orElseThrow();
    }

    public CodeRepositoryRecord createZipRepository(String name, String sourceLabel, String sourceHash, String localPath, UUID spaceId, UUID createdBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO code_repositories (
                    id, name, source_type, source_label, source_hash, git_url, branch, auth_type,
                    local_path, status, last_indexed_commit, space_id, created_by
                )
                VALUES (
                    :id, :name, 'ZIP', :sourceLabel, :sourceHash, NULL, 'SNAPSHOT', 'NONE',
                    :localPath, 'PENDING', NULL, :spaceId, :createdBy
                )
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", name)
                .addValue("sourceLabel", sourceLabel)
                .addValue("sourceHash", sourceHash)
                .addValue("localPath", localPath)
                .addValue("spaceId", spaceId)
                .addValue("createdBy", createdBy));
        return findRepository(id).orElseThrow();
    }

    public void storeCredential(UUID repositoryId, EncryptedGitCredential credential) {
        jdbc.update("""
                UPDATE code_repositories
                SET credential_username = :username,
                    credential_token_iv = :iv,
                    credential_token_ciphertext = :ciphertext,
                    credential_updated_at = now(),
                    updated_at = now()
                WHERE id = :repositoryId
                """, new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("username", credential.username())
                .addValue("iv", credential.iv())
                .addValue("ciphertext", credential.ciphertext()));
    }

    public Optional<EncryptedGitCredential> findCredential(UUID repositoryId) {
        List<EncryptedGitCredential> credentials = jdbc.query("""
                SELECT credential_username, credential_token_iv, credential_token_ciphertext
                FROM code_repositories
                WHERE id = :repositoryId
                  AND credential_token_iv IS NOT NULL
                  AND credential_token_ciphertext IS NOT NULL
                """, new MapSqlParameterSource().addValue("repositoryId", repositoryId), (rs, rowNum) ->
                new EncryptedGitCredential(
                        rs.getString("credential_username"),
                        rs.getString("credential_token_iv"),
                        rs.getString("credential_token_ciphertext")
                ));
        return credentials.stream().findFirst();
    }

    public List<CodeRepositorySummary> listRepositories(List<UUID> spaceIds, UUID selectedSpaceId) {
        return jdbc.query("""
                SELECT r.id, r.space_id, r.name, r.source_type, r.source_label, r.source_hash,
                       r.git_url, r.branch, r.auth_type, r.status, r.last_indexed_commit,
                       r.error_message, r.created_at, r.updated_at,
                       (r.credential_token_ciphertext IS NOT NULL) AS credential_stored,
                       COALESCE(f.active_file_count, 0) AS active_file_count,
                       COALESCE(c.active_chunk_count, 0) AS active_chunk_count
                FROM code_repositories r
                LEFT JOIN (
                    SELECT repository_id, COUNT(*) AS active_file_count
                    FROM code_files
                    WHERE active
                      AND file_path <> '__learnbot__/project-context.md'
                    GROUP BY repository_id
                ) f ON f.repository_id = r.id
                LEFT JOIN (
                    SELECT repository_id, COUNT(*) AS active_chunk_count
                    FROM code_chunks
                    WHERE active
                    GROUP BY repository_id
                ) c ON c.repository_id = r.id
                WHERE r.deleted_at IS NULL
                  AND r.space_id IN (:spaceIds)
                  AND (CAST(:selectedSpaceId AS uuid) IS NULL OR r.space_id = CAST(:selectedSpaceId AS uuid))
                ORDER BY r.created_at DESC
                """, new MapSqlParameterSource()
                .addValue("spaceIds", spaceIds)
                .addValue("selectedSpaceId", selectedSpaceId), this::mapRepositorySummary);
    }

    public Optional<CodeRepositoryRecord> findRepository(UUID repositoryId) {
        List<CodeRepositoryRecord> repositories = jdbc.query("""
                SELECT id, space_id, name, source_type, source_label, source_hash,
                       git_url, branch, auth_type, local_path, status, last_indexed_commit
                FROM code_repositories
                WHERE id = :repositoryId
                  AND deleted_at IS NULL
                """, new MapSqlParameterSource().addValue("repositoryId", repositoryId), this::mapRepositoryRecord);
        return repositories.stream().findFirst();
    }

    public void updateZipSnapshot(UUID repositoryId, String sourceLabel, String sourceHash, String localPath) {
        jdbc.update("""
                UPDATE code_repositories
                SET source_label = :sourceLabel,
                    source_hash = :sourceHash,
                    local_path = :localPath,
                    updated_at = now()
                WHERE id = :repositoryId
                  AND source_type = 'ZIP'
                """, new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("sourceLabel", sourceLabel)
                .addValue("sourceHash", sourceHash)
                .addValue("localPath", localPath));
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

    public void deleteRepository(UUID repositoryId) {
        jdbc.update("""
                UPDATE code_repositories
                SET deleted_at = now(), updated_at = now()
                WHERE id = :repositoryId
                """, new MapSqlParameterSource().addValue("repositoryId", repositoryId));
    }

    public int deleteFailedJobHistory(UUID repositoryId) {
        return jdbc.update("""
                DELETE FROM indexing_jobs j
                WHERE j.repository_id = :repositoryId
                  AND j.status IN ('FAILED', 'CANCELLED')
                  AND NOT EXISTS (
                    SELECT 1
                    FROM code_files f
                    WHERE f.index_version = j.id
                      AND f.active
                  )
                """, new MapSqlParameterSource().addValue("repositoryId", repositoryId));
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

    @Transactional
    public void completeSuccessfulIndex(UUID repositoryId, UUID indexVersion, String commitHash) {
        setActiveIndex(repositoryId, indexVersion);
        markRepositoryIndexed(repositoryId, commitHash);
        finishJob(indexVersion, "SUCCEEDED", commitHash, null);
    }

    @Transactional
    public void completeSuccessfulZipIndex(UUID repositoryId, UUID indexVersion, String sourceLabel, String sourceHash, String localPath) {
        updateZipSnapshot(repositoryId, sourceLabel, sourceHash, localPath);
        setActiveIndex(repositoryId, indexVersion);
        markRepositoryIndexed(repositoryId, sourceHash);
        finishJob(indexVersion, "SUCCEEDED", sourceHash, null);
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
        updateJobProgress(jobId, totalFiles, processedFiles, totalChunks, failedFiles, 0, 0, 0, 0);
    }

    public void updateJobProgress(
            UUID jobId,
            int totalFiles,
            int processedFiles,
            int totalChunks,
            int failedFiles,
            int addedFiles,
            int modifiedFiles,
            int unchangedFiles,
            int deletedFiles
    ) {
        jdbc.update("""
                UPDATE indexing_jobs
                SET total_files = :totalFiles,
                    processed_files = :processedFiles,
                    total_chunks = :totalChunks,
                    failed_files = :failedFiles,
                    added_files = :addedFiles,
                    modified_files = :modifiedFiles,
                    unchanged_files = :unchangedFiles,
                    deleted_files = :deletedFiles
                WHERE id = :jobId
                """, new MapSqlParameterSource()
                .addValue("jobId", jobId)
                .addValue("totalFiles", totalFiles)
                .addValue("processedFiles", processedFiles)
                .addValue("totalChunks", totalChunks)
                .addValue("failedFiles", failedFiles)
                .addValue("addedFiles", addedFiles)
                .addValue("modifiedFiles", modifiedFiles)
                .addValue("unchangedFiles", unchangedFiles)
                .addValue("deletedFiles", deletedFiles));
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
                       failed_files, added_files, modified_files, unchanged_files, deleted_files,
                       commit_hash, error_message, started_at, finished_at, created_at
                FROM indexing_jobs
                WHERE repository_id = :repositoryId
                ORDER BY created_at DESC
                LIMIT 20
                """, new MapSqlParameterSource().addValue("repositoryId", repositoryId), this::mapJobSummary);
    }

    public Optional<IndexingJobSummary> findJob(UUID jobId) {
        List<IndexingJobSummary> jobs = jdbc.query("""
                SELECT id, repository_id, job_type, status, total_files, processed_files, total_chunks,
                       failed_files, added_files, modified_files, unchanged_files, deleted_files,
                       commit_hash, error_message, started_at, finished_at, created_at
                FROM indexing_jobs
                WHERE id = :jobId
                """, new MapSqlParameterSource().addValue("jobId", jobId), this::mapJobSummary);
        return jobs.stream().findFirst();
    }

    public List<IndexingJobFailureSummary> listJobFailures(UUID repositoryId, UUID jobId) {
        return jdbc.query("""
                SELECT id, job_id, repository_id, file_path, stage, message, created_at
                FROM indexing_job_failures
                WHERE repository_id = :repositoryId
                  AND job_id = :jobId
                ORDER BY created_at ASC
                LIMIT 200
                """, new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("jobId", jobId), this::mapJobFailureSummary);
    }

    public void addJobFailure(UUID repositoryId, UUID jobId, String filePath, String stage, String message) {
        jdbc.update("""
                INSERT INTO indexing_job_failures (id, repository_id, job_id, file_path, stage, message)
                VALUES (:id, :repositoryId, :jobId, :filePath, :stage, :message)
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("repositoryId", repositoryId)
                .addValue("jobId", jobId)
                .addValue("filePath", filePath)
                .addValue("stage", stage)
                .addValue("message", trimMessage(message)));
    }

    public void addAnalysisDiagnostics(UUID repositoryId, UUID indexVersion, List<CodeAnalysisDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return;
        }
        for (CodeAnalysisDiagnostic diagnostic : diagnostics) {
            jdbc.update("""
                    INSERT INTO code_analysis_diagnostics (
                        id, repository_id, index_version, stage, analyzer, status, mode,
                        attempted_files, analyzed_files, failed_files, resolved_relations,
                        unresolved_relations, node_count, edge_count, duration_millis, message, metadata
                    ) VALUES (
                        :id, :repositoryId, :indexVersion, :stage, :analyzer, :status, :mode,
                        :attemptedFiles, :analyzedFiles, :failedFiles, :resolvedRelations,
                        :unresolvedRelations, :nodeCount, :edgeCount, :durationMillis, :message,
                        CAST(:metadata AS jsonb)
                    )
                    """, new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("repositoryId", repositoryId)
                    .addValue("indexVersion", indexVersion)
                    .addValue("stage", diagnostic.stage())
                    .addValue("analyzer", diagnostic.analyzer())
                    .addValue("status", diagnostic.status())
                    .addValue("mode", diagnostic.mode())
                    .addValue("attemptedFiles", diagnostic.attemptedFiles())
                    .addValue("analyzedFiles", diagnostic.analyzedFiles())
                    .addValue("failedFiles", diagnostic.failedFiles())
                    .addValue("resolvedRelations", diagnostic.resolvedRelations())
                    .addValue("unresolvedRelations", diagnostic.unresolvedRelations())
                    .addValue("nodeCount", diagnostic.nodeCount())
                    .addValue("edgeCount", diagnostic.edgeCount())
                    .addValue("durationMillis", diagnostic.durationMillis())
                    .addValue("message", diagnostic.message())
                    .addValue("metadata", toJson(diagnostic.metadata())));
        }
    }

    public void enqueueGraphEnrichment(UUID repositoryId, UUID indexVersion) {
        jdbc.update("""
                INSERT INTO code_graph_enrichment_jobs (id, repository_id, index_version, status)
                VALUES (:id, :repositoryId, :indexVersion, 'PENDING')
                ON CONFLICT (repository_id, index_version) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("repositoryId", repositoryId)
                .addValue("indexVersion", indexVersion));
    }

    public void recoverGraphEnrichmentJobs() {
        jdbc.update("""
                UPDATE code_graph_enrichment_jobs
                SET status = 'PENDING', next_attempt_at = now(), started_at = NULL,
                    lease_owner = NULL, lease_until = NULL, heartbeat_at = NULL,
                    error_message = 'Recovered after expired enrichment lease.'
                WHERE status = 'RUNNING'
                  AND (lease_until IS NULL OR lease_until < now())
                """, new MapSqlParameterSource());
    }

    @Transactional
    public Optional<CodeGraphEnrichmentJob> claimGraphEnrichmentJob(String workerId) {
        int leaseSeconds = Math.max(1, properties.getCode().getGraph().getEnrichmentLeaseSeconds());
        List<CodeGraphEnrichmentJob> jobs = jdbc.query("""
                WITH candidate AS (
                    SELECT id
                    FROM code_graph_enrichment_jobs
                    WHERE (status = 'PENDING' AND next_attempt_at <= now())
                       OR (status = 'RUNNING' AND (lease_until IS NULL OR lease_until < now()))
                    ORDER BY created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE code_graph_enrichment_jobs job
                SET status = 'RUNNING',
                    attempts = attempts + 1,
                    started_at = now(),
                    lease_owner = :workerId,
                    lease_until = now() + (:leaseSeconds * interval '1 second'),
                    heartbeat_at = now(),
                    error_message = NULL
                FROM candidate
                WHERE job.id = candidate.id
                RETURNING job.id, job.repository_id, job.index_version, job.status, job.attempts, job.lease_owner
                """, new MapSqlParameterSource().addValue("workerId", workerId).addValue("leaseSeconds", leaseSeconds), (rs, rowNum) -> new CodeGraphEnrichmentJob(
                        rs.getObject("id", UUID.class), rs.getObject("repository_id", UUID.class),
                        rs.getObject("index_version", UUID.class), rs.getString("status"), rs.getInt("attempts"),
                        rs.getString("lease_owner")
                ));
        return jobs.stream().findFirst();
    }

    public boolean finishGraphEnrichmentJob(UUID id, String workerId, String status, String message) {
        int updated = jdbc.update("""
                UPDATE code_graph_enrichment_jobs
                SET status = :status,
                    error_message = :message,
                    finished_at = now(),
                    lease_owner = NULL,
                    lease_until = NULL,
                    heartbeat_at = NULL
                WHERE id = :id
                  AND lease_owner = :workerId
                """, new MapSqlParameterSource().addValue("id", id).addValue("workerId", workerId)
                .addValue("status", status).addValue("message", message == null ? null : trimMessage(message)));
        return updated > 0;
    }

    public boolean retryGraphEnrichmentJob(UUID id, String workerId, int attempts, String message) {
        int delayMinutes = attempts <= 1 ? 1 : attempts == 2 ? 5 : 30;
        int updated = jdbc.update("""
                UPDATE code_graph_enrichment_jobs
                SET status = 'PENDING', error_message = :message,
                    next_attempt_at = now() + (:delayMinutes * interval '1 minute'),
                    lease_owner = NULL,
                    lease_until = NULL,
                    heartbeat_at = NULL
                WHERE id = :id
                  AND lease_owner = :workerId
                """, new MapSqlParameterSource().addValue("id", id).addValue("workerId", workerId)
                .addValue("delayMinutes", delayMinutes).addValue("message", trimMessage(message)));
        return updated > 0;
    }

    public boolean heartbeatGraphEnrichmentJob(UUID id, String workerId) {
        int leaseSeconds = Math.max(1, properties.getCode().getGraph().getEnrichmentLeaseSeconds());
        int updated = jdbc.update("""
                UPDATE code_graph_enrichment_jobs
                SET lease_until = now() + (:leaseSeconds * interval '1 second'),
                    heartbeat_at = now()
                WHERE id = :id
                  AND lease_owner = :workerId
                  AND status = 'RUNNING'
                """, new MapSqlParameterSource().addValue("id", id).addValue("workerId", workerId)
                .addValue("leaseSeconds", leaseSeconds));
        return updated > 0;
    }

    public boolean isActiveIndex(UUID repositoryId, UUID indexVersion) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM code_graph_nodes
                WHERE repository_id = :repositoryId AND index_version = :indexVersion AND active
                """, new MapSqlParameterSource().addValue("repositoryId", repositoryId)
                .addValue("indexVersion", indexVersion), Integer.class);
        return count != null && count > 0;
    }

    public CodeGraph loadGraph(UUID repositoryId, UUID indexVersion) {
        List<CodeGraphNode> nodes = jdbc.query("""
                SELECT node_key, node_type, name, qualified_name, file_path, chunk_id, metadata
                FROM code_graph_nodes
                WHERE repository_id = :repositoryId AND index_version = :indexVersion
                """, new MapSqlParameterSource().addValue("repositoryId", repositoryId)
                .addValue("indexVersion", indexVersion), (rs, rowNum) -> new CodeGraphNode(
                        rs.getString("node_key"), rs.getString("node_type"), rs.getString("name"),
                        rs.getString("qualified_name"), rs.getString("file_path"),
                        rs.getObject("chunk_id", UUID.class), fromJson(rs.getString("metadata"))
                ));
        List<CodeGraphEdge> edges = jdbc.query("""
                SELECT source.node_key AS source_key, target.node_key AS target_key,
                       edge.edge_type, edge.confidence, edge.evidence_chunk_id, edge.metadata
                FROM code_graph_edges edge
                JOIN code_graph_nodes source ON source.id = edge.source_node_id
                JOIN code_graph_nodes target ON target.id = edge.target_node_id
                WHERE edge.repository_id = :repositoryId AND edge.index_version = :indexVersion
                """, new MapSqlParameterSource().addValue("repositoryId", repositoryId)
                .addValue("indexVersion", indexVersion), (rs, rowNum) -> new CodeGraphEdge(
                        rs.getString("source_key"), rs.getString("target_key"), rs.getString("edge_type"),
                        rs.getDouble("confidence"), rs.getObject("evidence_chunk_id", UUID.class),
                        fromJson(rs.getString("metadata"))
                ));
        return new CodeGraph(nodes, edges);
    }

    public int mergeGraphEdges(UUID repositoryId, UUID indexVersion, List<CodeGraphEdge> edges) {
        if (edges == null || edges.isEmpty() || !isActiveIndex(repositoryId, indexVersion)) return 0;
        Map<String, UUID> nodeIds = new LinkedHashMap<>();
        jdbc.query("""
                SELECT id, node_key FROM code_graph_nodes
                WHERE repository_id = :repositoryId AND index_version = :indexVersion
                """, new MapSqlParameterSource().addValue("repositoryId", repositoryId)
                .addValue("indexVersion", indexVersion), (rs, rowNum) -> Map.entry(
                        rs.getString("node_key"), rs.getObject("id", UUID.class)
                )).forEach(entry -> nodeIds.put(entry.getKey(), entry.getValue()));
        int inserted = 0;
        for (CodeGraphEdge edge : edges) {
            UUID sourceId = nodeIds.get(edge.sourceKey());
            UUID targetId = nodeIds.get(edge.targetKey());
            if (sourceId == null || targetId == null || sourceId.equals(targetId)) continue;
            inserted += jdbc.update("""
                    INSERT INTO code_graph_edges (
                        id, repository_id, index_version, source_node_id, target_node_id,
                        edge_type, confidence, evidence_chunk_id, metadata, active
                    ) VALUES (
                        :id, :repositoryId, :indexVersion, :sourceId, :targetId,
                        :edgeType, :confidence, :evidenceChunkId, CAST(:metadata AS jsonb), TRUE
                    ) ON CONFLICT (repository_id, index_version, source_node_id, target_node_id, edge_type) DO NOTHING
                    """, new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                    .addValue("repositoryId", repositoryId).addValue("indexVersion", indexVersion)
                    .addValue("sourceId", sourceId).addValue("targetId", targetId)
                    .addValue("edgeType", edge.type()).addValue("confidence", edge.confidence())
                    .addValue("evidenceChunkId", edge.evidenceChunkId()).addValue("metadata", toJson(edge.metadata())));
        }
        return inserted;
    }

    public List<CodeAnalysisDiagnosticSummary> listAnalysisDiagnostics(UUID repositoryId, UUID indexVersion) {
        return jdbc.query("""
                SELECT id, repository_id, index_version, stage, analyzer, status, mode,
                       attempted_files, analyzed_files, failed_files, resolved_relations,
                       unresolved_relations, node_count, edge_count, duration_millis,
                       message, metadata, created_at
                FROM code_analysis_diagnostics
                WHERE repository_id = :repositoryId AND index_version = :indexVersion
                ORDER BY created_at, stage
                """, new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("indexVersion", indexVersion), (rs, rowNum) -> new CodeAnalysisDiagnosticSummary(
                        rs.getObject("id", UUID.class),
                        rs.getObject("repository_id", UUID.class),
                        rs.getObject("index_version", UUID.class),
                        rs.getString("stage"),
                        rs.getString("analyzer"),
                        rs.getString("status"),
                        rs.getString("mode"),
                        rs.getInt("attempted_files"),
                        rs.getInt("analyzed_files"),
                        rs.getInt("failed_files"),
                        rs.getInt("resolved_relations"),
                        rs.getInt("unresolved_relations"),
                        rs.getInt("node_count"),
                        rs.getInt("edge_count"),
                        rs.getLong("duration_millis"),
                        rs.getString("message"),
                        fromJson(rs.getString("metadata")),
                        rs.getObject("created_at", OffsetDateTime.class)
                ));
    }

    public Optional<IndexingJobSummary> findRunningJob(UUID repositoryId) {
        List<IndexingJobSummary> jobs = jdbc.query("""
                SELECT id, repository_id, job_type, status, total_files, processed_files, total_chunks,
                       failed_files, added_files, modified_files, unchanged_files, deleted_files,
                       commit_hash, error_message, started_at, finished_at, created_at
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

    public Map<String, ActiveCodeFileSnapshot> listActiveFileSnapshots(UUID repositoryId) {
        return jdbc.query("""
                SELECT f.id, f.file_path, f.content_hash, COUNT(c.id) AS chunk_count
                FROM code_files f
                LEFT JOIN code_chunks c ON c.file_id = f.id AND c.active
                WHERE f.repository_id = :repositoryId
                  AND f.active
                GROUP BY f.id
                """, new MapSqlParameterSource().addValue("repositoryId", repositoryId), rs -> {
            Map<String, ActiveCodeFileSnapshot> snapshots = new java.util.HashMap<>();
            while (rs.next()) {
                ActiveCodeFileSnapshot snapshot = new ActiveCodeFileSnapshot(
                        rs.getObject("id", UUID.class),
                        rs.getString("file_path"),
                        rs.getString("content_hash"),
                        rs.getInt("chunk_count")
                );
                snapshots.put(snapshot.filePath(), snapshot);
            }
            return snapshots;
        });
    }

    public Map<String, UUID> findActiveFileIdsByPath(UUID repositoryId, List<String> filePaths) {
        if (repositoryId == null || filePaths == null || filePaths.isEmpty()) {
            return Map.of();
        }
        List<String> normalizedPaths = filePaths.stream()
                .filter(path -> path != null && !path.isBlank())
                .distinct()
                .toList();
        if (normalizedPaths.isEmpty()) {
            return Map.of();
        }

        return jdbc.query("""
                SELECT id, file_path
                FROM code_files
                WHERE repository_id = :repositoryId
                  AND active
                  AND file_path IN (:filePaths)
                """, new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("filePaths", normalizedPaths), rs -> {
            Map<String, UUID> fileIds = new java.util.LinkedHashMap<>();
            while (rs.next()) {
                fileIds.put(rs.getString("file_path"), rs.getObject("id", UUID.class));
            }
            return fileIds;
        });
    }

    public UUID copyActiveFileToIndex(UUID repositoryId, UUID oldFileId, UUID newFileId, UUID indexVersion) {
        int files = jdbc.update("""
                INSERT INTO code_files (id, repository_id, index_version, file_path, language, content_hash, active)
                SELECT :newFileId, repository_id, :indexVersion, file_path, language, content_hash, FALSE
                FROM code_files
                WHERE id = :oldFileId
                  AND repository_id = :repositoryId
                  AND active
                """, new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("oldFileId", oldFileId)
                .addValue("newFileId", newFileId)
                .addValue("indexVersion", indexVersion));
        if (files == 0) {
            throw new IllegalArgumentException("복사할 활성 코드 파일을 찾을 수 없습니다.");
        }

        jdbc.update("""
                INSERT INTO code_chunks (
                    id, repository_id, file_id, index_version, file_path, chunk_index, chunk_type,
                    symbol_name, class_name, method_name, namespace_name, control_name, event_name,
                    line_start, line_end, content, metadata, embedding, active
                )
                SELECT gen_random_uuid(), repository_id, :newFileId, :indexVersion, file_path, chunk_index, chunk_type,
                       symbol_name, class_name, method_name, namespace_name, control_name, event_name,
                       line_start, line_end, content, metadata, embedding, FALSE
                FROM code_chunks
                WHERE file_id = :oldFileId
                  AND repository_id = :repositoryId
                  AND active
                """, new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("oldFileId", oldFileId)
                .addValue("newFileId", newFileId)
                .addValue("indexVersion", indexVersion));
        return newFileId;
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
                JOIN code_repositories r ON r.id = f.repository_id
                LEFT JOIN code_chunks c ON c.file_id = f.id AND c.active
                WHERE f.active
                  AND r.deleted_at IS NULL
                  AND f.file_path <> '__learnbot__/project-context.md'
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

    public String activeFileContentFromChunks(UUID fileId) {
        return jdbc.query("""
                SELECT content
                FROM code_chunks
                WHERE file_id = :fileId AND active
                ORDER BY chunk_index ASC
                """, new MapSqlParameterSource().addValue("fileId", fileId), rs -> {
            StringBuilder content = new StringBuilder();
            while (rs.next()) {
                if (!content.isEmpty()) {
                    content.append("\n\n");
                }
                content.append(rs.getString("content"));
            }
            return content.toString();
        });
    }

    public void addChunks(UUID repositoryId, UUID fileId, UUID indexVersion, String filePath, List<ParsedCodeChunk> chunks, List<List<Double>> embeddings) {
        int batchSize = Math.max(1, properties.getEmbedding().getInsertBatchSize());
        for (int start = 0; start < chunks.size(); start += batchSize) {
            int end = Math.min(start + batchSize, chunks.size());
            MapSqlParameterSource[] batch = new MapSqlParameterSource[end - start];
            for (int i = start; i < end; i++) {
                ParsedCodeChunk chunk = chunks.get(i);
                batch[i - start] = new MapSqlParameterSource()
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
                        .addValue("embedding", vectorLiteral(embeddings.get(i)));
            }
            jdbc.batchUpdate("""
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
                    """, batch);
        }
    }

    public List<CodeSearchResult> listChunksForIndex(UUID repositoryId, UUID indexVersion) {
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
                       0.0 AS score
                FROM code_chunks c
                JOIN code_repositories r ON r.id = c.repository_id
                WHERE c.repository_id = :repositoryId
                  AND c.index_version = :indexVersion
                ORDER BY c.file_path, c.chunk_index
                """, new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("indexVersion", indexVersion), this::mapSearchResult);
    }

    public void replaceGraph(UUID repositoryId, UUID indexVersion, CodeGraph graph) {
        jdbc.update("""
                DELETE FROM code_graph_edges
                WHERE repository_id = :repositoryId
                  AND index_version = :indexVersion
                """, new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("indexVersion", indexVersion));
        jdbc.update("""
                DELETE FROM code_graph_nodes
                WHERE repository_id = :repositoryId
                  AND index_version = :indexVersion
                """, new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("indexVersion", indexVersion));
        if (graph == null || graph.nodes().isEmpty()) {
            return;
        }

        Map<String, UUID> nodeIds = new java.util.LinkedHashMap<>();
        for (CodeGraphNode node : graph.nodes()) {
            UUID nodeId = UUID.randomUUID();
            nodeIds.put(node.key(), nodeId);
            jdbc.update("""
                    INSERT INTO code_graph_nodes (
                        id, repository_id, index_version, node_key, node_type, name, qualified_name,
                        file_path, chunk_id, metadata, active
                    )
                    VALUES (
                        :id, :repositoryId, :indexVersion, :nodeKey, :nodeType, :name, :qualifiedName,
                        :filePath, :chunkId, CAST(:metadata AS jsonb), FALSE
                    )
                    """, new MapSqlParameterSource()
                    .addValue("id", nodeId)
                    .addValue("repositoryId", repositoryId)
                    .addValue("indexVersion", indexVersion)
                    .addValue("nodeKey", node.key())
                    .addValue("nodeType", node.type())
                    .addValue("name", node.name())
                    .addValue("qualifiedName", node.qualifiedName())
                    .addValue("filePath", node.filePath())
                    .addValue("chunkId", node.chunkId())
                    .addValue("metadata", toJson(node.metadata())));
        }

        for (CodeGraphEdge edge : graph.edges()) {
            UUID sourceId = nodeIds.get(edge.sourceKey());
            UUID targetId = nodeIds.get(edge.targetKey());
            if (sourceId == null || targetId == null || sourceId.equals(targetId)) {
                continue;
            }
            jdbc.update("""
                    INSERT INTO code_graph_edges (
                        id, repository_id, index_version, source_node_id, target_node_id,
                        edge_type, confidence, evidence_chunk_id, metadata, active
                    )
                    VALUES (
                        :id, :repositoryId, :indexVersion, :sourceNodeId, :targetNodeId,
                        :edgeType, :confidence, :evidenceChunkId, CAST(:metadata AS jsonb), FALSE
                    )
                    """, new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("repositoryId", repositoryId)
                    .addValue("indexVersion", indexVersion)
                    .addValue("sourceNodeId", sourceId)
                    .addValue("targetNodeId", targetId)
                    .addValue("edgeType", edge.type())
                    .addValue("confidence", edge.confidence())
                    .addValue("evidenceChunkId", edge.evidenceChunkId())
                    .addValue("metadata", toJson(edge.metadata())));
        }
    }

    @Transactional
    public void activateIndex(UUID repositoryId, UUID indexVersion) {
        setActiveIndex(repositoryId, indexVersion);
    }

    private void setActiveIndex(UUID repositoryId, UUID indexVersion) {
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
                UPDATE code_graph_edges
                SET active = FALSE
                WHERE repository_id = :repositoryId
                """, new MapSqlParameterSource().addValue("repositoryId", repositoryId));
        jdbc.update("""
                UPDATE code_graph_nodes
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
        jdbc.update("""
                UPDATE code_graph_nodes
                SET active = TRUE
                WHERE repository_id = :repositoryId AND index_version = :indexVersion
                """, new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("indexVersion", indexVersion));
        jdbc.update("""
                UPDATE code_graph_edges
                SET active = TRUE
                WHERE repository_id = :repositoryId AND index_version = :indexVersion
                """, new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("indexVersion", indexVersion));
    }

    public List<CodeSearchResult> search(UUID repositoryId, String query, List<Double> embedding, int limit, List<UUID> spaceIds, UUID selectedSpaceId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("query", query)
                .addValue("likeQuery", "%" + query + "%")
                .addValue("embedding", vectorLiteral(embedding))
                .addValue("limit", limit)
                .addValue("spaceIds", spaceIds)
                .addValue("selectedSpaceId", selectedSpaceId);

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
                  AND r.deleted_at IS NULL
                  AND r.space_id IN (:spaceIds)
                  AND (CAST(:selectedSpaceId AS uuid) IS NULL OR r.space_id = CAST(:selectedSpaceId AS uuid))
                  AND (CAST(:repositoryId AS uuid) IS NULL OR c.repository_id = CAST(:repositoryId AS uuid))
                ORDER BY score DESC
                LIMIT :limit
                """, params, this::mapSearchResult);
    }

    public List<CodeSearchResult> keywordSearch(UUID repositoryId, String query, int limit, List<UUID> spaceIds, UUID selectedSpaceId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("query", query)
                .addValue("likeQuery", "%" + query + "%")
                .addValue("limit", limit)
                .addValue("spaceIds", spaceIds)
                .addValue("selectedSpaceId", selectedSpaceId);

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
                  AND r.deleted_at IS NULL
                  AND r.space_id IN (:spaceIds)
                  AND (CAST(:selectedSpaceId AS uuid) IS NULL OR r.space_id = CAST(:selectedSpaceId AS uuid))
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

    public List<CodeSearchResult> findSymbolDefinitions(UUID repositoryId, String symbol, int limit, List<UUID> spaceIds, UUID selectedSpaceId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("symbol", symbol)
                .addValue("likeQuery", "%" + symbol + "%")
                .addValue("limit", Math.max(1, Math.min(limit, 50)))
                .addValue("spaceIds", spaceIds)
                .addValue("selectedSpaceId", selectedSpaceId);

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
                         CASE WHEN lower(c.symbol_name) = lower(:symbol) THEN 1.20 ELSE 0 END +
                         CASE WHEN lower(c.method_name) = lower(:symbol) THEN 1.10 ELSE 0 END +
                         CASE WHEN lower(c.class_name) = lower(:symbol) THEN 1.00 ELSE 0 END +
                         CASE WHEN lower(c.control_name) = lower(:symbol) THEN 0.90 ELSE 0 END +
                         CASE WHEN lower(c.event_name) = lower(:symbol) THEN 0.90 ELSE 0 END +
                         CASE WHEN c.file_path ILIKE :likeQuery THEN 0.15 ELSE 0 END
                       ) AS score
                FROM code_chunks c
                JOIN code_repositories r ON r.id = c.repository_id
                WHERE c.active
                  AND r.deleted_at IS NULL
                  AND r.space_id IN (:spaceIds)
                  AND (CAST(:selectedSpaceId AS uuid) IS NULL OR r.space_id = CAST(:selectedSpaceId AS uuid))
                  AND (CAST(:repositoryId AS uuid) IS NULL OR c.repository_id = CAST(:repositoryId AS uuid))
                  AND (
                    lower(c.symbol_name) = lower(:symbol)
                    OR lower(c.method_name) = lower(:symbol)
                    OR lower(c.class_name) = lower(:symbol)
                    OR lower(c.control_name) = lower(:symbol)
                    OR lower(c.event_name) = lower(:symbol)
                    OR c.file_path ILIKE :likeQuery
                  )
                ORDER BY score DESC, c.file_path, c.line_start
                LIMIT :limit
                """, params, this::mapSearchResult);
    }

    public List<CodeSearchResult> findSymbolReferences(UUID repositoryId, String symbol, int limit, List<UUID> spaceIds, UUID selectedSpaceId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("symbol", symbol)
                .addValue("likeQuery", "%" + symbol + "%")
                .addValue("limit", Math.max(1, Math.min(limit, 80)))
                .addValue("spaceIds", spaceIds)
                .addValue("selectedSpaceId", selectedSpaceId);

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
                         CASE WHEN c.content ILIKE :likeQuery THEN 0.72 ELSE 0 END +
                         CASE WHEN c.symbol_name ILIKE :likeQuery THEN 0.22 ELSE 0 END +
                         CASE WHEN c.method_name ILIKE :likeQuery THEN 0.22 ELSE 0 END +
                         CASE WHEN c.class_name ILIKE :likeQuery THEN 0.18 ELSE 0 END +
                         CASE WHEN c.control_name ILIKE :likeQuery THEN 0.18 ELSE 0 END +
                         CASE WHEN c.event_name ILIKE :likeQuery THEN 0.18 ELSE 0 END +
                         CASE WHEN c.file_path ILIKE :likeQuery THEN 0.10 ELSE 0 END
                       ) AS score
                FROM code_chunks c
                JOIN code_repositories r ON r.id = c.repository_id
                WHERE c.active
                  AND r.deleted_at IS NULL
                  AND r.space_id IN (:spaceIds)
                  AND (CAST(:selectedSpaceId AS uuid) IS NULL OR r.space_id = CAST(:selectedSpaceId AS uuid))
                  AND (CAST(:repositoryId AS uuid) IS NULL OR c.repository_id = CAST(:repositoryId AS uuid))
                  AND (
                    c.content ILIKE :likeQuery
                    OR c.symbol_name ILIKE :likeQuery
                    OR c.method_name ILIKE :likeQuery
                    OR c.class_name ILIKE :likeQuery
                    OR c.control_name ILIKE :likeQuery
                    OR c.event_name ILIKE :likeQuery
                    OR c.file_path ILIKE :likeQuery
                  )
                ORDER BY score DESC, c.file_path, c.line_start
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
                  AND r.deleted_at IS NULL
                  AND c.repository_id = :repositoryId
                  AND c.file_path IN (:filePaths)
                  AND c.chunk_index BETWEEN :minIndex AND :maxIndex
                ORDER BY c.file_path, c.chunk_index
                LIMIT :limit
                """, params, this::mapSearchResult);
    }

    public List<CodeSearchResult> graphRelatedChunks(UUID repositoryId, List<UUID> seedChunkIds, List<String> edgeTypes, int limit) {
        return graphRelatedChunks(repositoryId, seedChunkIds, edgeTypes, 1, "BOTH", limit, List.of());
    }

    public List<CodeSearchResult> graphRelatedChunks(
            UUID repositoryId,
            List<UUID> seedChunkIds,
            List<String> edgeTypes,
            int maxHop,
            String direction,
            int limit
    ) {
        return graphRelatedChunks(repositoryId, seedChunkIds, edgeTypes, maxHop, direction, limit, List.of());
    }

    public List<CodeSearchResult> graphRelatedChunks(
            UUID repositoryId,
            List<UUID> seedChunkIds,
            List<String> edgeTypes,
            int maxHop,
            String direction,
            int limit,
            List<String> seedNodeTypes
    ) {
        if (seedChunkIds == null || seedChunkIds.isEmpty()) {
            return List.of();
        }
        List<String> safeEdgeTypes = edgeTypes == null || edgeTypes.isEmpty()
                ? List.of("CALLS", "REFERENCES", "HANDLES_EVENT", "BINDS_TO", "CONTAINS", "DEFINES",
                        "EXTENDS", "IMPLEMENTS", "OVERRIDES", "INJECTS", "RETURNS", "ACCEPTS", "THROWS",
                        "ANNOTATED_BY", "READS_FIELD", "WRITES_FIELD", "USES_ENTITY", "MAPS_TO_TABLE", "EXPOSES_ENDPOINT")
                : edgeTypes;
        String safeDirection = Set.of("FORWARD", "REVERSE", "BOTH").contains(direction) ? direction : "BOTH";
        int safeMaxHop = Math.max(1, Math.min(maxHop, 4));
        int maxSeedNodes = Math.max(1, properties.getCode().getGraph().getMaxSeedNodes());
        int maxEdgesPerNode = Math.max(1, properties.getCode().getGraph().getMaxEdgesPerNode());
        int maxCandidatesPerHop = Math.max(1, properties.getCode().getGraph().getMaxCandidatesPerHop());
        int maxTraversalRows = Math.max(1, properties.getCode().getGraph().getMaxTraversalRows());
        int safeLimit = Math.max(1, Math.min(limit, 80));
        MapSqlParameterSource seedParams = new MapSqlParameterSource()
                .addValue("repositoryId", repositoryId)
                .addValue("maxSeedNodes", maxSeedNodes);
        String seedValues = seedValues(seedChunkIds, seedParams);
        String seedTypeOrder = seedTypeOrder(seedNodeTypes, seedParams);

        List<TraversalPath> seeds = jdbc.query("""
                WITH seed_input(chunk_id, seed_rank) AS (
                    VALUES %s
                )
                SELECT DISTINCT n.id, n.name, seed_input.seed_rank,
                       %s AS seed_type_rank
                FROM code_graph_nodes n
                JOIN seed_input ON seed_input.chunk_id = n.chunk_id
                WHERE n.active
                  AND (CAST(:repositoryId AS uuid) IS NULL OR n.repository_id = CAST(:repositoryId AS uuid))
                ORDER BY seed_input.seed_rank, seed_type_rank, n.name, n.id
                LIMIT :maxSeedNodes
                """.formatted(seedValues, seedTypeOrder), seedParams, (rs, rowNum) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new TraversalPath(id, List.of(id), List.of(rs.getString("name")), List.of(), 0, 1.0);
                });
        if (seeds.isEmpty()) {
            return List.of();
        }

        Map<UUID, TraversalPath> bestPaths = new LinkedHashMap<>();
        seeds.forEach(seed -> bestPaths.put(seed.nodeId(), seed));
        List<TraversalPath> frontier = seeds;
        int traversalRows = 0;
        boolean truncated = false;
        int reachedHop = 0;

        for (int hop = 1; hop <= safeMaxHop && !frontier.isEmpty(); hop++) {
            int remainingRows = maxTraversalRows - traversalRows;
            if (remainingRows <= 0) {
                truncated = true;
                break;
            }
            Map<UUID, TraversalPath> frontierById = new LinkedHashMap<>();
            frontier.forEach(path -> frontierById.put(path.nodeId(), path));
            List<TraversalNeighbor> neighbors = graphNeighbors(
                    new ArrayList<>(frontierById.keySet()), safeEdgeTypes, safeDirection,
                    maxEdgesPerNode, remainingRows
            );
            traversalRows += neighbors.size();
            if (neighbors.size() >= remainingRows) {
                truncated = true;
            }
            Map<UUID, TraversalPath> nextByNode = new LinkedHashMap<>();
            for (TraversalNeighbor neighbor : neighbors) {
                TraversalPath parent = frontierById.get(neighbor.fromNodeId());
                if (parent == null || parent.visited().contains(neighbor.toNodeId())) {
                    continue;
                }
                List<UUID> visited = new ArrayList<>(parent.visited());
                visited.add(neighbor.toNodeId());
                List<String> names = new ArrayList<>(parent.pathNames());
                names.add(neighbor.toNodeName());
                List<String> edges = new ArrayList<>(parent.pathEdges());
                edges.add(neighbor.edgeType());
                TraversalPath candidate = new TraversalPath(
                        neighbor.toNodeId(), List.copyOf(visited), List.copyOf(names), List.copyOf(edges),
                        hop, parent.pathScore() * neighbor.confidence() * 0.82
                );
                TraversalPath current = nextByNode.get(candidate.nodeId());
                if (current == null || candidate.pathScore() > current.pathScore()) {
                    nextByNode.put(candidate.nodeId(), candidate);
                }
            }
            frontier = nextByNode.values().stream()
                    .sorted(Comparator.comparingDouble(TraversalPath::pathScore).reversed())
                    .limit(maxCandidatesPerHop)
                    .toList();
            if (nextByNode.size() > frontier.size()) {
                truncated = true;
            }
            for (TraversalPath path : frontier) {
                TraversalPath current = bestPaths.get(path.nodeId());
                if (current == null || path.pathScore() > current.pathScore()) {
                    bestPaths.put(path.nodeId(), path);
                }
            }
            reachedHop = hop;
        }

        Set<UUID> seedNodeIds = seeds.stream().map(TraversalPath::nodeId).collect(java.util.stream.Collectors.toSet());
        List<TraversalPath> resultPaths = bestPaths.values().stream()
                .filter(path -> !seedNodeIds.contains(path.nodeId()))
                .sorted(Comparator.comparingDouble(TraversalPath::pathScore).reversed())
                .toList();
        if (truncated) {
            log.warn("code_graph_traversal repositoryId={} status=TRUNCATED rows={} reachedHop={} maxHop={} maxRows={} maxEdgesPerNode={} maxCandidatesPerHop={}",
                    repositoryId, traversalRows, reachedHop, safeMaxHop, maxTraversalRows, maxEdgesPerNode, maxCandidatesPerHop);
        }
        return graphChunksForPaths(repositoryId, seedChunkIds, resultPaths, safeLimit, traversalRows, reachedHop, truncated);
    }

    private List<TraversalNeighbor> graphNeighbors(List<UUID> frontierIds, List<String> edgeTypes, String direction,
                                                    int maxEdgesPerNode, int remainingRows) {
        boolean forward = "FORWARD".equals(direction) || "BOTH".equals(direction);
        boolean reverse = "REVERSE".equals(direction) || "BOTH".equals(direction);
        return jdbc.query("""
                WITH candidates AS (
                    SELECT e.source_node_id AS from_node_id, e.target_node_id AS to_node_id,
                           e.edge_type, e.confidence
                    FROM code_graph_edges e
                    WHERE :forward AND e.active AND e.source_node_id IN (:frontierIds)
                      AND e.edge_type IN (:edgeTypes)
                    UNION ALL
                    SELECT e.target_node_id AS from_node_id, e.source_node_id AS to_node_id,
                           e.edge_type, e.confidence
                    FROM code_graph_edges e
                    WHERE :reverse AND e.active AND e.target_node_id IN (:frontierIds)
                      AND e.edge_type IN (:edgeTypes)
                ), ranked AS (
                    SELECT candidates.*,
                           row_number() OVER (
                               PARTITION BY from_node_id
                               ORDER BY confidence DESC,
                                   CASE WHEN edge_type = 'REFERENCES' THEN 1 ELSE 0 END,
                                   edge_type, to_node_id
                           ) AS edge_rank
                    FROM candidates
                )
                SELECT ranked.from_node_id, ranked.to_node_id, target.name AS to_node_name,
                       ranked.edge_type, ranked.confidence
                FROM ranked
                JOIN code_graph_nodes target ON target.id = ranked.to_node_id AND target.active
                WHERE ranked.edge_rank <= :maxEdgesPerNode
                ORDER BY ranked.edge_rank, ranked.confidence DESC, ranked.to_node_id
                LIMIT :remainingRows
                """, new MapSqlParameterSource()
                .addValue("frontierIds", frontierIds)
                .addValue("edgeTypes", edgeTypes)
                .addValue("forward", forward)
                .addValue("reverse", reverse)
                .addValue("maxEdgesPerNode", maxEdgesPerNode)
                .addValue("remainingRows", remainingRows), (rs, rowNum) -> new TraversalNeighbor(
                        rs.getObject("from_node_id", UUID.class),
                        rs.getObject("to_node_id", UUID.class),
                        rs.getString("to_node_name"),
                        rs.getString("edge_type"),
                        rs.getDouble("confidence")
                ));
    }

    private String seedValues(List<UUID> seedChunkIds, MapSqlParameterSource params) {
        List<UUID> distinct = seedChunkIds.stream().distinct().toList();
        List<String> values = new ArrayList<>();
        for (int i = 0; i < distinct.size(); i++) {
            String param = "seedChunk" + i;
            params.addValue(param, distinct.get(i));
            values.add("(CAST(:" + param + " AS uuid), " + i + ")");
        }
        return String.join(", ", values);
    }

    private String seedTypeOrder(List<String> seedNodeTypes, MapSqlParameterSource params) {
        if (seedNodeTypes == null || seedNodeTypes.isEmpty()) {
            return "99";
        }
        StringBuilder builder = new StringBuilder("CASE n.node_type ");
        List<String> distinct = seedNodeTypes.stream()
                .filter(type -> type != null && !type.isBlank())
                .distinct()
                .toList();
        if (distinct.isEmpty()) {
            return "99";
        }
        for (int i = 0; i < distinct.size(); i++) {
            String param = "seedNodeType" + i;
            params.addValue(param, distinct.get(i));
            builder.append("WHEN :").append(param).append(" THEN ").append(i).append(' ');
        }
        return builder.append("ELSE 99 END").toString();
    }

    private List<CodeSearchResult> graphChunksForPaths(UUID repositoryId, List<UUID> seedChunkIds,
                                                        List<TraversalPath> paths, int limit, int traversalRows,
                                                        int reachedHop, boolean truncated) {
        if (paths.isEmpty()) {
            return List.of();
        }
        Map<UUID, TraversalPath> pathByNode = new LinkedHashMap<>();
        paths.forEach(path -> pathByNode.put(path.nodeId(), path));
        List<TraversalChunk> rows = jdbc.query("""
                SELECT n.id AS graph_node_id,
                       c.id AS chunk_id, c.repository_id, c.file_id, r.name AS repository_name,
                       c.file_path, c.chunk_type, c.symbol_name, c.class_name, c.method_name,
                       c.namespace_name, c.control_name, c.event_name, c.chunk_index,
                       c.line_start, c.line_end, c.content, c.metadata, 0.0 AS score
                FROM code_graph_nodes n
                JOIN code_chunks c ON c.id = n.chunk_id AND c.active
                JOIN code_repositories r ON r.id = c.repository_id AND r.deleted_at IS NULL
                WHERE n.active AND n.id IN (:nodeIds)
                  AND c.id NOT IN (:seedChunkIds)
                  AND (CAST(:repositoryId AS uuid) IS NULL OR c.repository_id = CAST(:repositoryId AS uuid))
                """, new MapSqlParameterSource()
                .addValue("nodeIds", new ArrayList<>(pathByNode.keySet()))
                .addValue("seedChunkIds", seedChunkIds)
                .addValue("repositoryId", repositoryId), (rs, rowNum) -> new TraversalChunk(
                        rs.getObject("graph_node_id", UUID.class), mapSearchResult(rs, rowNum)
                ));
        Map<UUID, CodeSearchResult> bestByChunk = new LinkedHashMap<>();
        for (TraversalChunk row : rows) {
            TraversalPath path = pathByNode.get(row.nodeId());
            if (path == null) continue;
            CodeSearchResult base = row.result();
            Map<String, Object> metadata = new LinkedHashMap<>(base.metadata() == null ? Map.of() : base.metadata());
            metadata.put("graphPath", String.join(" -> ", path.pathNames()));
            metadata.put("graphPathNodes", path.pathNames());
            metadata.put("graphEdgeTypes", path.pathEdges());
            metadata.put("graphEdgeType", path.pathEdges().get(path.pathEdges().size() - 1));
            metadata.put("graphDepth", path.depth());
            metadata.put("graphPathScore", path.pathScore());
            metadata.put("graphExpanded", true);
            metadata.put("graphTraversalRows", traversalRows);
            metadata.put("graphTraversalReachedHop", reachedHop);
            metadata.put("graphTraversalTruncated", truncated);
            CodeSearchResult enriched = new CodeSearchResult(
                    base.chunkId(), base.repositoryId(), base.fileId(), base.repositoryName(), base.filePath(),
                    base.chunkType(), base.symbolName(), base.className(), base.methodName(), base.namespaceName(),
                    base.controlName(), base.eventName(), base.chunkIndex(), base.lineStart(), base.lineEnd(),
                    base.content(), 0.12 + Math.min(0.36, path.pathScore() * 0.24), Map.copyOf(metadata)
            );
            CodeSearchResult current = bestByChunk.get(enriched.chunkId());
            if (current == null || enriched.score() > current.score()) bestByChunk.put(enriched.chunkId(), enriched);
        }
        return bestByChunk.values().stream()
                .sorted(Comparator.comparingDouble(CodeSearchResult::score).reversed()
                        .thenComparing(CodeSearchResult::filePath).thenComparingInt(CodeSearchResult::lineStart))
                .limit(limit)
                .toList();
    }

    private record TraversalPath(UUID nodeId, List<UUID> visited, List<String> pathNames,
                                 List<String> pathEdges, int depth, double pathScore) {}
    private record TraversalNeighbor(UUID fromNodeId, UUID toNodeId, String toNodeName,
                                     String edgeType, double confidence) {}
    private record TraversalChunk(UUID nodeId, CodeSearchResult result) {}

    private CodeRepositoryRecord mapRepositoryRecord(ResultSet rs, int rowNum) throws SQLException {
        return new CodeRepositoryRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("space_id", UUID.class),
                rs.getString("name"),
                rs.getString("source_type"),
                rs.getString("source_label"),
                rs.getString("source_hash"),
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
                rs.getObject("space_id", UUID.class),
                rs.getString("name"),
                rs.getString("source_type"),
                rs.getString("source_label"),
                rs.getString("source_hash"),
                rs.getString("git_url"),
                rs.getString("branch"),
                rs.getString("auth_type"),
                rs.getString("status"),
                rs.getString("last_indexed_commit"),
                rs.getString("error_message"),
                rs.getBoolean("credential_stored"),
                rs.getInt("active_file_count"),
                rs.getInt("active_chunk_count"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private IndexingJobFailureSummary mapJobFailureSummary(ResultSet rs, int rowNum) throws SQLException {
        return new IndexingJobFailureSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("job_id", UUID.class),
                rs.getObject("repository_id", UUID.class),
                rs.getString("file_path"),
                rs.getString("stage"),
                rs.getString("message"),
                rs.getObject("created_at", OffsetDateTime.class)
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
                rs.getInt("added_files"),
                rs.getInt("modified_files"),
                rs.getInt("unchanged_files"),
                rs.getInt("deleted_files"),
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

    private String trimMessage(String message) {
        String clean = message == null || message.isBlank() ? "Unknown indexing failure." : message.trim();
        return clean.length() <= 1000 ? clean : clean.substring(0, 1000);
    }
}
