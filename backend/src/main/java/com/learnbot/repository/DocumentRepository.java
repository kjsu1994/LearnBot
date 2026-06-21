package com.learnbot.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.config.LearnBotProperties;
import com.learnbot.domain.SourceStatus;
import com.learnbot.domain.SourceType;
import com.learnbot.dto.DocumentSummary;
import com.learnbot.dto.DocumentIndexingJobSummary;
import com.learnbot.dto.DocumentProcessingDiagnosticSummary;
import com.learnbot.dto.CrawlAuditSummary;
import com.learnbot.dto.DocumentChunkDetail;
import com.learnbot.dto.SearchFilter;
import com.learnbot.dto.SearchResult;
import com.learnbot.dto.StoredObjectSummary;
import com.learnbot.service.Chunk;
import com.learnbot.service.CrawlAuditEvent;
import com.learnbot.service.DocumentEntityMentionExtractor;
import com.learnbot.service.DocumentGraphEdge;
import com.learnbot.service.DocumentGraphJob;
import com.learnbot.service.DocumentGraphNode;
import com.learnbot.service.DocumentProcessingDiagnostic;
import com.learnbot.service.DocumentPageMetadata;
import com.learnbot.service.DocumentEnrichmentJob;
import com.learnbot.service.StoredObject;
import com.learnbot.service.StoredSource;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class DocumentRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final LearnBotProperties properties;
    private final DocumentEntityMentionExtractor entityMentionExtractor;

    public DocumentRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper, LearnBotProperties properties) {
        this(jdbc, objectMapper, properties, new DocumentEntityMentionExtractor());
    }

    @Autowired
    public DocumentRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            LearnBotProperties properties,
            DocumentEntityMentionExtractor entityMentionExtractor
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.entityMentionExtractor = entityMentionExtractor == null ? new DocumentEntityMentionExtractor() : entityMentionExtractor;
    }

    public UUID createSource(SourceType type, String name, String location, UUID spaceId, UUID createdBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO data_sources (id, type, name, location, status, space_id, created_by)
                VALUES (:id, :type, :name, :location, :status, :spaceId, :createdBy)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("type", type.name())
                .addValue("name", name)
                .addValue("location", location)
                .addValue("status", SourceStatus.INDEXING.name())
                .addValue("spaceId", spaceId)
                .addValue("createdBy", createdBy));
        return id;
    }

    public UUID createDocument(UUID sourceId, String title, String sourceUri, String contentType, Map<String, Object> metadata) {
        return createDocument(sourceId, title, sourceUri, contentType, metadata, null);
    }

    public UUID createDocument(UUID sourceId, String title, String sourceUri, String contentType, Map<String, Object> metadata, String contentHash) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO documents (id, source_id, title, source_uri, content_type, metadata, content_hash)
                VALUES (:id, :sourceId, :title, :sourceUri, :contentType, CAST(:metadata AS jsonb), :contentHash)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("sourceId", sourceId)
                .addValue("title", title)
                .addValue("sourceUri", sourceUri)
                .addValue("contentType", contentType)
                .addValue("metadata", toJson(metadata))
                .addValue("contentHash", contentHash));
        return id;
    }

    public void addChunks(UUID documentId, List<Chunk> chunks, List<List<Double>> embeddings) {
        int batchSize = Math.max(1, properties.getEmbedding().getInsertBatchSize());
        for (int start = 0; start < chunks.size(); start += batchSize) {
            int end = Math.min(start + batchSize, chunks.size());
            MapSqlParameterSource[] batch = new MapSqlParameterSource[end - start];
            for (int i = start; i < end; i++) {
                Chunk chunk = chunks.get(i);
                Map<String, Object> metadata = new LinkedHashMap<>(chunk.metadata() == null ? Map.of() : chunk.metadata());
                metadata.putIfAbsent("parentDocumentId", documentId.toString());
                batch[i - start] = new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("documentId", documentId)
                        .addValue("chunkIndex", chunk.index())
                        .addValue("content", chunk.content())
                        .addValue("metadata", toJson(metadata))
                        .addValue("embedding", vectorLiteral(embeddings.get(i)));
            }
            jdbc.batchUpdate("""
                    INSERT INTO document_chunks (id, document_id, chunk_index, content, metadata, embedding)
                    VALUES (:id, :documentId, :chunkIndex, :content, CAST(:metadata AS jsonb), CAST(:embedding AS vector))
                    """, batch);
        }
    }

    public void updateSourceStatus(UUID sourceId, SourceStatus status, String errorMessage) {
        jdbc.update("""
                UPDATE data_sources
                SET status = :status, error_message = :errorMessage, updated_at = now()
                WHERE id = :sourceId
                """, new MapSqlParameterSource()
                .addValue("sourceId", sourceId)
                .addValue("status", status.name())
                .addValue("errorMessage", errorMessage));
    }

    public Optional<StoredSource> findSource(UUID sourceId) {
        List<StoredSource> sources = jdbc.query("""
                SELECT id, type, name, location, status
                FROM data_sources
                WHERE id = :sourceId
                """, new MapSqlParameterSource().addValue("sourceId", sourceId), this::mapSource);
        return sources.stream().findFirst();
    }

    public Optional<StoredSource> findSourceByDocumentId(UUID documentId, List<UUID> spaceIds) {
        List<StoredSource> sources = jdbc.query("""
                SELECT s.id, s.type, s.name, s.location, s.status
                FROM data_sources s
                JOIN documents d ON d.source_id = s.id
                WHERE d.id = :documentId
                  AND s.deleted_at IS NULL
                  AND s.space_id IN (:spaceIds)
                """, new MapSqlParameterSource()
                .addValue("documentId", documentId)
                .addValue("spaceIds", spaceIds), this::mapSource);
        return sources.stream().findFirst();
    }

    public void softDeleteSource(UUID sourceId, UUID deletedBy) {
        jdbc.update("""
                UPDATE data_sources
                SET deleted_at = now(),
                    deleted_by = :deletedBy,
                    updated_at = now()
                WHERE id = :sourceId
                  AND deleted_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("sourceId", sourceId)
                .addValue("deletedBy", deletedBy));
    }

    public void deleteDocumentsForSource(UUID sourceId) {
        jdbc.update("""
                DELETE FROM documents
                WHERE source_id = :sourceId
                """, new MapSqlParameterSource().addValue("sourceId", sourceId));
    }

    public void deleteDocumentsForSourceExcept(UUID sourceId, List<UUID> preservedDocumentIds) {
        if (preservedDocumentIds == null || preservedDocumentIds.isEmpty()) {
            deleteDocumentsForSource(sourceId);
            return;
        }
        jdbc.update("""
                DELETE FROM documents
                WHERE source_id = :sourceId
                  AND id NOT IN (:preservedDocumentIds)
                """, new MapSqlParameterSource()
                .addValue("sourceId", sourceId)
                .addValue("preservedDocumentIds", preservedDocumentIds));
    }

    public int countDocumentsForSource(UUID sourceId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM documents
                WHERE source_id = :sourceId
                """, new MapSqlParameterSource().addValue("sourceId", sourceId), Integer.class);
        return count == null ? 0 : count;
    }

    public Optional<ReusableDocument> findReusableDocument(UUID sourceId, String sourceUri, String contentHash) {
        if (contentHash == null || contentHash.isBlank()) {
            return Optional.empty();
        }
        List<ReusableDocument> documents = jdbc.query("""
                SELECT d.id, COUNT(c.id) AS chunk_count
                FROM documents d
                LEFT JOIN document_chunks c ON c.document_id = d.id
                WHERE d.source_id = :sourceId
                  AND d.source_uri = :sourceUri
                  AND d.content_hash = :contentHash
                GROUP BY d.id, d.created_at
                ORDER BY d.created_at DESC
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("sourceId", sourceId)
                .addValue("sourceUri", sourceUri)
                .addValue("contentHash", contentHash), (rs, rowNum) -> new ReusableDocument(
                rs.getObject("id", UUID.class),
                rs.getInt("chunk_count")
        ));
        return documents.stream().findFirst();
    }

    public int copyReusableDocumentChunks(UUID oldDocumentId, UUID newDocumentId) {
        return jdbc.update("""
                INSERT INTO document_chunks (id, document_id, chunk_index, content, metadata, embedding)
                SELECT gen_random_uuid(),
                       :newDocumentId,
                       chunk_index,
                       content,
                       metadata || jsonb_build_object('parentDocumentId', CAST(:newDocumentId AS text)),
                       embedding
                FROM document_chunks
                WHERE document_id = :oldDocumentId
                  AND metadata ->> 'kind' IS DISTINCT FROM 'document_context'
                """, new MapSqlParameterSource()
                .addValue("oldDocumentId", oldDocumentId)
                .addValue("newDocumentId", newDocumentId));
    }

    public UUID createDocumentJob(UUID sourceId, UUID spaceId, String jobType) {
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO document_indexing_jobs (id, source_id, space_id, job_type, status, started_at)
                VALUES (:id, :sourceId, :spaceId, :jobType, 'RUNNING', now())
                """, new MapSqlParameterSource()
                .addValue("id", jobId)
                .addValue("sourceId", sourceId)
                .addValue("spaceId", spaceId)
                .addValue("jobType", jobType));
        return jobId;
    }

    public void updateDocumentJobProgress(
            UUID jobId,
            int totalDocuments,
            int processedDocuments,
            int totalChunks,
            int reusedChunks,
            int embeddedChunks
    ) {
        jdbc.update("""
                UPDATE document_indexing_jobs
                SET total_documents = :totalDocuments,
                    processed_documents = :processedDocuments,
                    total_chunks = :totalChunks,
                    reused_chunks = :reusedChunks,
                    embedded_chunks = :embeddedChunks
                WHERE id = :jobId
                """, new MapSqlParameterSource()
                .addValue("jobId", jobId)
                .addValue("totalDocuments", totalDocuments)
                .addValue("processedDocuments", processedDocuments)
                .addValue("totalChunks", totalChunks)
                .addValue("reusedChunks", reusedChunks)
                .addValue("embeddedChunks", embeddedChunks));
    }

    public void finishDocumentJob(UUID jobId, String status, String errorMessage) {
        jdbc.update("""
                UPDATE document_indexing_jobs
                SET status = :status,
                    error_message = :errorMessage,
                    finished_at = now()
                WHERE id = :jobId
                """, new MapSqlParameterSource()
                .addValue("jobId", jobId)
                .addValue("status", status)
                .addValue("errorMessage", errorMessage));
    }

    public void markDocumentJobSearchable(UUID jobId, String enrichmentStatus, String enrichmentMessage) {
        jdbc.update("""
                UPDATE document_indexing_jobs
                SET searchable_at = COALESCE(searchable_at, now()),
                    enrichment_status = :enrichmentStatus,
                    enrichment_message = :enrichmentMessage
                WHERE id = :jobId
                """, new MapSqlParameterSource()
                .addValue("jobId", jobId)
                .addValue("enrichmentStatus", enrichmentStatus)
                .addValue("enrichmentMessage", enrichmentMessage));
    }

    public void updateDocumentJobEnrichment(UUID jobId, String enrichmentStatus, String enrichmentMessage) {
        jdbc.update("""
                UPDATE document_indexing_jobs
                SET enrichment_status = :enrichmentStatus,
                    enrichment_message = :enrichmentMessage
                WHERE id = :jobId
                """, new MapSqlParameterSource()
                .addValue("jobId", jobId)
                .addValue("enrichmentStatus", enrichmentStatus)
                .addValue("enrichmentMessage", enrichmentMessage));
    }

    public void markSourceSearchable(UUID sourceId) {
        jdbc.update("""
                UPDATE data_sources
                SET status = 'SEARCHABLE', error_message = NULL, updated_at = now()
                WHERE id = :sourceId
                  AND status <> 'FAILED'
                """, new MapSqlParameterSource().addValue("sourceId", sourceId));
    }

    public void enqueueDocumentEnrichment(UUID sourceId, UUID jobId) {
        jdbc.update("""
                INSERT INTO document_enrichment_jobs (id, source_id, job_id, status)
                VALUES (:id, :sourceId, :jobId, 'PENDING')
                ON CONFLICT (source_id, job_id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("sourceId", sourceId)
                .addValue("jobId", jobId));
    }

    public void enqueueDocumentGraph(UUID sourceId, UUID jobId) {
        jdbc.update("""
                INSERT INTO document_graph_jobs (id, source_id, job_id, status)
                VALUES (:id, :sourceId, :jobId, 'PENDING')
                ON CONFLICT (source_id, job_id) DO UPDATE
                SET status = 'PENDING',
                    attempts = 0,
                    error_message = NULL,
                    lease_owner = NULL,
                    lease_until = NULL,
                    heartbeat_at = NULL,
                    next_attempt_at = now(),
                    started_at = NULL,
                    finished_at = NULL,
                    updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("sourceId", sourceId)
                .addValue("jobId", jobId));
    }

    public void recoverDocumentGraphJobs() {
        jdbc.update("""
                UPDATE document_graph_jobs
                SET status = 'PENDING', next_attempt_at = now(), started_at = NULL,
                    lease_owner = NULL, lease_until = NULL, heartbeat_at = NULL,
                    error_message = 'Recovered after expired graph lease.',
                    updated_at = now()
                WHERE status = 'RUNNING'
                  AND (lease_until IS NULL OR lease_until < now())
                """, new MapSqlParameterSource());
    }

    public Optional<DocumentGraphJob> claimDocumentGraphJob(String workerId) {
        List<DocumentGraphJob> jobs = jdbc.query("""
                WITH candidate AS (
                    SELECT id
                    FROM document_graph_jobs
                    WHERE (status = 'PENDING' AND next_attempt_at <= now())
                       OR (status = 'RUNNING' AND (lease_until IS NULL OR lease_until < now()))
                    ORDER BY created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE document_graph_jobs job
                SET status = 'RUNNING',
                    attempts = attempts + 1,
                    started_at = now(),
                    lease_owner = :workerId,
                    lease_until = now() + interval '300 seconds',
                    heartbeat_at = now(),
                    error_message = NULL,
                    updated_at = now()
                FROM candidate
                WHERE job.id = candidate.id
                RETURNING job.id, job.source_id, job.job_id, job.status, job.attempts, job.lease_owner
                """, new MapSqlParameterSource().addValue("workerId", workerId), (rs, rowNum) -> new DocumentGraphJob(
                rs.getObject("id", UUID.class),
                rs.getObject("source_id", UUID.class),
                rs.getObject("job_id", UUID.class),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getString("lease_owner")
        ));
        return jobs.stream().findFirst();
    }

    public boolean heartbeatDocumentGraphJob(UUID graphJobId, String workerId) {
        int updated = jdbc.update("""
                UPDATE document_graph_jobs
                SET lease_until = now() + interval '300 seconds',
                    heartbeat_at = now(),
                    updated_at = now()
                WHERE id = :id
                  AND lease_owner = :workerId
                  AND status = 'RUNNING'
                """, new MapSqlParameterSource()
                .addValue("id", graphJobId)
                .addValue("workerId", workerId));
        return updated > 0;
    }

    public boolean finishDocumentGraphJob(UUID graphJobId, String workerId, String status, String message) {
        int updated = jdbc.update("""
                UPDATE document_graph_jobs
                SET status = :status,
                    error_message = :message,
                    finished_at = now(),
                    lease_owner = NULL,
                    lease_until = NULL,
                    heartbeat_at = NULL,
                    updated_at = now()
                WHERE id = :id
                  AND lease_owner = :workerId
                """, new MapSqlParameterSource()
                .addValue("id", graphJobId)
                .addValue("workerId", workerId)
                .addValue("status", status)
                .addValue("message", message));
        return updated > 0;
    }

    public boolean retryDocumentGraphJob(UUID graphJobId, String workerId, int attempts, String message) {
        int delayMinutes = attempts <= 1 ? 1 : 5;
        int updated = jdbc.update("""
                UPDATE document_graph_jobs
                SET status = 'PENDING',
                    error_message = :message,
                    next_attempt_at = now() + (:delayMinutes * interval '1 minute'),
                    lease_owner = NULL,
                    lease_until = NULL,
                    heartbeat_at = NULL,
                    updated_at = now()
                WHERE id = :id
                  AND lease_owner = :workerId
                """, new MapSqlParameterSource()
                .addValue("id", graphJobId)
                .addValue("workerId", workerId)
                .addValue("delayMinutes", delayMinutes)
                .addValue("message", message));
        return updated > 0;
    }

    public boolean retryLatestDocumentEnrichmentJob(UUID jobId) {
        int updated = jdbc.update("""
                UPDATE document_enrichment_jobs
                SET status = 'PENDING',
                    attempts = 0,
                    next_attempt_at = now(),
                    error_message = NULL,
                    lease_owner = NULL,
                    lease_until = NULL,
                    updated_at = now()
                WHERE job_id = :jobId
                  AND status IN ('FAILED', 'SKIPPED', 'RETRYING')
                """, new MapSqlParameterSource().addValue("jobId", jobId));
        return updated > 0;
    }

    public boolean retryLatestDocumentGraphJob(UUID jobId) {
        int updated = jdbc.update("""
                UPDATE document_graph_jobs
                SET status = 'PENDING',
                    attempts = 0,
                    next_attempt_at = now(),
                    error_message = NULL,
                    lease_owner = NULL,
                    lease_until = NULL,
                    heartbeat_at = NULL,
                    updated_at = now()
                WHERE job_id = :jobId
                  AND status IN ('FAILED', 'SKIPPED', 'RETRYING')
                """, new MapSqlParameterSource().addValue("jobId", jobId));
        return updated > 0;
    }

    public void refreshSourceReadiness(UUID sourceId) {
        String next = sourceReadiness(sourceId);
        jdbc.update("""
                UPDATE data_sources
                SET status = :status,
                    updated_at = now()
                WHERE id = :sourceId
                  AND status NOT IN ('INDEXING', 'FAILED')
                """, new MapSqlParameterSource()
                .addValue("sourceId", sourceId)
                .addValue("status", next));
    }

    private String sourceReadiness(UUID sourceId) {
        UUID latestJobId = latestDocumentJobId(sourceId).orElse(null);
        if (latestJobId == null) {
            return SourceStatus.READY.name();
        }
        List<String> statuses = new ArrayList<>();
        statuses.addAll(jdbc.queryForList("""
                SELECT status FROM document_enrichment_jobs WHERE source_id = :sourceId AND job_id = :jobId
                """, new MapSqlParameterSource().addValue("sourceId", sourceId).addValue("jobId", latestJobId), String.class));
        statuses.addAll(jdbc.queryForList("""
                SELECT status FROM document_graph_jobs WHERE source_id = :sourceId AND job_id = :jobId
                """, new MapSqlParameterSource().addValue("sourceId", sourceId).addValue("jobId", latestJobId), String.class));
        if (statuses.stream().anyMatch(status -> "FAILED".equals(status) || "RETRYING".equals(status))) {
            return SourceStatus.PARTIAL.name();
        }
        if (statuses.stream().anyMatch(status -> "PENDING".equals(status) || "RUNNING".equals(status))) {
            return SourceStatus.SEARCHABLE.name();
        }
        return SourceStatus.READY.name();
    }

    private Optional<UUID> latestDocumentJobId(UUID sourceId) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT id
                FROM document_indexing_jobs
                WHERE source_id = :sourceId
                ORDER BY created_at DESC
                LIMIT 1
                """, new MapSqlParameterSource().addValue("sourceId", sourceId), UUID.class);
        return ids.stream().findFirst();
    }

    public void recoverDocumentEnrichmentJobs() {
        jdbc.update("""
                UPDATE document_enrichment_jobs
                SET status = 'PENDING',
                    lease_owner = NULL,
                    lease_until = NULL,
                    heartbeat_at = NULL,
                    started_at = NULL,
                    next_attempt_at = now(),
                    error_message = 'Recovered after expired enrichment lease.'
                WHERE status = 'RUNNING'
                  AND lease_until < now()
                """, new MapSqlParameterSource());
    }

    public Optional<DocumentEnrichmentJob> claimDocumentEnrichmentJob(String workerId) {
        List<DocumentEnrichmentJob> jobs = jdbc.query("""
                WITH next_job AS (
                    SELECT id
                    FROM document_enrichment_jobs
                    WHERE status = 'PENDING'
                      AND next_attempt_at <= now()
                    ORDER BY created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE document_enrichment_jobs job
                SET status = 'RUNNING',
                    lease_owner = :workerId,
                    lease_until = now() + interval '5 minutes',
                    heartbeat_at = now(),
                    started_at = now(),
                    updated_at = now()
                FROM next_job
                WHERE job.id = next_job.id
                RETURNING job.id, job.source_id, job.job_id, job.status, job.attempts, job.lease_owner
                """, new MapSqlParameterSource().addValue("workerId", workerId), (rs, rowNum) -> new DocumentEnrichmentJob(
                rs.getObject("id", UUID.class),
                rs.getObject("source_id", UUID.class),
                rs.getObject("job_id", UUID.class),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getString("lease_owner")
        ));
        return jobs.stream().findFirst();
    }

    public boolean heartbeatDocumentEnrichmentJob(UUID enrichmentJobId, String workerId) {
        int updated = jdbc.update("""
                UPDATE document_enrichment_jobs
                SET lease_until = now() + interval '5 minutes',
                    heartbeat_at = now(),
                    updated_at = now()
                WHERE id = :id
                  AND lease_owner = :workerId
                  AND status = 'RUNNING'
                """, new MapSqlParameterSource()
                .addValue("id", enrichmentJobId)
                .addValue("workerId", workerId));
        return updated > 0;
    }

    public boolean finishDocumentEnrichmentJob(UUID enrichmentJobId, String workerId, String status, String message) {
        int updated = jdbc.update("""
                UPDATE document_enrichment_jobs
                SET status = :status,
                    error_message = :message,
                    lease_owner = NULL,
                    lease_until = NULL,
                    heartbeat_at = NULL,
                    finished_at = now(),
                    updated_at = now()
                WHERE id = :id
                  AND lease_owner = :workerId
                """, new MapSqlParameterSource()
                .addValue("id", enrichmentJobId)
                .addValue("workerId", workerId)
                .addValue("status", status)
                .addValue("message", message));
        return updated > 0;
    }

    public boolean retryDocumentEnrichmentJob(UUID enrichmentJobId, String workerId, int attempts, String message) {
        int updated = jdbc.update("""
                UPDATE document_enrichment_jobs
                SET status = 'PENDING',
                    attempts = :attempts + 1,
                    error_message = :message,
                    lease_owner = NULL,
                    lease_until = NULL,
                    heartbeat_at = NULL,
                    next_attempt_at = now() + (:attempts + 1) * interval '30 seconds',
                    updated_at = now()
                WHERE id = :id
                  AND lease_owner = :workerId
                """, new MapSqlParameterSource()
                .addValue("id", enrichmentJobId)
                .addValue("workerId", workerId)
                .addValue("attempts", attempts)
                .addValue("message", message));
        return updated > 0;
    }

    public void addDocumentProcessingDiagnostic(UUID sourceId, UUID jobId, DocumentProcessingDiagnostic diagnostic) {
        if (diagnostic == null) {
            return;
        }
        jdbc.update("""
                INSERT INTO document_processing_diagnostics (
                    id, source_id, job_id, stage, analyzer, status, mode,
                    attempted_items, processed_items, failed_items, node_count, edge_count,
                    duration_millis, message, metadata
                ) VALUES (
                    :id, :sourceId, :jobId, :stage, :analyzer, :status, :mode,
                    :attemptedItems, :processedItems, :failedItems, :nodeCount, :edgeCount,
                    :durationMillis, :message, CAST(:metadata AS jsonb)
                )
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("sourceId", sourceId)
                .addValue("jobId", jobId)
                .addValue("stage", diagnostic.stage())
                .addValue("analyzer", diagnostic.analyzer())
                .addValue("status", diagnostic.status())
                .addValue("mode", diagnostic.mode())
                .addValue("attemptedItems", diagnostic.attemptedItems())
                .addValue("processedItems", diagnostic.processedItems())
                .addValue("failedItems", diagnostic.failedItems())
                .addValue("nodeCount", diagnostic.nodeCount())
                .addValue("edgeCount", diagnostic.edgeCount())
                .addValue("durationMillis", diagnostic.durationMillis())
                .addValue("message", diagnostic.message())
                .addValue("metadata", toJson(diagnostic.metadata())));
    }

    public List<DocumentProcessingDiagnosticSummary> listDocumentProcessingDiagnostics(UUID jobId, List<UUID> spaceIds) {
        return jdbc.query("""
                SELECT diagnostic.id, diagnostic.source_id, diagnostic.job_id, diagnostic.stage,
                       diagnostic.analyzer, diagnostic.status, diagnostic.mode,
                       diagnostic.attempted_items, diagnostic.processed_items, diagnostic.failed_items,
                       diagnostic.node_count, diagnostic.edge_count, diagnostic.duration_millis,
                       diagnostic.message, diagnostic.metadata::text AS metadata, diagnostic.created_at
                FROM document_processing_diagnostics diagnostic
                JOIN document_indexing_jobs job ON job.id = diagnostic.job_id
                WHERE diagnostic.job_id = :jobId
                  AND job.space_id IN (:spaceIds)
                ORDER BY diagnostic.created_at, diagnostic.stage
                """, new MapSqlParameterSource()
                .addValue("jobId", jobId)
                .addValue("spaceIds", spaceIds), (rs, rowNum) -> new DocumentProcessingDiagnosticSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("source_id", UUID.class),
                rs.getObject("job_id", UUID.class),
                rs.getString("stage"),
                rs.getString("analyzer"),
                rs.getString("status"),
                rs.getString("mode"),
                rs.getInt("attempted_items"),
                rs.getInt("processed_items"),
                rs.getInt("failed_items"),
                rs.getInt("node_count"),
                rs.getInt("edge_count"),
                rs.getLong("duration_millis"),
                rs.getString("message"),
                fromJson(rs.getString("metadata")),
                rs.getObject("created_at", OffsetDateTime.class)
        ));
    }

    public List<DocumentIndexingJobSummary> listDocumentJobs(List<UUID> spaceIds, UUID selectedSpaceId) {
        return jdbc.query("""
                SELECT id, source_id, space_id, job_type, status, total_documents, processed_documents,
                       total_chunks, reused_chunks, embedded_chunks, error_message,
                       searchable_at, enrichment_status, enrichment_message,
                       started_at, finished_at, created_at
                FROM document_indexing_jobs
                WHERE space_id IN (:spaceIds)
                  AND (CAST(:selectedSpaceId AS uuid) IS NULL OR space_id = CAST(:selectedSpaceId AS uuid))
                ORDER BY created_at DESC
                LIMIT 100
                """, new MapSqlParameterSource()
                .addValue("spaceIds", spaceIds)
                .addValue("selectedSpaceId", selectedSpaceId), this::mapDocumentJobSummary);
    }

    public Optional<DocumentIndexingJobSummary> findDocumentJob(UUID jobId, List<UUID> spaceIds) {
        List<DocumentIndexingJobSummary> jobs = jdbc.query("""
                SELECT id, source_id, space_id, job_type, status, total_documents, processed_documents,
                       total_chunks, reused_chunks, embedded_chunks, error_message,
                       searchable_at, enrichment_status, enrichment_message,
                       started_at, finished_at, created_at
                FROM document_indexing_jobs
                WHERE id = :jobId
                  AND space_id IN (:spaceIds)
                """, new MapSqlParameterSource()
                .addValue("jobId", jobId)
                .addValue("spaceIds", spaceIds), this::mapDocumentJobSummary);
        return jobs.stream().findFirst();
    }

    public void resetInterruptedDocumentJobs() {
        jdbc.update("""
                UPDATE document_indexing_jobs
                SET status = 'FAILED',
                    error_message = 'Document indexing was interrupted by server restart.',
                    finished_at = now()
                WHERE status = 'RUNNING'
                """, new MapSqlParameterSource());
        jdbc.update("""
                UPDATE data_sources
                SET status = 'FAILED',
                    error_message = 'Document indexing was interrupted by server restart.',
                    updated_at = now()
                WHERE status = 'INDEXING'
                  AND id IN (
                      SELECT source_id
                      FROM document_indexing_jobs
                      WHERE status = 'FAILED'
                        AND error_message = 'Document indexing was interrupted by server restart.'
                  )
                """, new MapSqlParameterSource());
    }

    public void createSourceObject(UUID sourceId, StoredObject object) {
        jdbc.update("""
                INSERT INTO source_objects (id, source_id, bucket, object_key, original_filename, content_type, size_bytes)
                VALUES (:id, :sourceId, :bucket, :objectKey, :originalFilename, :contentType, :sizeBytes)
                ON CONFLICT (source_id) DO UPDATE
                SET bucket = EXCLUDED.bucket,
                    object_key = EXCLUDED.object_key,
                    original_filename = EXCLUDED.original_filename,
                    content_type = EXCLUDED.content_type,
                    size_bytes = EXCLUDED.size_bytes,
                    created_at = now()
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("sourceId", sourceId)
                .addValue("bucket", object.bucket())
                .addValue("objectKey", object.objectKey())
                .addValue("originalFilename", object.originalFilename())
                .addValue("contentType", object.contentType())
                .addValue("sizeBytes", object.sizeBytes()));
    }

    public Optional<StoredObject> findSourceObject(UUID sourceId) {
        List<StoredObject> objects = jdbc.query("""
                SELECT bucket, object_key, original_filename, content_type, size_bytes
                FROM source_objects
                WHERE source_id = :sourceId
                """, new MapSqlParameterSource().addValue("sourceId", sourceId), this::mapStoredObject);
        return objects.stream().findFirst();
    }

    public List<StoredObject> listSourceObjects(UUID sourceId) {
        return jdbc.query("""
                SELECT bucket, object_key, original_filename, content_type, size_bytes
                FROM source_objects
                WHERE source_id = :sourceId
                """, new MapSqlParameterSource().addValue("sourceId", sourceId), this::mapStoredObject);
    }

    public void createCrawlAuditLog(
            UUID sourceId,
            String url,
            String host,
            boolean allowedDomain,
            Boolean robotsAllowed,
            Integer statusCode,
            boolean success,
            String message
    ) {
        createCrawlAuditLog(new CrawlAuditEvent(
                sourceId,
                url,
                host,
                allowedDomain,
                robotsAllowed,
                statusCode,
                success,
                null,
                null,
                null,
                url,
                null,
                Map.of(),
                message
        ));
    }

    public void createCrawlAuditLog(CrawlAuditEvent event) {
        jdbc.update("""
                INSERT INTO crawl_audit_logs (
                    id, source_id, url, host, allowed_domain, robots_allowed, status_code, success,
                    reason_code, depth, referrer_url, normalized_url, content_type, metadata, message
                )
                VALUES (
                    :id, :sourceId, :url, :host, :allowedDomain, :robotsAllowed, :statusCode, :success,
                    :reasonCode, :depth, :referrerUrl, :normalizedUrl, :contentType, CAST(:metadata AS jsonb), :message
                )
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("sourceId", event.sourceId())
                .addValue("url", event.url())
                .addValue("host", event.host())
                .addValue("allowedDomain", event.allowedDomain())
                .addValue("robotsAllowed", event.robotsAllowed())
                .addValue("statusCode", event.statusCode())
                .addValue("success", event.success())
                .addValue("reasonCode", event.reasonCode())
                .addValue("depth", event.depth())
                .addValue("referrerUrl", event.referrerUrl())
                .addValue("normalizedUrl", event.normalizedUrl())
                .addValue("contentType", event.contentType())
                .addValue("metadata", toJson(event.metadata()))
                .addValue("message", event.message()));
    }

    public List<DocumentSummary> listDocuments(List<UUID> spaceIds, UUID selectedSpaceId) {
        return jdbc.query("""
                SELECT d.id, d.source_id, s.space_id, s.type, s.status, d.title, d.source_uri, d.content_type, d.created_at
                FROM documents d
                JOIN data_sources s ON s.id = d.source_id
                WHERE s.deleted_at IS NULL
                  AND s.space_id IN (:spaceIds)
                  AND (CAST(:selectedSpaceId AS uuid) IS NULL OR s.space_id = CAST(:selectedSpaceId AS uuid))
                ORDER BY d.created_at DESC
                LIMIT 100
                """, new MapSqlParameterSource()
                .addValue("spaceIds", spaceIds)
                .addValue("selectedSpaceId", selectedSpaceId), this::mapDocumentSummary);
    }

    public Optional<DocumentSummary> findDocument(UUID documentId, List<UUID> spaceIds) {
        List<DocumentSummary> documents = jdbc.query("""
                SELECT d.id, d.source_id, s.space_id, s.type, s.status, d.title, d.source_uri, d.content_type, d.created_at
                FROM documents d
                JOIN data_sources s ON s.id = d.source_id
                WHERE d.id = :documentId
                  AND s.deleted_at IS NULL
                  AND s.space_id IN (:spaceIds)
                """, new MapSqlParameterSource()
                .addValue("documentId", documentId)
                .addValue("spaceIds", spaceIds), this::mapDocumentSummary);
        return documents.stream().findFirst();
    }

    public List<DocumentChunkDetail> listDocumentChunks(UUID documentId) {
        return jdbc.query("""
                SELECT id, chunk_index, content, metadata::text AS metadata, created_at
                FROM document_chunks
                WHERE document_id = :documentId
                ORDER BY chunk_index ASC
                """, new MapSqlParameterSource().addValue("documentId", documentId), (rs, rowNum) -> new DocumentChunkDetail(
                rs.getObject("id", UUID.class),
                rs.getInt("chunk_index"),
                rs.getString("content"),
                fromJson(rs.getString("metadata")),
                rs.getObject("created_at", OffsetDateTime.class)
        ));
    }

    public List<StoredDocumentForEnrichment> listDocumentsForSource(UUID sourceId) {
        return jdbc.query("""
                SELECT id, title, source_uri, content_type, metadata::text AS metadata
                FROM documents
                WHERE source_id = :sourceId
                ORDER BY created_at, title
                """, new MapSqlParameterSource().addValue("sourceId", sourceId), (rs, rowNum) -> new StoredDocumentForEnrichment(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("source_uri"),
                rs.getString("content_type"),
                fromJson(rs.getString("metadata"))
        ));
    }

    public void replaceDocumentContextChunks(UUID documentId, List<Chunk> chunks, List<List<Double>> embeddings) {
        jdbc.update("""
                DELETE FROM document_chunks
                WHERE document_id = :documentId
                  AND metadata ->> 'kind' = 'document_context'
                """, new MapSqlParameterSource().addValue("documentId", documentId));
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        Integer baseIndex = jdbc.queryForObject("""
                SELECT COALESCE(MAX(chunk_index), -1) + 1
                FROM document_chunks
                WHERE document_id = :documentId
                """, new MapSqlParameterSource().addValue("documentId", documentId), Integer.class);
        List<Chunk> reindexed = new ArrayList<>();
        int startIndex = baseIndex == null ? 0 : baseIndex;
        for (int i = 0; i < chunks.size(); i++) {
            reindexed.add(new Chunk(startIndex + i, chunks.get(i).content(), chunks.get(i).metadata()));
        }
        addChunks(documentId, reindexed, embeddings);
    }

    public Map<String, Object> documentMetadata(UUID documentId) {
        List<String> rows = jdbc.query("""
                SELECT metadata::text AS metadata
                FROM documents
                WHERE id = :documentId
                """, new MapSqlParameterSource().addValue("documentId", documentId), (rs, rowNum) -> rs.getString("metadata"));
        return rows.stream().findFirst().map(this::fromJson).orElse(Map.of());
    }

    public Optional<StoredObjectSummary> findStoredObjectSummary(UUID sourceId) {
        List<StoredObjectSummary> objects = jdbc.query("""
                SELECT bucket, original_filename, content_type, size_bytes
                FROM source_objects
                WHERE source_id = :sourceId
                """, new MapSqlParameterSource().addValue("sourceId", sourceId), (rs, rowNum) -> new StoredObjectSummary(
                rs.getString("bucket"),
                rs.getString("original_filename"),
                rs.getString("content_type"),
                rs.getLong("size_bytes")
        ));
        return objects.stream().findFirst();
    }

    public List<CrawlAuditSummary> listCrawlAudits(UUID sourceId) {
        return jdbc.query("""
                SELECT id, url, host, allowed_domain, robots_allowed, status_code, success,
                       reason_code, depth, referrer_url, normalized_url, content_type, metadata::text AS metadata,
                       message, started_at, finished_at
                FROM crawl_audit_logs
                WHERE source_id = :sourceId
                ORDER BY started_at DESC
                LIMIT 100
                """, new MapSqlParameterSource().addValue("sourceId", sourceId), (rs, rowNum) -> new CrawlAuditSummary(
                rs.getObject("id", UUID.class),
                rs.getString("url"),
                rs.getString("host"),
                rs.getBoolean("allowed_domain"),
                rs.getObject("robots_allowed", Boolean.class),
                rs.getObject("status_code", Integer.class),
                rs.getBoolean("success"),
                rs.getString("reason_code"),
                rs.getObject("depth", Integer.class),
                rs.getString("referrer_url"),
                rs.getString("normalized_url"),
                rs.getString("content_type"),
                fromJson(rs.getString("metadata")),
                rs.getString("message"),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class)
        ));
    }

    public List<SearchResult> search(String query, List<Double> embedding, SearchFilter filter, int limit, List<UUID> spaceIds, UUID selectedSpaceId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("query", query)
                .addValue("embedding", vectorLiteral(embedding))
                .addValue("limit", limit)
                .addValue("spaceIds", spaceIds)
                .addValue("selectedSpaceId", selectedSpaceId)
                .addValue("sourceType", cleanUpper(filter == null ? null : filter.sourceType()))
                .addValue("contentType", clean(filter == null ? null : filter.contentType()));

        return jdbc.query("""
                SELECT c.id AS chunk_id,
                       d.id AS document_id,
                       d.title,
                       d.source_uri,
                       s.type AS source_type,
                       d.content_type,
                       c.chunk_index,
                       c.content,
                       c.metadata::text AS metadata,
                       (
                         0.75 * (1 - (c.embedding <=> CAST(:embedding AS vector))) +
                         0.25 * ts_rank(c.search_vector, plainto_tsquery('simple', :query))
                       ) AS score
                FROM document_chunks c
                JOIN documents d ON d.id = c.document_id
                JOIN data_sources s ON s.id = d.source_id
                WHERE s.deleted_at IS NULL
                  AND s.space_id IN (:spaceIds)
                  AND (CAST(:selectedSpaceId AS uuid) IS NULL OR s.space_id = CAST(:selectedSpaceId AS uuid))
                  AND (CAST(:sourceType AS varchar) IS NULL OR s.type = CAST(:sourceType AS varchar))
                  AND (CAST(:contentType AS varchar) IS NULL OR d.content_type = CAST(:contentType AS varchar))
                ORDER BY score DESC
                LIMIT :limit
                """, params, this::mapSearchResult);
    }

    public List<SearchResult> keywordSearch(String query, SearchFilter filter, int limit, List<UUID> spaceIds, UUID selectedSpaceId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("query", query)
                .addValue("likeQuery", "%" + query + "%")
                .addValue("limit", limit)
                .addValue("spaceIds", spaceIds)
                .addValue("selectedSpaceId", selectedSpaceId)
                .addValue("sourceType", cleanUpper(filter == null ? null : filter.sourceType()))
                .addValue("contentType", clean(filter == null ? null : filter.contentType()));

        return jdbc.query("""
                SELECT c.id AS chunk_id,
                       d.id AS document_id,
                       d.title,
                       d.source_uri,
                       s.type AS source_type,
                       d.content_type,
                       c.chunk_index,
                       c.content,
                       c.metadata::text AS metadata,
                       (
                         ts_rank(c.search_vector, plainto_tsquery('simple', :query)) +
                         CASE WHEN d.title ILIKE :likeQuery THEN 0.30 ELSE 0 END +
                         CASE WHEN d.source_uri ILIKE :likeQuery THEN 0.14 ELSE 0 END +
                         CASE WHEN c.content ILIKE :likeQuery THEN 0.10 ELSE 0 END
                       ) AS score
                FROM document_chunks c
                JOIN documents d ON d.id = c.document_id
                JOIN data_sources s ON s.id = d.source_id
                WHERE s.deleted_at IS NULL
                  AND s.space_id IN (:spaceIds)
                  AND (CAST(:selectedSpaceId AS uuid) IS NULL OR s.space_id = CAST(:selectedSpaceId AS uuid))
                  AND (CAST(:sourceType AS varchar) IS NULL OR s.type = CAST(:sourceType AS varchar))
                  AND (CAST(:contentType AS varchar) IS NULL OR d.content_type = CAST(:contentType AS varchar))
                  AND (
                    c.search_vector @@ plainto_tsquery('simple', :query)
                    OR c.content ILIKE :likeQuery
                    OR d.title ILIKE :likeQuery
                    OR d.source_uri ILIKE :likeQuery
                  )
                ORDER BY score DESC
                LIMIT :limit
                """, params, this::mapSearchResult);
    }

    public List<SearchResult> adjacentChunks(
            UUID documentId,
            int centerChunkIndex,
            int radius,
            SearchFilter filter,
            List<UUID> spaceIds,
            UUID selectedSpaceId
    ) {
        if (documentId == null || radius <= 0 || spaceIds == null || spaceIds.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("documentId", documentId)
                .addValue("minChunkIndex", centerChunkIndex - radius)
                .addValue("maxChunkIndex", centerChunkIndex + radius)
                .addValue("centerChunkIndex", centerChunkIndex)
                .addValue("spaceIds", spaceIds)
                .addValue("selectedSpaceId", selectedSpaceId)
                .addValue("sourceType", cleanUpper(filter == null ? null : filter.sourceType()))
                .addValue("contentType", clean(filter == null ? null : filter.contentType()));

        return jdbc.query("""
                SELECT c.id AS chunk_id,
                       d.id AS document_id,
                       d.title,
                       d.source_uri,
                       s.type AS source_type,
                       d.content_type,
                       c.chunk_index,
                       c.content,
                       c.metadata::text AS metadata,
                       0.0 AS score
                FROM document_chunks c
                JOIN documents d ON d.id = c.document_id
                JOIN data_sources s ON s.id = d.source_id
                WHERE d.id = :documentId
                  AND s.deleted_at IS NULL
                  AND s.space_id IN (:spaceIds)
                  AND (CAST(:selectedSpaceId AS uuid) IS NULL OR s.space_id = CAST(:selectedSpaceId AS uuid))
                  AND (CAST(:sourceType AS varchar) IS NULL OR s.type = CAST(:sourceType AS varchar))
                  AND (CAST(:contentType AS varchar) IS NULL OR d.content_type = CAST(:contentType AS varchar))
                  AND c.chunk_index BETWEEN :minChunkIndex AND :maxChunkIndex
                  AND c.chunk_index <> :centerChunkIndex
                  AND c.metadata ->> 'kind' IS DISTINCT FROM 'document_context'
                ORDER BY ABS(c.chunk_index - :centerChunkIndex), c.chunk_index
                """, params, this::mapSearchResult);
    }

    public List<AdjacentChunkCandidate> adjacentChunksBatch(
            List<AdjacentChunkSeed> seeds,
            int radius,
            SearchFilter filter,
            List<UUID> spaceIds,
            UUID selectedSpaceId
    ) {
        if (seeds == null || seeds.isEmpty() || radius <= 0 || spaceIds == null || spaceIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, ChunkRange> ranges = new LinkedHashMap<>();
        for (AdjacentChunkSeed seed : seeds) {
            if (seed == null || seed.documentId() == null) {
                continue;
            }
            ranges.merge(
                    seed.documentId(),
                    new ChunkRange(seed.chunkIndex() - radius, seed.chunkIndex() + radius),
                    ChunkRange::merge
            );
        }
        if (ranges.isEmpty()) {
            return List.of();
        }
        int minChunkIndex = ranges.values().stream().mapToInt(ChunkRange::min).min().orElse(0);
        int maxChunkIndex = ranges.values().stream().mapToInt(ChunkRange::max).max().orElse(0);
        List<UUID> documentIds = new ArrayList<>(ranges.keySet());
        Set<UUID> seedChunkIds = seeds.stream().map(AdjacentChunkSeed::chunkId).collect(Collectors.toSet());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("documentIds", documentIds)
                .addValue("minChunkIndex", minChunkIndex)
                .addValue("maxChunkIndex", maxChunkIndex)
                .addValue("spaceIds", spaceIds)
                .addValue("selectedSpaceId", selectedSpaceId)
                .addValue("sourceType", cleanUpper(filter == null ? null : filter.sourceType()))
                .addValue("contentType", clean(filter == null ? null : filter.contentType()));

        List<SearchResult> candidates = jdbc.query("""
                SELECT c.id AS chunk_id,
                       d.id AS document_id,
                       d.title,
                       d.source_uri,
                       s.type AS source_type,
                       d.content_type,
                       c.chunk_index,
                       c.content,
                       c.metadata::text AS metadata,
                       0.0 AS score
                FROM document_chunks c
                JOIN documents d ON d.id = c.document_id
                JOIN data_sources s ON s.id = d.source_id
                WHERE d.id IN (:documentIds)
                  AND s.deleted_at IS NULL
                  AND s.space_id IN (:spaceIds)
                  AND (CAST(:selectedSpaceId AS uuid) IS NULL OR s.space_id = CAST(:selectedSpaceId AS uuid))
                  AND (CAST(:sourceType AS varchar) IS NULL OR s.type = CAST(:sourceType AS varchar))
                  AND (CAST(:contentType AS varchar) IS NULL OR d.content_type = CAST(:contentType AS varchar))
                  AND c.chunk_index BETWEEN :minChunkIndex AND :maxChunkIndex
                  AND c.metadata ->> 'kind' IS DISTINCT FROM 'document_context'
                ORDER BY d.id, c.chunk_index
                """, params, this::mapSearchResult);

        List<AdjacentChunkCandidate> output = new ArrayList<>();
        Set<UUID> emitted = new HashSet<>();
        for (SearchResult candidate : candidates) {
            if (seedChunkIds.contains(candidate.chunkId())) {
                continue;
            }
            AdjacentChunkSeed nearest = seeds.stream()
                    .filter(seed -> seed != null && candidate.documentId().equals(seed.documentId()))
                    .filter(seed -> Math.abs(candidate.chunkIndex() - seed.chunkIndex()) <= radius)
                    .min(Comparator.comparingInt(seed -> Math.abs(candidate.chunkIndex() - seed.chunkIndex())))
                    .orElse(null);
            if (nearest == null || !emitted.add(candidate.chunkId())) {
                continue;
            }
            output.add(new AdjacentChunkCandidate(
                    candidate,
                    nearest.chunkId(),
                    Math.abs(candidate.chunkIndex() - nearest.chunkIndex()),
                    nearest.score()
            ));
        }
        return output;
    }

    public List<ContextRelatedChunkCandidate> contextRelatedChunks(
            List<ContextChunkSeed> seeds,
            int limit,
            SearchFilter filter,
            List<UUID> spaceIds,
            UUID selectedSpaceId
    ) {
        if (seeds == null || seeds.isEmpty() || limit <= 0 || spaceIds == null || spaceIds.isEmpty()) {
            return List.of();
        }
        List<UUID> documentIds = seeds.stream()
                .filter(seed -> seed != null && seed.documentId() != null)
                .map(ContextChunkSeed::documentId)
                .distinct()
                .toList();
        if (documentIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> seedChunkIds = seeds.stream()
                .filter(seed -> seed != null && seed.chunkId() != null)
                .map(ContextChunkSeed::chunkId)
                .collect(Collectors.toSet());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("documentIds", documentIds)
                .addValue("spaceIds", spaceIds)
                .addValue("selectedSpaceId", selectedSpaceId)
                .addValue("sourceType", cleanUpper(filter == null ? null : filter.sourceType()))
                .addValue("contentType", clean(filter == null ? null : filter.contentType()));

        List<SearchResult> candidates = jdbc.query("""
                SELECT c.id AS chunk_id,
                       d.id AS document_id,
                       d.title,
                       d.source_uri,
                       s.type AS source_type,
                       d.content_type,
                       c.chunk_index,
                       c.content,
                       c.metadata::text AS metadata,
                       0.0 AS score
                FROM document_chunks c
                JOIN documents d ON d.id = c.document_id
                JOIN data_sources s ON s.id = d.source_id
                WHERE d.id IN (:documentIds)
                  AND s.deleted_at IS NULL
                  AND s.space_id IN (:spaceIds)
                  AND (CAST(:selectedSpaceId AS uuid) IS NULL OR s.space_id = CAST(:selectedSpaceId AS uuid))
                  AND (CAST(:sourceType AS varchar) IS NULL OR s.type = CAST(:sourceType AS varchar))
                  AND (CAST(:contentType AS varchar) IS NULL OR d.content_type = CAST(:contentType AS varchar))
                  AND c.metadata ->> 'kind' IS DISTINCT FROM 'document_context'
                ORDER BY d.id, c.chunk_index
                """, params, this::mapSearchResult);

        List<ContextRelatedChunkCandidate> output = new ArrayList<>();
        Set<UUID> emitted = new HashSet<>();
        for (SearchResult candidate : candidates) {
            if (seedChunkIds.contains(candidate.chunkId())) {
                continue;
            }
            ContextMatch match = bestContextMatch(candidate, seeds);
            if (match == null || !emitted.add(candidate.chunkId())) {
                continue;
            }
            output.add(new ContextRelatedChunkCandidate(candidate, match.seed().chunkId(), match.reason(), match.seed().score()));
            if (output.size() >= limit) {
                break;
            }
        }
        return output;
    }

    public void rebuildDocumentGraph(UUID sourceId) {
        rebuildDocumentGraphWithDiagnostic(sourceId);
    }

    public DocumentProcessingDiagnostic rebuildDocumentGraphWithDiagnostic(UUID sourceId) {
        long started = System.nanoTime();
        jdbc.update("DELETE FROM document_graph_edges WHERE source_id = :sourceId",
                new MapSqlParameterSource().addValue("sourceId", sourceId));
        jdbc.update("DELETE FROM document_graph_nodes WHERE source_id = :sourceId",
                new MapSqlParameterSource().addValue("sourceId", sourceId));

        List<GraphChunkRow> rows = jdbc.query("""
                SELECT d.id AS document_id, d.title, d.source_uri, d.content_type, d.metadata::text AS document_metadata,
                       c.id AS chunk_id, c.chunk_index, c.content, c.metadata::text AS metadata
                FROM documents d
                LEFT JOIN document_chunks c ON c.document_id = d.id
                WHERE d.source_id = :sourceId
                  AND (c.id IS NULL OR c.metadata ->> 'kind' IS DISTINCT FROM 'document_context')
                ORDER BY d.created_at, c.chunk_index
                """, new MapSqlParameterSource().addValue("sourceId", sourceId), (rs, rowNum) -> new GraphChunkRow(
                rs.getObject("document_id", UUID.class),
                rs.getString("title"),
                rs.getString("source_uri"),
                rs.getString("content_type"),
                fromJson(rs.getString("document_metadata")),
                rs.getObject("chunk_id", UUID.class),
                rs.getObject("chunk_index", Integer.class),
                rs.getString("content"),
                fromJson(rs.getString("metadata"))
        ));
        if (rows.isEmpty()) {
            return new DocumentProcessingDiagnostic(
                    "DOCUMENT_GRAPH_REBUILD", "Document graph builder", "SKIPPED", "ASYNC",
                    0, 0, 0, 0, 0, elapsedMs(started),
                    "No document chunks were available for graph rebuild.", Map.of()
            );
        }

        Map<String, UUID> nodeIds = new LinkedHashMap<>();
        List<DocumentGraphNode> nodes = new ArrayList<>();
        List<DocumentGraphEdge> edges = new ArrayList<>();
        addNode(nodes, "source:" + sourceId, "SOURCE", "Source", null, null, Map.of());

        UUID previousDocumentId = null;
        String previousChunkKey = null;
        for (GraphChunkRow row : rows) {
            String documentKey = "document:" + row.documentId();
            addNode(nodes, documentKey, "DOCUMENT", row.title(), row.documentId(), null, mergedMetadata(row.documentMetadata(), Map.of(
                    "sourceUri", row.sourceUri(),
                    "contentType", row.contentType()
            )));
            addEdge(edges, "source:" + sourceId, documentKey, "SOURCE_CONTAINS_DOCUMENT", 1.0, Map.of());
            if (row.chunkId() == null) {
                continue;
            }
            String sectionKey = sectionKey(row);
            addNode(nodes, sectionKey, "SECTION", sectionLabel(row), row.documentId(), null, sectionMetadata(row));
            addEdge(edges, documentKey, sectionKey, "DOCUMENT_CONTAINS_SECTION", 0.96, Map.of());
            String nodeType = chunkNodeType(row.metadata());
            String chunkKey = "chunk:" + row.chunkId();
            addNode(nodes, chunkKey, nodeType, chunkLabel(row), row.documentId(), row.chunkId(), row.metadata());
            addEdge(edges, sectionKey, chunkKey, "SECTION_CONTAINS_CHUNK", 1.0, Map.of("chunkIndex", row.chunkIndex()));
            if (previousDocumentId != null && previousDocumentId.equals(row.documentId()) && previousChunkKey != null) {
                addEdge(edges, previousChunkKey, chunkKey, "CHUNK_NEXT_CHUNK", 0.7, Map.of());
            }
            previousDocumentId = row.documentId();
            previousChunkKey = chunkKey;
            addStructureNodes(sourceId, nodes, edges, sectionKey, chunkKey, row);
            addTopicNodes(sourceId, nodes, edges, chunkKey, row);
            addEntityMentionNodes(sourceId, nodes, edges, chunkKey, row);
        }

        List<MapSqlParameterSource> nodeBatch = new ArrayList<>();
        for (DocumentGraphNode node : nodes) {
            UUID id = UUID.randomUUID();
            nodeIds.put(node.key(), id);
            nodeBatch.add(new MapSqlParameterSource()
                    .addValue("id", id)
                    .addValue("sourceId", sourceId)
                    .addValue("documentId", node.documentId())
                    .addValue("chunkId", node.chunkId())
                    .addValue("nodeKey", node.key())
                    .addValue("nodeType", node.type())
                    .addValue("label", node.label())
                    .addValue("metadata", toJson(node.metadata())));
        }
        if (!nodeBatch.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO document_graph_nodes (id, source_id, document_id, chunk_id, node_key, node_type, label, metadata)
                    VALUES (:id, :sourceId, :documentId, :chunkId, :nodeKey, :nodeType, :label, CAST(:metadata AS jsonb))
                    ON CONFLICT (source_id, node_key) DO NOTHING
                    """, nodeBatch.toArray(MapSqlParameterSource[]::new));
        }
        List<MapSqlParameterSource> edgeBatch = new ArrayList<>();
        for (DocumentGraphEdge edge : edges) {
            UUID sourceNodeId = nodeIds.get(edge.sourceKey());
            UUID targetNodeId = nodeIds.get(edge.targetKey());
            if (sourceNodeId == null || targetNodeId == null) {
                continue;
            }
            edgeBatch.add(new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("sourceId", sourceId)
                    .addValue("sourceNodeId", sourceNodeId)
                    .addValue("targetNodeId", targetNodeId)
                    .addValue("edgeType", edge.type())
                    .addValue("weight", edge.weight())
                    .addValue("metadata", toJson(edge.metadata())));
        }
        if (!edgeBatch.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO document_graph_edges (id, source_id, source_node_id, target_node_id, edge_type, weight, metadata)
                    VALUES (:id, :sourceId, :sourceNodeId, :targetNodeId, :edgeType, :weight, CAST(:metadata AS jsonb))
                    ON CONFLICT (source_id, source_node_id, target_node_id, edge_type) DO NOTHING
                    """, edgeBatch.toArray(MapSqlParameterSource[]::new));
        }
        return new DocumentProcessingDiagnostic(
                "DOCUMENT_GRAPH_REBUILD", "Document graph builder", "SUCCESS", "ASYNC",
                rows.size(), rows.size(), 0, nodes.size(), edgeBatch.size(), elapsedMs(started),
                "Document graph rebuild completed.", Map.of("sourceId", sourceId.toString())
        );
    }

    public List<SearchResult> graphExpandedChunks(List<UUID> seedChunkIds, int limit, List<UUID> spaceIds, UUID selectedSpaceId) {
        return graphExpandedChunks(seedChunkIds, limit, 1, spaceIds, selectedSpaceId);
    }

    public List<SearchResult> graphExpandedChunks(List<UUID> seedChunkIds, int limit, int maxHop, List<UUID> spaceIds, UUID selectedSpaceId) {
        if (seedChunkIds == null || seedChunkIds.isEmpty() || spaceIds == null || spaceIds.isEmpty()) {
            return List.of();
        }
        int safeMaxHop = Math.max(1, Math.min(maxHop, 3));
        return jdbc.query("""
                WITH RECURSIVE seed_nodes AS (
                    SELECT source_id, id
                    FROM document_graph_nodes
                    WHERE chunk_id IN (:seedChunkIds)
                ),
                graph_walk AS (
                    SELECT seed.source_id,
                           seed.id AS node_id,
                           seed.id AS previous_node_id,
                           0 AS depth,
                           1.0::double precision AS path_score,
                           ARRAY[seed.id] AS path,
                           NULL::varchar AS edge_type
                    FROM seed_nodes seed
                    UNION ALL
                    SELECT next_node.source_id,
                           next_node.id AS node_id,
                           walk.node_id AS previous_node_id,
                           walk.depth + 1 AS depth,
                           (walk.path_score * e.weight * POWER(0.75::double precision, GREATEST(walk.depth, 0))) AS path_score,
                           walk.path || next_node.id,
                           e.edge_type
                    FROM graph_walk walk
                    JOIN document_graph_edges e ON e.source_id = walk.source_id
                     AND (e.source_node_id = walk.node_id OR e.target_node_id = walk.node_id)
                    JOIN document_graph_nodes next_node ON next_node.source_id = e.source_id
                     AND next_node.id = CASE WHEN e.source_node_id = walk.node_id THEN e.target_node_id ELSE e.source_node_id END
                    WHERE walk.depth < :maxHop
                      AND next_node.id <> ALL(walk.path)
                ),
                expanded AS (
                    SELECT n.chunk_id,
                           MAX(walk.path_score) AS graph_score,
                           MIN(walk.depth) AS graph_depth,
                           MAX(COALESCE(walk.edge_type, 'DOCUMENT_GRAPH')) AS edge_type
                    FROM graph_walk walk
                    JOIN document_graph_nodes n ON n.source_id = walk.source_id
                     AND n.id = walk.node_id
                    WHERE walk.depth > 0
                      AND n.chunk_id IS NOT NULL
                      AND n.chunk_id NOT IN (:seedChunkIds)
                    GROUP BY n.chunk_id
                )
                SELECT c.id AS chunk_id,
                       d.id AS document_id,
                       d.title,
                       d.source_uri,
                       s.type AS source_type,
                       d.content_type,
                       c.chunk_index,
                       c.content,
                       c.metadata::text AS metadata,
                       e.graph_score AS score,
                       e.graph_depth,
                       e.edge_type AS graph_edge_type
                FROM expanded e
                JOIN document_chunks c ON c.id = e.chunk_id
                JOIN documents d ON d.id = c.document_id
                JOIN data_sources s ON s.id = d.source_id
                WHERE s.deleted_at IS NULL
                  AND s.space_id IN (:spaceIds)
                  AND (CAST(:selectedSpaceId AS uuid) IS NULL OR s.space_id = CAST(:selectedSpaceId AS uuid))
                ORDER BY e.graph_score DESC, e.graph_depth, c.chunk_index
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("seedChunkIds", seedChunkIds)
                .addValue("spaceIds", spaceIds)
                .addValue("selectedSpaceId", selectedSpaceId)
                .addValue("maxHop", safeMaxHop)
                .addValue("limit", Math.max(1, limit)), this::mapGraphSearchResult);
    }

    private ContextMatch bestContextMatch(SearchResult candidate, List<ContextChunkSeed> seeds) {
        Map<String, Object> metadata = candidate.metadata() == null ? Map.of() : candidate.metadata();
        String headingPath = string(metadata, "headingPath");
        String sectionTitle = string(metadata, "sectionTitle");
        String tableId = string(metadata, "tableId");
        Integer pageNumber = DocumentPageMetadata.canonicalPageNumber(metadata);
        for (ContextChunkSeed seed : seeds) {
            if (seed == null || !candidate.documentId().equals(seed.documentId())) {
                continue;
            }
            if (!seed.tableId().isBlank() && seed.tableId().equals(tableId)) {
                return new ContextMatch(seed, "same_table");
            }
            if (seed.pageNumber() != null && seed.pageNumber().equals(pageNumber)) {
                return new ContextMatch(seed, "same_page");
            }
            if (!seed.headingPath().isBlank() && seed.headingPath().equals(headingPath)) {
                return new ContextMatch(seed, "same_section");
            }
            if (!seed.sectionTitle().isBlank() && seed.sectionTitle().equals(sectionTitle)) {
                return new ContextMatch(seed, "same_section_title");
            }
        }
        return null;
    }

    private void addNode(List<DocumentGraphNode> nodes, String key, String type, String label, UUID documentId, UUID chunkId, Map<String, Object> metadata) {
        if (nodes.stream().noneMatch(node -> node.key().equals(key))) {
            nodes.add(new DocumentGraphNode(key, type, label == null || label.isBlank() ? key : label, documentId, chunkId, metadata == null ? Map.of() : metadata));
        }
    }

    private void addEdge(List<DocumentGraphEdge> edges, String sourceKey, String targetKey, String type, double weight, Map<String, Object> metadata) {
        if (sourceKey == null || targetKey == null || sourceKey.equals(targetKey)) {
            return;
        }
        edges.add(new DocumentGraphEdge(sourceKey, targetKey, type, weight, metadata == null ? Map.of() : metadata));
    }

    private String chunkNodeType(Map<String, Object> metadata) {
        String strategy = metadata == null ? "" : String.valueOf(metadata.getOrDefault("strategy", ""));
        String blockType = metadata == null ? "" : String.valueOf(metadata.getOrDefault("blockType", ""));
        if (strategy.contains("pdf") || metadata.containsKey("pageStart")) {
            return "PAGE";
        }
        if (strategy.contains("slide") || metadata.containsKey("slideStart")) {
            return "PAGE";
        }
        if ("section".equals(blockType) || metadata.containsKey("headingPath")) {
            return "SECTION";
        }
        return "SECTION";
    }

    private String chunkLabel(GraphChunkRow row) {
        String heading = row.metadata() == null ? "" : String.valueOf(row.metadata().getOrDefault("headingPath", ""));
        if (!heading.isBlank()) {
            return heading;
        }
        Integer page = DocumentPageMetadata.canonicalPageNumber(row.metadata());
        if (page != null) {
            return "Page " + page;
        }
        return row.title() + " #" + row.chunkIndex();
    }

    private String sectionKey(GraphChunkRow row) {
        String headingPath = string(row.metadata(), "headingPath");
        if (headingPath.isBlank()) {
            return "section:" + row.documentId() + ":document_root";
        }
        return "section:" + row.documentId() + ":" + headingPath.toLowerCase(java.util.Locale.ROOT);
    }

    private String sectionLabel(GraphChunkRow row) {
        String headingPath = string(row.metadata(), "headingPath");
        if (!headingPath.isBlank()) {
            return headingPath;
        }
        return row.title() == null || row.title().isBlank() ? "Document root" : row.title();
    }

    private Map<String, Object> sectionMetadata(GraphChunkRow row) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (row.documentMetadata() != null) {
            copyIfPresent(row.documentMetadata(), metadata, "schemaName");
            copyIfPresent(row.documentMetadata(), metadata, "documentType");
            copyIfPresent(row.documentMetadata(), metadata, "documentTypeConfidence");
        }
        if (row.metadata() != null) {
            copyIfPresent(row.metadata(), metadata, "headingPath");
            copyIfPresent(row.metadata(), metadata, "sectionTitle");
            Integer pageNumber = DocumentPageMetadata.canonicalPageNumber(row.metadata());
            if (pageNumber != null) {
                metadata.put("pageNumber", pageNumber);
            }
        }
        metadata.putIfAbsent("headingPath", "document_root");
        return metadata;
    }

    private Map<String, Object> mergedMetadata(Map<String, Object> first, Map<String, Object> second) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (first != null) {
            merged.putAll(first);
        }
        if (second != null) {
            merged.putAll(second);
        }
        return merged;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source == null ? null : source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private void addTopicNodes(UUID sourceId, List<DocumentGraphNode> nodes, List<DocumentGraphEdge> edges, String chunkKey, GraphChunkRow row) {
        List<String> topics = new ArrayList<>();
        addTopic(topics, row.title());
        if (row.metadata() != null) {
            addTopic(topics, String.valueOf(row.metadata().getOrDefault("headingPath", "")));
        }
        for (String topic : topics.stream().distinct().limit(3).toList()) {
            String entityKey = "entity:" + sourceId + ":" + topic.toLowerCase(java.util.Locale.ROOT);
            addNode(nodes, entityKey, "ENTITY", topic, null, null,
                    Map.of("entityType", "TOPIC", "entitySource", "heuristic_topic"));
            addEdge(edges, chunkKey, entityKey, "CHUNK_MENTIONS_ENTITY", 0.35,
                    Map.of("entityType", "TOPIC", "entitySource", "heuristic_topic"));
        }
    }

    private void addEntityMentionNodes(UUID sourceId, List<DocumentGraphNode> nodes, List<DocumentGraphEdge> edges, String chunkKey, GraphChunkRow row) {
        try {
            String text = (row.title() == null ? "" : row.title()) + "\n" + (row.content() == null ? "" : row.content());
            for (DocumentEntityMentionExtractor.EntityMention mention : entityMentionExtractor.extract(text, row.metadata())) {
                String normalizedValue = mention.value().toLowerCase(java.util.Locale.ROOT);
                String entityKey = "entity:" + sourceId + ":" + mention.entityType() + ":" + normalizedValue;
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("entityType", mention.entityType());
                metadata.put("entityValue", mention.value());
                metadata.put("entitySource", "heuristic");
                if (mention.schemaName() != null && !mention.schemaName().isBlank()) {
                    metadata.put("schemaName", mention.schemaName());
                }
                if (mention.documentType() != null && !mention.documentType().isBlank()) {
                    metadata.put("documentType", mention.documentType());
                }
                addNode(nodes, entityKey, "ENTITY", mention.value(), null, null, metadata);
                addEdge(edges, chunkKey, entityKey, "CHUNK_MENTIONS_ENTITY", 0.62, metadata);
            }
        } catch (RuntimeException ignored) {
            // Graph rebuild should not fail because optional heuristic entity extraction failed.
        }
    }

    private void addStructureNodes(UUID sourceId, List<DocumentGraphNode> nodes, List<DocumentGraphEdge> edges, String sectionKey, String chunkKey, GraphChunkRow row) {
        if (row.metadata() == null) {
            return;
        }
        String tableId = string(row.metadata(), "tableId");
        if (!tableId.isBlank()) {
            String tableKey = "table:" + row.documentId() + ":" + tableId.toLowerCase(java.util.Locale.ROOT);
            addNode(nodes, tableKey, "TABLE", tableId, row.documentId(), null, Map.of("tableId", tableId));
            addEdge(edges, sectionKey, tableKey, "SECTION_HAS_TABLE", 0.92, Map.of("relation", "same_table"));
            Integer rowNumber = integer(row.metadata().get("rowNumber"));
            if (rowNumber != null) {
                String rowKey = "table-row:" + row.documentId() + ":" + tableId.toLowerCase(java.util.Locale.ROOT) + ":" + rowNumber;
                addNode(nodes, rowKey, "TABLE_ROW", tableId + " row " + rowNumber, row.documentId(), null,
                        Map.of("tableId", tableId, "rowNumber", rowNumber));
                addEdge(edges, tableKey, rowKey, "TABLE_HAS_ROW", 0.90, Map.of("relation", "same_table_row"));
                addEdge(edges, rowKey, chunkKey, "SECTION_CONTAINS_CHUNK", 0.88, Map.of("relation", "same_table_row"));
            } else {
                addEdge(edges, tableKey, chunkKey, "SECTION_CONTAINS_CHUNK", 0.90, Map.of("relation", "same_table"));
            }
        }
        Integer pageNumber = DocumentPageMetadata.canonicalPageNumber(row.metadata());
        if (pageNumber != null) {
            String pageKey = "page:" + row.documentId() + ":" + pageNumber;
            addNode(nodes, pageKey, "PAGE", "Page " + pageNumber, row.documentId(), null, Map.of("pageNumber", pageNumber));
            addEdge(edges, pageKey, chunkKey, "SECTION_CONTAINS_CHUNK", 0.84, Map.of("relation", "same_page"));
        }
    }

    private void addTopic(List<String> topics, String value) {
        if (value == null) {
            return;
        }
        for (String token : value.split("[^\\p{IsHangul}\\p{IsAlphabetic}\\p{IsDigit}]+")) {
            if (token.length() >= 3 && token.length() <= 40) {
                topics.add(token);
            }
        }
    }

    private SearchResult mapSearchResult(ResultSet rs, int rowNum) throws SQLException {
        return new SearchResult(
                rs.getObject("chunk_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("title"),
                rs.getString("source_uri"),
                rs.getString("source_type"),
                rs.getString("content_type"),
                rs.getInt("chunk_index"),
                rs.getString("content"),
                fromJson(rs.getString("metadata")),
                rs.getDouble("score")
        );
    }

    private SearchResult mapGraphSearchResult(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> metadata = new LinkedHashMap<>(fromJson(rs.getString("metadata")));
        metadata.put("graphDepth", rs.getInt("graph_depth"));
        metadata.put("graphEdgeType", rs.getString("graph_edge_type"));
        return new SearchResult(
                rs.getObject("chunk_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("title"),
                rs.getString("source_uri"),
                rs.getString("source_type"),
                rs.getString("content_type"),
                rs.getInt("chunk_index"),
                rs.getString("content"),
                metadata,
                rs.getDouble("score")
        );
    }

    private DocumentSummary mapDocumentSummary(ResultSet rs, int rowNum) throws SQLException {
        return new DocumentSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("source_id", UUID.class),
                rs.getObject("space_id", UUID.class),
                rs.getString("type"),
                rs.getString("status"),
                rs.getString("title"),
                rs.getString("source_uri"),
                rs.getString("content_type"),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private StoredSource mapSource(ResultSet rs, int rowNum) throws SQLException {
        return new StoredSource(
                rs.getObject("id", UUID.class),
                SourceType.valueOf(rs.getString("type")),
                rs.getString("name"),
                rs.getString("location"),
                SourceStatus.valueOf(rs.getString("status"))
        );
    }

    private StoredObject mapStoredObject(ResultSet rs, int rowNum) throws SQLException {
        return new StoredObject(
                rs.getString("bucket"),
                rs.getString("object_key"),
                rs.getString("original_filename"),
                rs.getString("content_type"),
                rs.getLong("size_bytes")
        );
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid metadata.", ex);
        }
    }

    private DocumentIndexingJobSummary mapDocumentJobSummary(ResultSet rs, int rowNum) throws SQLException {
        return new DocumentIndexingJobSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("source_id", UUID.class),
                rs.getObject("space_id", UUID.class),
                rs.getString("job_type"),
                rs.getString("status"),
                rs.getInt("total_documents"),
                rs.getInt("processed_documents"),
                rs.getInt("total_chunks"),
                rs.getInt("reused_chunks"),
                rs.getInt("embedded_chunks"),
                rs.getString("error_message"),
                rs.getObject("searchable_at", OffsetDateTime.class),
                rs.getString("enrichment_status"),
                rs.getString("enrichment_message"),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private Map<String, Object> fromJson(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadata, new TypeReference<>() {
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

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String cleanUpper(String value) {
        String clean = clean(value);
        return clean == null ? null : clean.toUpperCase();
    }

    private String string(Map<String, Object> metadata, String key) {
        Object value = metadata == null ? null : metadata.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            String text = value == null ? "" : String.valueOf(value).trim();
            return text.isBlank() ? null : Integer.parseInt(text);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private long elapsedMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    public record ReusableDocument(UUID documentId, int chunkCount) {
    }

    private record GraphChunkRow(
            UUID documentId,
            String title,
            String sourceUri,
            String contentType,
            Map<String, Object> documentMetadata,
            UUID chunkId,
            Integer chunkIndex,
            String content,
            Map<String, Object> metadata
    ) {
    }

    public record AdjacentChunkSeed(UUID chunkId, UUID documentId, int chunkIndex, double score) {
    }

    public record AdjacentChunkCandidate(SearchResult result, UUID seedChunkId, int distance, double seedScore) {
    }

    public record ContextChunkSeed(
            UUID chunkId,
            UUID documentId,
            String headingPath,
            String sectionTitle,
            Integer pageNumber,
            String tableId,
            double score
    ) {
        public ContextChunkSeed {
            headingPath = headingPath == null ? "" : headingPath;
            sectionTitle = sectionTitle == null ? "" : sectionTitle;
            tableId = tableId == null ? "" : tableId;
        }
    }

    public record ContextRelatedChunkCandidate(SearchResult result, UUID seedChunkId, String reason, double seedScore) {
    }

    public record StoredDocumentForEnrichment(
            UUID documentId,
            String title,
            String sourceUri,
            String contentType,
            Map<String, Object> metadata
    ) {
    }

    private record ContextMatch(ContextChunkSeed seed, String reason) {
    }

    private record ChunkRange(int min, int max) {
        ChunkRange merge(ChunkRange other) {
            return new ChunkRange(Math.min(min, other.min()), Math.max(max, other.max()));
        }
    }
}
