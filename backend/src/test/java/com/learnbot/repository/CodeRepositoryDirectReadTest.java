package com.learnbot.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeRepositoryDirectReadTest {

    @Test
    void pathRangeReadNormalizesPathAndRequiresExactOverlappingActiveChunks() {
        NamedParameterJdbcTemplate jdbc = queryJdbc();
        CodeRepository repository = repository(jdbc);
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID selectedSpaceId = UUID.randomUUID();

        var results = repository.findActiveChunksByPathAndLineRange(
                repositoryId,
                ".\\backend\\src//main/./java/App.java",
                12,
                30,
                250,
                List.of(spaceId),
                selectedSpaceId
        );

        assertThat(results).isEmpty();
        Query query = captureQuery(jdbc);
        assertThat(query.sql()).contains(
                "WHERE c.active",
                "c.file_path = :filePath",
                "c.line_start <= :lineEnd",
                "c.line_end >= :lineStart",
                "r.space_id IN (:spaceIds)",
                "CAST(:selectedSpaceId AS uuid)",
                "CAST(:repositoryId AS uuid)"
        );
        assertThat(query.params().getValue("repositoryId")).isEqualTo(repositoryId);
        assertThat(query.params().getValue("filePath")).isEqualTo("backend/src/main/java/App.java");
        assertThat(query.params().getValue("lineStart")).isEqualTo(12);
        assertThat(query.params().getValue("lineEnd")).isEqualTo(30);
        assertThat(query.params().getValue("limit")).isEqualTo(100);
        assertThat(query.params().getValue("spaceIds")).isEqualTo(List.of(spaceId));
        assertThat(query.params().getValue("selectedSpaceId")).isEqualTo(selectedSpaceId);
    }

    @Test
    void pathRangeReadRejectsNonRelativeOrInvalidRangesBeforeQuerying() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        CodeRepository repository = repository(jdbc);

        assertThat(repository.findActiveChunksByPathAndLineRange(
                UUID.randomUUID(), "../outside.java", 1, 5, 10, List.of(UUID.randomUUID()), null
        )).isEmpty();
        assertThat(repository.findActiveChunksByPathAndLineRange(
                UUID.randomUUID(), "C:\\outside.java", 1, 5, 10, List.of(UUID.randomUUID()), null
        )).isEmpty();
        assertThat(repository.findActiveChunksByPathAndLineRange(
                UUID.randomUUID(), "src/App.java", 5, 4, 10, List.of(UUID.randomUUID()), null
        )).isEmpty();

        verify(jdbc, never()).query(
                anyString(),
                ArgumentMatchers.any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<CodeSearchResult>>any()
        );
    }

    @Test
    void fileSymbolListingIsBoundedToAnExactAuthorizedActivePath() {
        NamedParameterJdbcTemplate jdbc = queryJdbc();
        CodeRepository repository = repository(jdbc);
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID selectedSpaceId = UUID.randomUUID();

        repository.listActiveSymbolsByPath(
                repositoryId, ".\\src//app/./Worker.cs", 500, List.of(spaceId), selectedSpaceId);

        Query query = captureQuery(jdbc);
        assertThat(query.sql()).contains(
                "WHERE c.active",
                "c.file_path = :filePath",
                "NULLIF(c.symbol_name, '') IS NOT NULL",
                "NULLIF(c.method_name, '') IS NOT NULL",
                "r.space_id IN (:spaceIds)",
                "CAST(:selectedSpaceId AS uuid)",
                "CAST(:repositoryId AS uuid)"
        );
        assertThat(query.params().getValue("repositoryId")).isEqualTo(repositoryId);
        assertThat(query.params().getValue("filePath")).isEqualTo("src/app/Worker.cs");
        assertThat(query.params().getValue("limit")).isEqualTo(80);
        assertThat(query.params().getValue("spaceIds")).isEqualTo(List.of(spaceId));
        assertThat(query.params().getValue("selectedSpaceId")).isEqualTo(selectedSpaceId);
    }

    @Test
    void symbolDefinitionReadCanBeRestrictedToAnExactNormalizedPath() {
        NamedParameterJdbcTemplate jdbc = queryJdbc();
        CodeRepository repository = repository(jdbc);
        UUID repositoryId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID selectedSpaceId = UUID.randomUUID();

        repository.findSymbolDefinitions(
                repositoryId,
                " claimNext ",
                ".\\backend\\src/main/java/LocalAgentToolGatewayService.java",
                80,
                List.of(spaceId),
                selectedSpaceId
        );

        Query query = captureQuery(jdbc);
        assertThat(query.sql()).contains(
                "WHERE c.active",
                "r.deleted_at IS NULL",
                "c.file_path = CAST(:filePath AS text)",
                "lower(c.symbol_name) = lower(:symbol)",
                "r.space_id IN (:spaceIds)",
                "CAST(:selectedSpaceId AS uuid)",
                "CAST(:repositoryId AS uuid)"
        );
        assertThat(query.params().getValue("symbol")).isEqualTo("claimNext");
        assertThat(query.params().getValue("filePath"))
                .isEqualTo("backend/src/main/java/LocalAgentToolGatewayService.java");
        assertThat(query.params().getValue("limit")).isEqualTo(50);
        assertThat(query.params().getValue("spaceIds")).isEqualTo(List.of(spaceId));
        assertThat(query.params().getValue("selectedSpaceId")).isEqualTo(selectedSpaceId);
    }

    @Test
    void existingSymbolDefinitionApiUsesDefaultAuthorizedSpaceWhenNoneProvided() {
        NamedParameterJdbcTemplate jdbc = queryJdbc();
        CodeRepository repository = repository(jdbc);

        repository.findSymbolDefinitions(UUID.randomUUID(), "LocalAgentController", 10, List.of(), null);

        Query query = captureQuery(jdbc);
        assertThat(query.params().getValue("filePath")).isNull();
        assertThat(query.params().getValue("spaceIds")).isEqualTo(List.of(SecurityRepository.DEFAULT_SPACE_ID));
    }

    @Test
    void noPathSymbolReadMatchesOnlyExactDefinitionFields() {
        NamedParameterJdbcTemplate jdbc = queryJdbc();
        CodeRepository repository = repository(jdbc);

        repository.findSymbolDefinitions(
                UUID.randomUUID(),
                "LocalAgentController",
                10,
                List.of(UUID.randomUUID()),
                null
        );

        Query query = captureQuery(jdbc);
        assertThat(query.sql()).contains(
                "lower(c.symbol_name) = lower(:symbol)",
                "lower(c.method_name) = lower(:symbol)",
                "lower(c.class_name) = lower(:symbol)",
                "lower(c.control_name) = lower(:symbol)",
                "lower(c.event_name) = lower(:symbol)"
        );
        assertThat(query.sql()).doesNotContain("file_path ILIKE", ":likeQuery");
        assertThat(query.params().hasValue("likeQuery")).isFalse();
    }

    @Test
    void adjacentReadAnchorsToAnAuthorizedActiveChunkAndSameFile() {
        NamedParameterJdbcTemplate jdbc = queryJdbc();
        CodeRepository repository = repository(jdbc);
        UUID repositoryId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID selectedSpaceId = UUID.randomUUID();

        repository.findAdjacentActiveChunks(
                repositoryId,
                chunkId,
                99,
                500,
                List.of(spaceId),
                selectedSpaceId
        );

        Query query = captureQuery(jdbc);
        assertThat(query.sql()).contains(
                "WHERE c.id = :chunkId",
                "AND c.active",
                "JOIN code_chunks c ON c.repository_id = a.repository_id AND c.file_id = a.file_id",
                "ABS(c.chunk_index - a.chunk_index) <= :radius",
                "r.space_id IN (:spaceIds)",
                "CAST(:selectedSpaceId AS uuid)",
                "CAST(:repositoryId AS uuid)"
        );
        assertThat(query.params().getValue("repositoryId")).isEqualTo(repositoryId);
        assertThat(query.params().getValue("chunkId")).isEqualTo(chunkId);
        assertThat(query.params().getValue("radius")).isEqualTo(20);
        assertThat(query.params().getValue("limit")).isEqualTo(100);
        assertThat(query.params().getValue("spaceIds")).isEqualTo(List.of(spaceId));
        assertThat(query.params().getValue("selectedSpaceId")).isEqualTo(selectedSpaceId);
    }

    private NamedParameterJdbcTemplate queryJdbc() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(
                anyString(),
                ArgumentMatchers.any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<CodeSearchResult>>any()
        )).thenReturn(List.of());
        return jdbc;
    }

    private CodeRepository repository(NamedParameterJdbcTemplate jdbc) {
        return new CodeRepository(jdbc, new ObjectMapper(), mock(LearnBotProperties.class));
    }

    private Query captureQuery(NamedParameterJdbcTemplate jdbc) {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(
                sql.capture(),
                params.capture(),
                ArgumentMatchers.<RowMapper<CodeSearchResult>>any()
        );
        return new Query(sql.getValue(), params.getValue());
    }

    private record Query(String sql, MapSqlParameterSource params) {
    }
}
