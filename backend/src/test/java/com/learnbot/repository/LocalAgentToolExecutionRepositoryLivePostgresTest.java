package com.learnbot.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolRequest;
import com.learnbot.dto.LocalAgentToolResponse;
import com.learnbot.dto.LocalAgentToolStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "learnbot.live-postgres-tests", matches = "true")
class LocalAgentToolExecutionRepositoryLivePostgresTest {

    @Test
    void persistedDryRunRequestMovesFromApprovedToRunningToCompletedWithoutChangingSource() {
        NamedParameterJdbcTemplate jdbc = jdbc();
        LocalAgentToolExecutionRepository repository = new LocalAgentToolExecutionRepository(jdbc, new ObjectMapper());
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID dryRunRequestId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        try {
            insertUser(jdbc, userId);
            LocalAgentToolRequest sourceRequest = new LocalAgentToolRequest(
                    sessionId,
                    userId,
                    agentId,
                    workspaceId,
                    AgentExecutionTarget.USER_LOCAL_AGENT,
                    LocalAgentToolName.PATCH_APPLY,
                    Map.of("diff", "--- a/README.md\n+++ b/README.md\n"),
                    LocalAgentApprovalState.REQUIRED,
                    OffsetDateTime.now().minusSeconds(2),
                    List.of("source request stays held")
            );
            repository.create(sourceRequestId, sourceRequest);
            repository.updateApprovalDecision(
                    sourceRequestId,
                    userId,
                    LocalAgentApprovalState.APPROVED,
                    LocalAgentToolStatus.APPROVED_HELD,
                    "approved-held source for live repository smoke"
            );
            LocalAgentToolRequest dryRunRequest = new LocalAgentToolRequest(
                    sessionId,
                    userId,
                    agentId,
                    workspaceId,
                    AgentExecutionTarget.USER_LOCAL_AGENT,
                    LocalAgentToolName.PATCH_APPLY,
                    Map.of(
                            "sourceRequestId", sourceRequestId.toString(),
                            "dryRunOnly", true,
                            "mutationAllowed", false
                    ),
                    LocalAgentApprovalState.APPROVED,
                    OffsetDateTime.now(),
                    List.of("dry-run clone")
            );
            repository.create(dryRunRequestId, dryRunRequest);

            var claimed = repository.claimNext(userId, agentId).orElseThrow();

            assertThat(claimed.id()).isEqualTo(dryRunRequestId);
            assertThat(claimed.status()).isEqualTo(LocalAgentToolStatus.RUNNING);
            assertThat(claimed.input())
                    .containsEntry("sourceRequestId", sourceRequestId.toString())
                    .containsEntry("dryRunOnly", true)
                    .containsEntry("mutationAllowed", false);
            OffsetDateTime leaseExpiresAt = jdbc.queryForObject("""
                    SELECT lease_expires_at
                    FROM local_agent_tool_executions
                    WHERE id = :id
                    """, new MapSqlParameterSource().addValue("id", dryRunRequestId), OffsetDateTime.class);
            assertThat(leaseExpiresAt).isAfter(OffsetDateTime.now());
            repository.complete(new LocalAgentToolResponse(
                    sessionId,
                    dryRunRequestId,
                    userId,
                    agentId,
                    workspaceId,
                    AgentExecutionTarget.USER_LOCAL_AGENT,
                    LocalAgentToolName.PATCH_APPLY,
                    LocalAgentToolStatus.SUCCEEDED,
                    Map.of(
                            "dryRun", true,
                            "mutationApplied", false,
                            "sourceRequestId", sourceRequestId.toString()
                    ),
                    null,
                    null,
                    OffsetDateTime.now().minusSeconds(1),
                    OffsetDateTime.now(),
                    List.of("dry-run completed")
            ));

            var completed = repository.find(dryRunRequestId).orElseThrow();
            var source = repository.find(sourceRequestId).orElseThrow();
            assertThat(completed.status()).isEqualTo(LocalAgentToolStatus.SUCCEEDED);
            assertThat(completed.output())
                    .containsEntry("dryRun", true)
                    .containsEntry("mutationApplied", false)
                    .containsEntry("sourceRequestId", sourceRequestId.toString());
            assertThat(completed.finishedAt()).isNotNull();
            assertThat(source.status()).isEqualTo(LocalAgentToolStatus.APPROVED_HELD);
            assertThat(source.approvalState()).isEqualTo(LocalAgentApprovalState.APPROVED);
            assertThat(repository.claimNext(userId, agentId)).isEmpty();
        } finally {
            cleanup(jdbc, dryRunRequestId, sourceRequestId, userId);
        }
    }

    @Test
    void lateCompletionDoesNotOverwriteTimedOutExecution() {
        NamedParameterJdbcTemplate jdbc = jdbc();
        LocalAgentToolExecutionRepository repository = new LocalAgentToolExecutionRepository(jdbc, new ObjectMapper());
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        try {
            insertUser(jdbc, userId);
            LocalAgentToolRequest request = new LocalAgentToolRequest(
                    sessionId,
                    userId,
                    agentId,
                    workspaceId,
                    AgentExecutionTarget.USER_LOCAL_AGENT,
                    LocalAgentToolName.FILE_READ,
                    Map.of("path", "README.md"),
                    LocalAgentApprovalState.NOT_REQUIRED,
                    OffsetDateTime.now(),
                    List.of()
            );
            repository.create(requestId, request);
            repository.claimNext(userId, agentId).orElseThrow();
            jdbc.update("""
                    UPDATE local_agent_tool_executions
                    SET status = 'TIMED_OUT',
                        failure_code = 'TIMEOUT',
                        error = 'lease timed out',
                        finished_at = now()
                    WHERE id = :id
                    """, new MapSqlParameterSource().addValue("id", requestId));

            repository.complete(new LocalAgentToolResponse(
                    sessionId,
                    requestId,
                    userId,
                    agentId,
                    workspaceId,
                    AgentExecutionTarget.USER_LOCAL_AGENT,
                    LocalAgentToolName.FILE_READ,
                    LocalAgentToolStatus.SUCCEEDED,
                    Map.of("content", "late"),
                    null,
                    null,
                    OffsetDateTime.now().minusSeconds(1),
                    OffsetDateTime.now(),
                    List.of("late success")
            ));

            var completed = repository.find(requestId).orElseThrow();
            assertThat(completed.status()).isEqualTo(LocalAgentToolStatus.TIMED_OUT);
            assertThat(completed.error()).isEqualTo("lease timed out");
        } finally {
            cleanup(jdbc, requestId, requestId, userId);
        }
    }

    @Test
    void findsLatestAcceptedMutationObservationForReleaseAttempt() {
        NamedParameterJdbcTemplate jdbc = jdbc();
        LocalAgentToolExecutionRepository repository = new LocalAgentToolExecutionRepository(jdbc, new ObjectMapper());
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        try {
            insertUser(jdbc, userId);
            LocalAgentToolRequest request = new LocalAgentToolRequest(
                    sessionId,
                    userId,
                    agentId,
                    workspaceId,
                    AgentExecutionTarget.USER_LOCAL_AGENT,
                    LocalAgentToolName.PATCH_APPLY,
                    Map.of(
                            "sourceRequestId", sourceRequestId.toString(),
                            "releaseAttemptId", releaseAttemptId.toString(),
                            "mutationAllowed", true,
                            "dryRunOnly", false
                    ),
                    LocalAgentApprovalState.APPROVED,
                    OffsetDateTime.now(),
                    List.of("mutation result observation")
            );
            repository.create(requestId, request);
            repository.claimNext(userId, agentId).orElseThrow();
            repository.complete(new LocalAgentToolResponse(
                    sessionId,
                    requestId,
                    userId,
                    agentId,
                    workspaceId,
                    AgentExecutionTarget.USER_LOCAL_AGENT,
                    LocalAgentToolName.PATCH_APPLY,
                    LocalAgentToolStatus.SUCCEEDED,
                    Map.of(
                            "acceptedMutationObservation", Map.ofEntries(
                                    Map.entry("schema", "learnbot.local-agent.accepted-mutation-observation.v1"),
                                    Map.entry("status", "ACCEPTED"),
                                    Map.entry("accepted", true),
                                    Map.entry("toolName", "patch.apply"),
                                    Map.entry("sourceRequestId", sourceRequestId.toString()),
                                    Map.entry("releaseAttemptId", releaseAttemptId.toString()),
                                    Map.entry("acceptedObservationPersistenceEnabled", false),
                                    Map.entry("resultAggregationEnabled", false),
                                    Map.entry("publicationEnabled", false),
                                    Map.entry("acknowledgementSaveEnabled", false),
                                    Map.entry("ragFreshnessUpdateEnabled", false)
                            )
                    ),
                    null,
                    null,
                    OffsetDateTime.now().minusSeconds(1),
                    OffsetDateTime.now(),
                    List.of("accepted observation stored in response output")
            ));

            var observation = repository.findLatestAcceptedMutationObservationForReleaseAttempt(
                    userId,
                    sourceRequestId,
                    releaseAttemptId
            ).orElseThrow();

            assertThat(observation)
                    .containsEntry("schema", "learnbot.local-agent.accepted-mutation-observation.v1")
                    .containsEntry("status", "ACCEPTED")
                    .containsEntry("accepted", true)
                    .containsEntry("sourceRequestId", sourceRequestId.toString())
                    .containsEntry("releaseAttemptId", releaseAttemptId.toString())
                    .containsEntry("acceptedObservationPersistenceEnabled", false)
                    .containsEntry("publicationEnabled", false)
                    .containsEntry("ragFreshnessUpdateEnabled", false);
        } finally {
            cleanup(jdbc, requestId, requestId, userId);
        }
    }

    private NamedParameterJdbcTemplate jdbc() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/learnbot"));
        dataSource.setUsername(System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "learnbot"));
        dataSource.setPassword(System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "learnbot"));
        return new NamedParameterJdbcTemplate(dataSource);
    }

    private void insertUser(NamedParameterJdbcTemplate jdbc, UUID userId) {
        jdbc.update("""
                INSERT INTO app_users (id, email, password_hash, display_name, role, status)
                VALUES (:id, :email, 'live-test', 'Live Test', 'USER', 'ACTIVE')
                """, new MapSqlParameterSource()
                .addValue("id", userId)
                .addValue("email", "local-agent-live-" + userId + "@example.test"));
    }

    private void cleanup(NamedParameterJdbcTemplate jdbc, UUID dryRunRequestId, UUID sourceRequestId, UUID userId) {
        jdbc.update("DELETE FROM local_agent_tool_executions WHERE id IN (:dryRunRequestId, :sourceRequestId)",
                new MapSqlParameterSource()
                        .addValue("dryRunRequestId", dryRunRequestId)
                        .addValue("sourceRequestId", sourceRequestId));
        jdbc.update("DELETE FROM app_users WHERE id = :userId",
                new MapSqlParameterSource().addValue("userId", userId));
    }
}
