package com.learnbot.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.domain.SourceStatus;
import com.learnbot.domain.SourceType;
import com.learnbot.dto.DocumentSummary;
import com.learnbot.dto.SearchFilter;
import com.learnbot.dto.SearchResult;
import com.learnbot.service.Chunk;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class DocumentRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DocumentRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public UUID createSource(SourceType type, String name, String location) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO data_sources (id, type, name, location, status)
                VALUES (:id, :type, :name, :location, :status)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("type", type.name())
                .addValue("name", name)
                .addValue("location", location)
                .addValue("status", SourceStatus.INDEXING.name()));
        return id;
    }

    public UUID createDocument(UUID sourceId, String title, String sourceUri, String contentType, Map<String, Object> metadata) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO documents (id, source_id, title, source_uri, content_type, metadata)
                VALUES (:id, :sourceId, :title, :sourceUri, :contentType, CAST(:metadata AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("sourceId", sourceId)
                .addValue("title", title)
                .addValue("sourceUri", sourceUri)
                .addValue("contentType", contentType)
                .addValue("metadata", toJson(metadata)));
        return id;
    }

    public void addChunks(UUID documentId, List<Chunk> chunks, List<List<Double>> embeddings) {
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            jdbc.update("""
                    INSERT INTO document_chunks (id, document_id, chunk_index, content, metadata, embedding)
                    VALUES (:id, :documentId, :chunkIndex, :content, CAST(:metadata AS jsonb), CAST(:embedding AS vector))
                    """, new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("documentId", documentId)
                    .addValue("chunkIndex", chunk.index())
                    .addValue("content", chunk.content())
                    .addValue("metadata", toJson(chunk.metadata()))
                    .addValue("embedding", vectorLiteral(embeddings.get(i))));
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

    public List<DocumentSummary> listDocuments() {
        return jdbc.query("""
                SELECT d.id, d.source_id, s.type, s.status, d.title, d.source_uri, d.content_type, d.created_at
                FROM documents d
                JOIN data_sources s ON s.id = d.source_id
                ORDER BY d.created_at DESC
                LIMIT 100
                """, (rs, rowNum) -> new DocumentSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("source_id", UUID.class),
                rs.getString("type"),
                rs.getString("status"),
                rs.getString("title"),
                rs.getString("source_uri"),
                rs.getString("content_type"),
                rs.getObject("created_at", OffsetDateTime.class)
        ));
    }

    public List<SearchResult> search(String query, List<Double> embedding, SearchFilter filter, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("query", query)
                .addValue("embedding", vectorLiteral(embedding))
                .addValue("limit", limit)
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
                       (
                         0.75 * (1 - (c.embedding <=> CAST(:embedding AS vector))) +
                         0.25 * ts_rank(c.search_vector, plainto_tsquery('simple', :query))
                       ) AS score
                FROM document_chunks c
                JOIN documents d ON d.id = c.document_id
                JOIN data_sources s ON s.id = d.source_id
                WHERE (CAST(:sourceType AS varchar) IS NULL OR s.type = CAST(:sourceType AS varchar))
                  AND (CAST(:contentType AS varchar) IS NULL OR d.content_type = CAST(:contentType AS varchar))
                ORDER BY score DESC
                LIMIT :limit
                """, params, this::mapSearchResult);
    }

    public List<SearchResult> keywordSearch(String query, SearchFilter filter, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("query", query)
                .addValue("likeQuery", "%" + query + "%")
                .addValue("limit", limit)
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
                       ts_rank(c.search_vector, plainto_tsquery('simple', :query)) AS score
                FROM document_chunks c
                JOIN documents d ON d.id = c.document_id
                JOIN data_sources s ON s.id = d.source_id
                WHERE (CAST(:sourceType AS varchar) IS NULL OR s.type = CAST(:sourceType AS varchar))
                  AND (CAST(:contentType AS varchar) IS NULL OR d.content_type = CAST(:contentType AS varchar))
                  AND (c.search_vector @@ plainto_tsquery('simple', :query) OR c.content ILIKE :likeQuery)
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
                rs.getDouble("score")
        );
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid metadata.", ex);
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
}
