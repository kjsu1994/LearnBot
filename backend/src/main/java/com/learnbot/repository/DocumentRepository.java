package com.learnbot.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.config.LearnBotProperties;
import com.learnbot.domain.SourceStatus;
import com.learnbot.domain.SourceType;
import com.learnbot.dto.DocumentSummary;
import com.learnbot.dto.DocumentIndexingJobSummary;
import com.learnbot.dto.CrawlAuditSummary;
import com.learnbot.dto.DocumentChunkDetail;
import com.learnbot.dto.SearchFilter;
import com.learnbot.dto.SearchResult;
import com.learnbot.dto.StoredObjectSummary;
import com.learnbot.service.Chunk;
import com.learnbot.service.StoredObject;
import com.learnbot.service.StoredSource;
import com.fasterxml.jackson.core.type.TypeReference;
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
public class DocumentRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final LearnBotProperties properties;

    public DocumentRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper, LearnBotProperties properties) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.properties = properties;
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
                batch[i - start] = new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("documentId", documentId)
                        .addValue("chunkIndex", chunk.index())
                        .addValue("content", chunk.content())
                        .addValue("metadata", toJson(chunk.metadata()))
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
                SELECT gen_random_uuid(), :newDocumentId, chunk_index, content, metadata, embedding
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

    public List<DocumentIndexingJobSummary> listDocumentJobs(List<UUID> spaceIds, UUID selectedSpaceId) {
        return jdbc.query("""
                SELECT id, source_id, space_id, job_type, status, total_documents, processed_documents,
                       total_chunks, reused_chunks, embedded_chunks, error_message, started_at, finished_at, created_at
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
                       total_chunks, reused_chunks, embedded_chunks, error_message, started_at, finished_at, created_at
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
        jdbc.update("""
                INSERT INTO crawl_audit_logs (
                    id, source_id, url, host, allowed_domain, robots_allowed, status_code, success, message
                )
                VALUES (
                    :id, :sourceId, :url, :host, :allowedDomain, :robotsAllowed, :statusCode, :success, :message
                )
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("sourceId", sourceId)
                .addValue("url", url)
                .addValue("host", host)
                .addValue("allowedDomain", allowedDomain)
                .addValue("robotsAllowed", robotsAllowed)
                .addValue("statusCode", statusCode)
                .addValue("success", success)
                .addValue("message", message));
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
                SELECT id, url, host, allowed_domain, robots_allowed, status_code, success, message, started_at, finished_at
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

    public record ReusableDocument(UUID documentId, int chunkCount) {
    }
}
