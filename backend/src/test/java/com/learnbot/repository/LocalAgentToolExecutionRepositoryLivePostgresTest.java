package com.learnbot.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.CodeAgentLoopPreviewResponse;
import com.learnbot.dto.LocalAgentApprovalState;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolRequest;
import com.learnbot.dto.LocalAgentToolResponse;
import com.learnbot.dto.LocalAgentToolStatus;
import com.learnbot.dto.LocalAgentWorkspaceSummary;
import com.learnbot.service.CodeAgentLoopPreviewService;
import com.learnbot.service.LocalAgentGatewayService;
import com.learnbot.service.LocalAgentPatchReleaseAttempt;
import com.learnbot.service.LocalAgentToolGatewayService;
import com.learnbot.service.LocalAgentToolPusher;
import com.learnbot.service.LocalAgentToolExecution;
import com.learnbot.service.agentloop.CodeAgentLoopRunnerService;
import com.learnbot.service.localagent.LocalAgentApprovedExecutionFlowContract;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfSystemProperty(named = "learnbot.live-postgres-tests", matches = "true")
class LocalAgentToolExecutionRepositoryLivePostgresTest {

    @Test
    @SuppressWarnings("unchecked")
    void approvedExecutionFlowSurvivesClaimAndCompletedResponsePersistence() {
        NamedParameterJdbcTemplate jdbc = jdbc();
        LocalAgentToolExecutionRepository repository = new LocalAgentToolExecutionRepository(jdbc, new ObjectMapper());
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        List<UUID> requestIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        try {
            insertUser(jdbc, userId);
            createApproved(repository, requestIds.get(0), sessionId, userId, agentId, workspaceId,
                    LocalAgentToolName.PATCH_APPLY,
                    Map.of(
                            "sourceRequestId", sourceRequestId.toString(),
                            "releaseAttemptId", releaseAttemptId.toString(),
                            "mutationAllowed", true,
                            "dryRunOnly", false,
                            "diff", "--- a/README.md\n+++ b/README.md\n"
                    ));
            createApproved(repository, requestIds.get(1), sessionId, userId, agentId, workspaceId,
                    LocalAgentToolName.COMMAND_RUN_ALLOWED,
                    Map.of(
                            "sourceRequestId", sourceRequestId.toString(),
                            "releaseAttemptId", releaseAttemptId.toString(),
                            "mutationAllowed", true,
                            "commandId", "maven.backend.test"
                    ));
            createApproved(repository, requestIds.get(2), sessionId, userId, agentId, workspaceId,
                    LocalAgentToolName.GIT_STATUS,
                    Map.of(
                            "sourceRequestId", sourceRequestId.toString(),
                            "releaseAttemptId", releaseAttemptId.toString(),
                            "mutationAllowed", true
                    ));
            createApproved(repository, requestIds.get(3), sessionId, userId, agentId, workspaceId,
                    LocalAgentToolName.ROLLBACK_RESTORE,
                    Map.of(
                            "sourceRequestId", sourceRequestId.toString(),
                            "releaseAttemptId", releaseAttemptId.toString(),
                            "mutationAllowed", true,
                            "manifestId", "snap-flow"
                    ));

            assertClaimAndComplete(repository, requestIds.get(0), sessionId, userId, agentId, workspaceId,
                    LocalAgentToolName.PATCH_APPLY,
                    Map.of(
                            "mutationApplied", true,
                            "snapshotManifestId", "snap-flow",
                            "rollbackAvailable", true
                    ));
            assertClaimAndComplete(repository, requestIds.get(1), sessionId, userId, agentId, workspaceId,
                    LocalAgentToolName.COMMAND_RUN_ALLOWED,
                    Map.of("commandId", "maven.backend.test", "exitCode", 0));
            assertClaimAndComplete(repository, requestIds.get(2), sessionId, userId, agentId, workspaceId,
                    LocalAgentToolName.GIT_STATUS,
                    Map.of("clean", false, "branch", "main"));
            assertClaimAndComplete(repository, requestIds.get(3), sessionId, userId, agentId, workspaceId,
                    LocalAgentToolName.ROLLBACK_RESTORE,
                    Map.of("restored", true, "manifestId", "snap-flow"));

            List<LocalAgentApprovedExecutionFlowContract.Step> steps = requestIds.stream()
                    .map(id -> repository.find(id).orElseThrow())
                    .map(this::persistedStep)
                    .toList();

            Map<String, Object> summary = LocalAgentApprovedExecutionFlowContract.summarize(steps);

            assertThat(summary)
                    .containsEntry("ordered", true)
                    .containsEntry("identityConsistent", true)
                    .containsEntry("releaseAttemptLinked", true)
                    .containsEntry("allTerminal", true)
                    .containsEntry("requestCreationEnabled", false)
                    .containsEntry("pushEnabled", false)
                    .containsEntry("claimEnabled", false)
                    .containsEntry("resultIntakeEnabled", false)
                    .containsEntry("acknowledgementSaveEnabled", false)
                    .containsEntry("mutationAllowedForFollowup", false)
                    .containsEntry("readyForServerOrchestration", false);
            assertThat((List<String>) summary.get("expectedToolOrder"))
                    .containsExactly("patch.apply", "command.runAllowed", "git.status", "rollback.restore");
            assertThat((List<Map<String, Object>>) summary.get("steps"))
                    .extracting(step -> step.get("verificationStatus"))
                    .containsExactly("APPLIED", "PASSED", "OBSERVED", "RESTORED");
            assertThat(repository.claimNext(userId, agentId)).isEmpty();
        } finally {
            cleanup(jdbc, requestIds, userId);
        }
    }

    @Test
    void runnerReadOnlyQueuePersistsClaimCompletionAndLoopTimelineEvents() {
        ObjectMapper objectMapper = new ObjectMapper();
        NamedParameterJdbcTemplate jdbc = jdbc();
        LocalAgentToolExecutionRepository toolRepository = new LocalAgentToolExecutionRepository(jdbc, objectMapper);
        CodeAgentLoopTimelineRepository timelineRepository = new CodeAgentLoopTimelineRepository(jdbc, objectMapper);
        LocalAgentGatewayService gatewayService = mock(LocalAgentGatewayService.class);
        LocalAgentToolPusher toolPusher = mock(LocalAgentToolPusher.class);
        LocalAgentToolGatewayService gateway = new LocalAgentToolGatewayService(
                toolRepository,
                mock(LocalAgentMutationObservationIntakeRepository.class),
                mock(LocalAgentPatchReleaseAttemptRepository.class),
                timelineRepository,
                gatewayService,
                toolPusher
        );
        CodeAgentLoopRunnerService runner = new CodeAgentLoopRunnerService(
                new CodeAgentLoopPreviewService(timelineRepository),
                gateway
        );
        UUID userId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        try {
            insertUser(jdbc, userId);
            timelineRepository.createPreview(userId, "live runner read-only observation", new CodeAgentLoopPreviewResponse(
                    loopId,
                    repositoryId,
                    null,
                    "PREVIEW_ONLY",
                    6,
                    120,
                    false,
                    true,
                    false,
                    List.of(),
                    List.of(),
                    List.of("live runner read-only queue contract")
            ));
            timelineRepository.appendNextDecision(
                    userId,
                    repositoryId,
                    loopId,
                    new LocalAgentToolResponse(
                            loopId,
                            UUID.randomUUID(),
                            userId,
                            agentId,
                            workspaceId,
                            AgentExecutionTarget.USER_LOCAL_AGENT,
                            LocalAgentToolName.GIT_STATUS,
                            LocalAgentToolStatus.SUCCEEDED,
                            Map.of("clean", true),
                            null,
                            null,
                            OffsetDateTime.now().minusSeconds(2),
                            OffsetDateTime.now().minusSeconds(1),
                            List.of()
                    ),
                    Map.of("repositoryId", repositoryId.toString(), "loopId", loopId.toString())
            );
            when(gatewayService.isConnected(userId, agentId)).thenReturn(true);
            when(gatewayService.hasApprovedWorkspace(userId, workspaceId)).thenReturn(true);

            var enqueued = runner.enqueueReadOnlyNextStep(userId, repositoryId, loopId, agentId, workspaceId);
            var claimed = gateway.claimNext(userId, agentId).orElseThrow();
            LocalAgentToolResponse response = new LocalAgentToolResponse(
                    loopId,
                    claimed.requestId(),
                    userId,
                    agentId,
                    workspaceId,
                    AgentExecutionTarget.USER_LOCAL_AGENT,
                    LocalAgentToolName.GIT_STATUS,
                    LocalAgentToolStatus.SUCCEEDED,
                    Map.of("clean", true, "branch", "main"),
                    null,
                    null,
                    OffsetDateTime.now().minusSeconds(1),
                    OffsetDateTime.now(),
                    List.of("live runner read-only observation completed")
            );

            gateway.complete(response);

            var completed = toolRepository.find(claimed.requestId()).orElseThrow();
            List<String> latestEvents = jdbc.query("""
                    SELECT event_type
                    FROM code_agent_loop_timeline_events
                    WHERE timeline_id = :loopId
                    ORDER BY sequence_number DESC
                    LIMIT 2
                    """, new MapSqlParameterSource().addValue("loopId", loopId), (rs, rowNum) -> rs.getString("event_type"));
            Integer nextDecisionCount = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM code_agent_loop_timeline_events
                    WHERE timeline_id = :loopId
                      AND event_type = 'LOOP_NEXT_DECISION_RECORDED'
                    """, new MapSqlParameterSource().addValue("loopId", loopId), Integer.class);

            assertThat(enqueued.runnerDecision()).isEqualTo("ENQUEUED_READ_ONLY_OBSERVATION");
            assertThat(claimed.request().toolName()).isEqualTo(LocalAgentToolName.GIT_STATUS);
            assertThat(completed.status()).isEqualTo(LocalAgentToolStatus.SUCCEEDED);
            assertThat(completed.input())
                    .containsEntry("repositoryId", repositoryId.toString())
                    .containsEntry("loopId", loopId.toString())
                    .containsEntry("freshObservationOnly", true)
                    .containsEntry("mutationAllowed", false);
            assertThat(latestEvents).containsExactly(
                    "LOOP_NEXT_DECISION_RECORDED",
                    "LOCAL_AGENT_OBSERVATION_RESULT"
            );
            assertThat(nextDecisionCount).isGreaterThanOrEqualTo(2);
        } finally {
            cleanupLoop(jdbc, loopId);
            cleanup(jdbc, List.of(), userId);
        }
    }

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
    @SuppressWarnings("unchecked")
    void durableFreshObservationsFeedReadinessWhileSourcePatchStaysHeld() {
        ObjectMapper objectMapper = new ObjectMapper();
        NamedParameterJdbcTemplate jdbc = jdbc();
        LocalAgentToolExecutionRepository toolRepository = new LocalAgentToolExecutionRepository(jdbc, objectMapper);
        LocalAgentPatchReleaseAttemptRepository releaseAttemptRepository = new LocalAgentPatchReleaseAttemptRepository(jdbc, objectMapper);
        LocalAgentGatewayService gatewayService = new LocalAgentGatewayService();
        LocalAgentToolPusher toolPusher = mock(LocalAgentToolPusher.class);
        LocalAgentToolGatewayService gateway = new LocalAgentToolGatewayService(
                toolRepository,
                mock(LocalAgentMutationObservationIntakeRepository.class),
                releaseAttemptRepository,
                mock(CodeAgentLoopTimelineRepository.class),
                gatewayService,
                toolPusher
        );
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        try {
            insertUser(jdbc, userId);
            gatewayService.registerHeartbeat(
                    userId,
                    agentId,
                    "0.1.0",
                    List.of(LocalAgentToolName.PATCH_APPLY.wireName(), LocalAgentToolName.ROLLBACK_RESTORE.wireName()),
                    List.of(new LocalAgentWorkspaceSummary(workspaceId, "repo", "C:/work/repo", true))
            );
            LocalAgentToolRequest sourceRequest = new LocalAgentToolRequest(
                    sessionId,
                    userId,
                    agentId,
                    workspaceId,
                    AgentExecutionTarget.USER_LOCAL_AGENT,
                    LocalAgentToolName.PATCH_APPLY,
                    validatedPatchInput(),
                    LocalAgentApprovalState.REQUIRED,
                    OffsetDateTime.now().minusSeconds(5),
                    List.of("validated approved-held source")
            );
            LocalAgentToolExecution source = toolRepository.create(sourceRequestId, sourceRequest);
            source = toolRepository.updateApprovalDecision(
                    sourceRequestId,
                    userId,
                    LocalAgentApprovalState.APPROVED,
                    LocalAgentToolStatus.APPROVED_HELD,
                    "approved-held source for durable linked-evidence smoke"
            ).orElseThrow();
            LocalAgentPatchReleaseAttempt attempt = releaseAttemptRepository.createDisabled(
                    releaseAttemptId,
                    source,
                    120,
                    Map.of("sourceRequestId", sourceRequestId.toString(), "claimable", false),
                    List.of("release gate disabled")
            );

            List<UUID> freshRequestIds = gateway.enqueueReleaseAttemptFreshObservations(userId, sourceRequestId)
                    .stream()
                    .map(item -> item.requestId())
                    .toList();

            assertThat(freshRequestIds).hasSize(2);
            assertThat(freshRequestIds).doesNotContain(sourceRequestId);
            LocalAgentToolExecution repositoryObservation = toolRepository.find(freshRequestIds.get(0)).orElseThrow();
            LocalAgentToolExecution patchDryRun = toolRepository.find(freshRequestIds.get(1)).orElseThrow();
            assertThat(repositoryObservation.toolName()).isEqualTo(LocalAgentToolName.GIT_STATUS);
            assertThat(repositoryObservation.status()).isEqualTo(LocalAgentToolStatus.PENDING);
            assertThat(patchDryRun.toolName()).isEqualTo(LocalAgentToolName.PATCH_APPLY);
            assertThat(patchDryRun.status()).isEqualTo(LocalAgentToolStatus.APPROVED);
            assertThat(patchDryRun.input())
                    .containsEntry("sourceRequestId", sourceRequestId.toString())
                    .containsEntry("releaseAttemptId", releaseAttemptId.toString())
                    .containsEntry("dryRunOnly", true)
                    .containsEntry("mutationAllowed", false)
                    .containsEntry("freshObservationOnly", true);

            completeClaimed(gateway, freshRequestIds.get(0), sessionId, userId, agentId, workspaceId, LocalAgentToolName.GIT_STATUS,
                    Map.of(
                            "clean", true,
                            "repositoryIdentity", Map.of(
                                    "branch", "main",
                                    "headCommit", "abc123",
                                    "remoteUrl", "https://example.com/acme/learnbot.git"
                            )
                    ));
            completeClaimed(gateway, freshRequestIds.get(1), sessionId, userId, agentId, workspaceId, LocalAgentToolName.PATCH_APPLY,
                    patchDryRunOutput("src/App.java"));

            var readiness = gateway.inspectPatchExecutionReadiness(userId, sourceRequestId);
            LocalAgentToolExecution heldSource = toolRepository.find(sourceRequestId).orElseThrow();
            Map<String, Object> repositoryVerification = readiness.repositoryVerification();
            Map<String, Object> repositoryLinkage = (Map<String, Object>) repositoryVerification.get("observationLinkage");
            Map<String, Object> snapshotLinkage = (Map<String, Object>) readiness.snapshotReadiness().get("observationLinkage");

            assertThat(readiness.readyToRelease()).isFalse();
            assertThat(readiness.checks())
                    .filteredOn(check -> List.of(
                            "approvedHeld",
                            "diffPresent",
                            "targetFilesPresent",
                            "expectedFilesPresent",
                            "snapshotManifestPreview",
                            "rollbackRestorePreconditions",
                            "workspaceRepositoryVerified"
                    ).contains(check.key()))
                    .allMatch(check -> check.passed());
            assertThat(readiness.checks())
                    .filteredOn(check -> "releaseGateEnabled".equals(check.key()))
                    .singleElement()
                    .matches(check -> !check.passed());
            assertThat(repositoryLinkage)
                    .containsEntry("status", "RELEASE_ATTEMPT_LINKED")
                    .containsEntry("releaseAttemptLinked", true)
                    .containsEntry("sourceRequestId", sourceRequestId)
                    .containsEntry("releaseAttemptId", releaseAttemptId);
            assertThat(snapshotLinkage)
                    .containsEntry("status", "RELEASE_ATTEMPT_LINKED")
                    .containsEntry("releaseAttemptLinked", true);
            assertThat(readiness.snapshotReadiness()).containsEntry("status", "CREATED");
            assertThat(readiness.rollbackReadiness()).containsEntry("status", "RESTORE_VALIDATED");
            assertThat(readiness.patchExecutionGate())
                    .containsEntry("releaseGateEnabled", false)
                    .containsEntry("claimEnabled", false)
                    .containsEntry("mutationEnabled", false);
            assertThat(readiness.releaseAttemptModel().latestAttempt())
                    .containsEntry("id", releaseAttemptId)
                    .containsEntry("status", LocalAgentPatchReleaseAttemptRepository.DISABLED_STATUS)
                    .containsEntry("claimable", false);
            assertThat(heldSource.approvalState()).isEqualTo(LocalAgentApprovalState.APPROVED);
            assertThat(heldSource.status()).isEqualTo(LocalAgentToolStatus.APPROVED_HELD);
            assertThat(toolRepository.claimNext(userId, agentId)).isEmpty();
            assertThat(attempt.claimable()).isFalse();
        } finally {
            cleanupReleaseAttempt(jdbc, releaseAttemptId);
            cleanup(jdbc, List.of(), userId);
        }
    }

    @Test
    void durableLinkedEvidenceReleaseBoundaryRefusesGateDisabledWithoutClaimingSource() {
        ObjectMapper objectMapper = new ObjectMapper();
        NamedParameterJdbcTemplate jdbc = jdbc();
        LocalAgentToolExecutionRepository toolRepository = new LocalAgentToolExecutionRepository(jdbc, objectMapper);
        LocalAgentPatchReleaseAttemptRepository releaseAttemptRepository = new LocalAgentPatchReleaseAttemptRepository(jdbc, objectMapper);
        LocalAgentGatewayService gatewayService = new LocalAgentGatewayService();
        LocalAgentToolPusher toolPusher = mock(LocalAgentToolPusher.class);
        LocalAgentToolGatewayService gateway = new LocalAgentToolGatewayService(
                toolRepository,
                mock(LocalAgentMutationObservationIntakeRepository.class),
                releaseAttemptRepository,
                mock(CodeAgentLoopTimelineRepository.class),
                gatewayService,
                toolPusher
        );
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        try {
            insertUser(jdbc, userId);
            gatewayService.registerHeartbeat(
                    userId,
                    agentId,
                    "0.1.0",
                    List.of(LocalAgentToolName.PATCH_APPLY.wireName(), LocalAgentToolName.ROLLBACK_RESTORE.wireName()),
                    List.of(new LocalAgentWorkspaceSummary(workspaceId, "repo", "C:/work/repo", true))
            );
            LocalAgentToolRequest sourceRequest = new LocalAgentToolRequest(
                    sessionId,
                    userId,
                    agentId,
                    workspaceId,
                    AgentExecutionTarget.USER_LOCAL_AGENT,
                    LocalAgentToolName.PATCH_APPLY,
                    validatedPatchInput(),
                    LocalAgentApprovalState.REQUIRED,
                    OffsetDateTime.now().minusSeconds(5),
                    List.of("validated approved-held source")
            );
            LocalAgentToolExecution source = toolRepository.create(sourceRequestId, sourceRequest);
            source = toolRepository.updateApprovalDecision(
                    sourceRequestId,
                    userId,
                    LocalAgentApprovalState.APPROVED,
                    LocalAgentToolStatus.APPROVED_HELD,
                    "approved-held source for durable release-boundary smoke"
            ).orElseThrow();
            releaseAttemptRepository.createDisabled(
                    releaseAttemptId,
                    source,
                    120,
                    Map.of("sourceRequestId", sourceRequestId.toString(), "claimable", false),
                    List.of("release gate disabled")
            );
            List<UUID> freshRequestIds = gateway.enqueueReleaseAttemptFreshObservations(userId, sourceRequestId)
                    .stream()
                    .map(item -> item.requestId())
                    .toList();
            completeClaimed(gateway, freshRequestIds.get(0), sessionId, userId, agentId, workspaceId, LocalAgentToolName.GIT_STATUS,
                    Map.of(
                            "clean", true,
                            "repositoryIdentity", Map.of(
                                    "branch", "main",
                                    "headCommit", "abc123",
                                    "remoteUrl", "https://example.com/acme/learnbot.git"
                            )
                    ));
            completeClaimed(gateway, freshRequestIds.get(1), sessionId, userId, agentId, workspaceId, LocalAgentToolName.PATCH_APPLY,
                    patchDryRunOutput("src/App.java"));

            var boundary = gateway.inspectPatchReleaseBoundary(userId, sourceRequestId);
            LocalAgentToolExecution heldSource = toolRepository.find(sourceRequestId).orElseThrow();

            assertThat(boundary.status()).isEqualTo("RELEASE_REFUSED_GATE_DISABLED");
            assertThat(boundary.actionMode()).isEqualTo("REFUSAL_ONLY");
            assertThat(boundary.releaseGateEnabled()).isFalse();
            assertThat(boundary.claimEnabled()).isFalse();
            assertThat(boundary.writeHelperEnabled()).isFalse();
            assertThat(boundary.requestCreationEnabled()).isFalse();
            assertThat(boundary.pushEnabled()).isFalse();
            assertThat(boundary.claimable()).isFalse();
            assertThat(boundary.mutationAllowed()).isFalse();
            assertThat(boundary.applyEnabled()).isFalse();
            assertThat(boundary.testEnabled()).isFalse();
            assertThat(boundary.rollbackRestoreEnabled()).isFalse();
            assertThat(boundary.ragFreshnessUpdateEnabled()).isFalse();
            assertThat(boundary.patchExecutionGate())
                    .containsEntry("preconditionsPassed", true)
                    .containsEntry("releaseGateEnabled", false)
                    .containsEntry("claimEnabled", false)
                    .containsEntry("mutationEnabled", false);
            assertThat(boundary.releaseAttemptModel().created()).isTrue();
            assertThat(boundary.releaseAttemptModel().claimable()).isFalse();
            assertThat(boundary.releaseAttemptModel().latestAttempt())
                    .containsEntry("id", releaseAttemptId)
                    .containsEntry("status", LocalAgentPatchReleaseAttemptRepository.DISABLED_STATUS)
                    .containsEntry("claimable", false);
            assertThat(boundary.releaseAttemptModel().latestAttempt().get("mutationRequestCreationGate"))
                    .isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> creationGate = (Map<String, Object>) boundary.releaseAttemptModel()
                    .latestAttempt()
                    .get("mutationRequestCreationGate");
            assertThat(creationGate)
                    .containsEntry("expectedRequestCount", 4)
                    .containsEntry("durableMutationExecutionRowCount", 0)
                    .containsEntry("persistedRequestCount", 0)
                    .containsEntry("pushedRequestCount", 0)
                    .containsEntry("claimableRequestCount", 0);
            assertThat(boundary.releaseEnablementChecklist())
                    .containsEntry("releaseGateEnabled", false)
                    .containsEntry("claimable", false)
                    .containsEntry("mutationAllowed", false);
            assertThat(boundary.blockingReasons())
                    .contains(
                            "release gate is disabled",
                            "held patch request remains non-claimable",
                            "Local Agent request creation and push remain disabled"
                    );
            assertThat(heldSource.approvalState()).isEqualTo(LocalAgentApprovalState.APPROVED);
            assertThat(heldSource.status()).isEqualTo(LocalAgentToolStatus.APPROVED_HELD);
            assertThat(toolRepository.countMutationEnabledExecutionRowsForReleaseAttempt(userId, releaseAttemptId))
                    .isZero();
            assertThat(toolRepository.claimNext(userId, agentId)).isEmpty();
        } finally {
            cleanupReleaseAttempt(jdbc, releaseAttemptId);
            cleanup(jdbc, List.of(), userId);
        }
    }

    @Test
    void durableLinkedEvidenceCanReleaseHeldPatchThenClaimCompleteAndPersistMutationObservation() {
        ObjectMapper objectMapper = new ObjectMapper();
        NamedParameterJdbcTemplate jdbc = jdbc();
        LocalAgentToolExecutionRepository toolRepository = new LocalAgentToolExecutionRepository(jdbc, objectMapper);
        LocalAgentMutationObservationIntakeRepository mutationObservationRepository =
                new LocalAgentMutationObservationIntakeRepository(jdbc, objectMapper);
        LocalAgentPatchReleaseAttemptRepository releaseAttemptRepository = new LocalAgentPatchReleaseAttemptRepository(jdbc, objectMapper);
        LocalAgentGatewayService gatewayService = new LocalAgentGatewayService();
        LearnBotProperties properties = new LearnBotProperties();
        properties.getLocalAgent().setPatchExecutionReleaseEnabled(true);
        LocalAgentToolGatewayService gateway = new LocalAgentToolGatewayService(
                toolRepository,
                mutationObservationRepository,
                releaseAttemptRepository,
                mock(CodeAgentLoopTimelineRepository.class),
                gatewayService,
                mock(LocalAgentToolPusher.class),
                properties
        );
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        try {
            insertUser(jdbc, userId);
            gatewayService.registerHeartbeat(
                    userId,
                    agentId,
                    "0.1.0",
                    List.of(LocalAgentToolName.PATCH_APPLY.wireName(), LocalAgentToolName.ROLLBACK_RESTORE.wireName()),
                    List.of(new LocalAgentWorkspaceSummary(workspaceId, "repo", "C:/work/repo", true))
            );
            LocalAgentToolRequest sourceRequest = new LocalAgentToolRequest(
                    sessionId,
                    userId,
                    agentId,
                    workspaceId,
                    AgentExecutionTarget.USER_LOCAL_AGENT,
                    LocalAgentToolName.PATCH_APPLY,
                    validatedPatchInput(),
                    LocalAgentApprovalState.REQUIRED,
                    OffsetDateTime.now().minusSeconds(5),
                    List.of("validated approved-held source")
            );
            LocalAgentToolExecution source = toolRepository.create(sourceRequestId, sourceRequest);
            source = toolRepository.updateApprovalDecision(
                    sourceRequestId,
                    userId,
                    LocalAgentApprovalState.APPROVED,
                    LocalAgentToolStatus.APPROVED_HELD,
                    "approved-held source for durable release-to-claim smoke"
            ).orElseThrow();
            releaseAttemptRepository.createDisabled(
                    releaseAttemptId,
                    source,
                    120,
                    Map.of("sourceRequestId", sourceRequestId.toString(), "claimable", false),
                    List.of("release gate disabled")
            );
            List<UUID> freshRequestIds = gateway.enqueueReleaseAttemptFreshObservations(userId, sourceRequestId)
                    .stream()
                    .map(item -> item.requestId())
                    .toList();
            completeClaimed(gateway, freshRequestIds.get(0), sessionId, userId, agentId, workspaceId, LocalAgentToolName.GIT_STATUS,
                    Map.of(
                            "clean", true,
                            "repositoryIdentity", Map.of(
                                    "branch", "main",
                                    "headCommit", "abc123",
                                    "remoteUrl", "https://example.com/acme/learnbot.git"
                            )
                    ));
            completeClaimed(gateway, freshRequestIds.get(1), sessionId, userId, agentId, workspaceId, LocalAgentToolName.PATCH_APPLY,
                    patchDryRunOutput("src/App.java"));

            var released = gateway.releaseHeldPatchForExecution(userId, sourceRequestId);
            var claimed = gateway.claimNext(userId, agentId).orElseThrow();
            gateway.complete(new LocalAgentToolResponse(
                    sessionId,
                    sourceRequestId,
                    userId,
                    agentId,
                    workspaceId,
                    AgentExecutionTarget.USER_LOCAL_AGENT,
                    LocalAgentToolName.PATCH_APPLY,
                    LocalAgentToolStatus.SUCCEEDED,
                    Map.of(
                            "dryRun", false,
                            "mutationApplied", true,
                            "snapshotManifestId", "snap-1234",
                            "rollbackAvailable", true
                    ),
                    null,
                    null,
                    OffsetDateTime.now().minusSeconds(1),
                    OffsetDateTime.now(),
                    List.of("durable released patch completed")
            ));

            var completed = toolRepository.find(sourceRequestId).orElseThrow();
            var observation = mutationObservationRepository.findLatestAcceptedMutationObservationForReleaseAttempt(
                    userId,
                    sourceRequestId,
                    releaseAttemptId
            ).orElseThrow();
            var readiness = gateway.inspectPatchExecutionReadiness(userId, sourceRequestId);
            @SuppressWarnings("unchecked")
            Map<String, Object> finalMutationReportSummary = (Map<String, Object>) readiness.releaseAttemptModel()
                    .latestAttempt()
                    .get("finalMutationReportSummary");
            @SuppressWarnings("unchecked")
            Map<String, Object> ragFreshnessMarker = (Map<String, Object>) readiness.releaseAttemptModel()
                    .latestAttempt()
                    .get("ragFreshnessMarker");
            @SuppressWarnings("unchecked")
            Map<String, Object> finalAnswerPublicationHandoff = (Map<String, Object>) readiness.releaseAttemptModel()
                    .latestAttempt()
                    .get("finalAnswerPublicationHandoff");
            @SuppressWarnings("unchecked")
            Map<String, Object> acknowledgementSaveHandoff = (Map<String, Object>) readiness.releaseAttemptModel()
                    .latestAttempt()
                    .get("acknowledgementSaveHandoff");

            assertThat(released.status()).isEqualTo(LocalAgentToolStatus.APPROVED);
            assertThat(released.approvalState()).isEqualTo(LocalAgentApprovalState.APPROVED);
            assertThat(released.input())
                    .containsEntry("sourceRequestId", sourceRequestId.toString())
                    .containsEntry("releaseAttemptId", releaseAttemptId.toString())
                    .containsEntry("mutationAllowed", true)
                    .containsEntry("dryRunOnly", false);
            assertThat(claimed.requestId()).isEqualTo(sourceRequestId);
            assertThat(claimed.request().toolName()).isEqualTo(LocalAgentToolName.PATCH_APPLY);
            assertThat(claimed.request().input())
                    .containsEntry("mutationAllowed", true)
                    .containsEntry("dryRunOnly", false);
            assertThat(completed.status()).isEqualTo(LocalAgentToolStatus.SUCCEEDED);
            assertThat(completed.output()).containsKeys("mutationResultIntakeCandidate", "acceptedMutationObservation");
            assertThat(observation)
                    .containsEntry("schema", "learnbot.local-agent.accepted-mutation-observation.v1")
                    .containsEntry("status", "ACCEPTED")
                    .containsEntry("accepted", true)
                    .containsEntry("toolName", LocalAgentToolName.PATCH_APPLY.wireName())
                    .containsEntry("sourceRequestId", sourceRequestId.toString())
                    .containsEntry("releaseAttemptId", releaseAttemptId.toString())
                    .containsEntry("verificationStatus", "APPLIED")
                    .containsEntry("mutationApplied", true)
                    .containsEntry("snapshotManifestId", "snap-1234")
                    .containsEntry("rollbackAvailable", true)
                    .containsEntry("resultAggregationEnabled", false)
                    .containsEntry("publicationEnabled", false)
                    .containsEntry("acknowledgementSaveEnabled", false)
                    .containsEntry("ragFreshnessUpdateEnabled", false);
            assertThat(finalMutationReportSummary)
                    .containsEntry("schema", "learnbot.local-agent.final-mutation-report-summary.v1")
                    .containsEntry("status", "READY_SUMMARY_AUDIT_ONLY")
                    .containsEntry("summaryAvailable", true)
                    .containsEntry("acceptedMutationObserved", true)
                    .containsEntry("acceptedMutationObservationCount", 1)
                    .containsEntry("acceptedMutationObservationAcceptedCount", 1)
                    .containsEntry("finalMutationReportSummaryAvailable", true)
                    .containsEntry("finalReportGenerationEnabled", false)
                    .containsEntry("publicationEnabled", false)
                    .containsEntry("finalAnswerGenerationEnabled", false)
                    .containsEntry("acknowledgementSaveEnabled", false)
                    .containsEntry("ragFreshnessUpdateEnabled", false);
            assertThat(finalMutationReportSummary.get("sections")).isInstanceOf(List.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> reportSections = (List<Map<String, Object>>) finalMutationReportSummary.get("sections");
            assertThat(reportSections)
                    .extracting(section -> section.get("key"))
                    .containsExactly("changedFiles", "verification", "rollback", "ragFreshness", "residualRisk");
            assertThat(reportSections)
                    .filteredOn(section -> "changedFiles".equals(section.get("key")))
                    .singleElement()
                    .satisfies(section -> assertThat(section)
                            .containsEntry("status", "OBSERVED")
                            .containsEntry("mutationApplied", true)
                            .containsEntry("snapshotManifestId", "snap-1234"));
            assertThat(reportSections)
                    .filteredOn(section -> "ragFreshness".equals(section.get("key")))
                    .singleElement()
                    .satisfies(section -> assertThat(section)
                            .containsEntry("status", "STALE_INDEX_WARNING_REQUIRED")
                            .containsEntry("staleIndexRiskVisible", true)
                            .containsEntry("ragFreshnessUpdateEnabled", false));
            assertThat(ragFreshnessMarker)
                    .containsEntry("schema", "learnbot.local-agent.rag-freshness-marker.v1")
                    .containsEntry("status", "STALE_INDEX_WARNING_REQUIRED")
                    .containsEntry("markerAvailable", true)
                    .containsEntry("acceptedMutationObserved", true)
                    .containsEntry("staleIndexRiskVisible", true)
                    .containsEntry("targetFilesKnown", true)
                    .containsEntry("staleIndexPolicy", "REQUIRE_EXPECTED_HASH_OR_CONTEXT_MATCH")
                    .containsEntry("freshnessAction", "REQUIRE_PARTIAL_REINDEX_OR_STALE_WARNING")
                    .containsEntry("ragFreshnessUpdateEnabled", false)
                    .containsEntry("partialReindexEnabled", false)
                    .containsEntry("finalAnswerMustDiscloseStaleIndex", true)
                    .containsEntry("finalAnswerGenerationEnabled", false)
                    .containsEntry("acknowledgementSaveEnabled", false);
            assertThat(ragFreshnessMarker.get("targetFiles")).asList().containsExactly("src/App.java");
            assertThat(finalAnswerPublicationHandoff)
                    .containsEntry("schema", "learnbot.local-agent.final-answer-publication-handoff.v1")
                    .containsEntry("status", "READY_HANDOFF_AUDIT_ONLY_PUBLICATION_DISABLED")
                    .containsEntry("handoffAvailable", true)
                    .containsEntry("finalMutationReportSummaryAvailable", true)
                    .containsEntry("staleIndexDisclosureRequired", true)
                    .containsEntry("staleIndexDisclosureModeled", true)
                    .containsEntry("staleIndexPolicy", "REQUIRE_EXPECTED_HASH_OR_CONTEXT_MATCH")
                    .containsEntry("freshnessAction", "REQUIRE_PARTIAL_REINDEX_OR_STALE_WARNING")
                    .containsEntry("publicationEnabled", false)
                    .containsEntry("finalAnswerGenerationEnabled", false)
                    .containsEntry("finalAnswerDeliveryEnabled", false)
                    .containsEntry("acknowledgementSaveEnabled", false)
                    .containsEntry("ragFreshnessUpdateEnabled", false)
                    .containsEntry("partialReindexEnabled", false);
            assertThat(finalAnswerPublicationHandoff.get("targetFiles")).asList().containsExactly("src/App.java");
            assertThat(finalAnswerPublicationHandoff.get("finalAnswerSections")).asList()
                    .containsExactly("changedFiles", "verification", "rollback", "ragFreshness", "residualRisk");
            assertThat((String) finalAnswerPublicationHandoff.get("staleIndexDisclosureText"))
                    .contains("RAG may be stale")
                    .contains("partial reindex");
            assertThat(acknowledgementSaveHandoff)
                    .containsEntry("schema", "learnbot.local-agent.acknowledgement-save-handoff.v1")
                    .containsEntry("status", "READY_ACKNOWLEDGEMENT_AUDIT_ONLY_SAVE_DISABLED")
                    .containsEntry("handoffAvailable", true)
                    .containsEntry("finalAnswerPublicationHandoffAvailable", true)
                    .containsEntry("staleIndexDisclosureModeled", true)
                    .containsEntry("acknowledgementReceiptRequired", true)
                    .containsEntry("acknowledgementReceiptModeled", true)
                    .containsEntry("publicationEnabled", false)
                    .containsEntry("finalAnswerGenerationEnabled", false)
                    .containsEntry("finalAnswerDeliveryEnabled", false)
                    .containsEntry("conversationSaveEnabled", false)
                    .containsEntry("acknowledgementSaveEnabled", false)
                    .containsEntry("ragFreshnessUpdateEnabled", false)
                    .containsEntry("partialReindexEnabled", false);
            assertThat(acknowledgementSaveHandoff.get("targetFiles")).asList().containsExactly("src/App.java");
            assertThat(acknowledgementSaveHandoff.get("finalAnswerSections")).asList()
                    .containsExactly("changedFiles", "verification", "rollback", "ragFreshness", "residualRisk");
            assertThat((String) acknowledgementSaveHandoff.get("staleIndexDisclosureText"))
                    .contains("RAG may be stale")
                    .contains("partial reindex");
            assertThat(toolRepository.claimNext(userId, agentId)).isEmpty();
        } finally {
            cleanupReleaseAttempt(jdbc, releaseAttemptId);
            cleanup(jdbc, List.of(), userId);
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

    @Test
    void findsLatestCompletedApprovedExecutionFlowRowsForReleaseAttemptInToolOrder() {
        NamedParameterJdbcTemplate jdbc = jdbc();
        LocalAgentToolExecutionRepository repository = new LocalAgentToolExecutionRepository(jdbc, new ObjectMapper());
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID olderPatchId = UUID.randomUUID();
        UUID latestPatchId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        UUID statusId = UUID.randomUUID();
        UUID rollbackId = UUID.randomUUID();
        UUID ignoredOtherUserId = UUID.randomUUID();
        UUID ignoredRunningId = UUID.randomUUID();
        List<UUID> allRequestIds = List.of(
                olderPatchId,
                latestPatchId,
                commandId,
                statusId,
                rollbackId,
                ignoredOtherUserId,
                ignoredRunningId
        );
        try {
            insertUser(jdbc, userId);
            insertUser(jdbc, otherUserId);
            Map<String, Object> input = Map.of(
                    "sourceRequestId", sourceRequestId.toString(),
                    "releaseAttemptId", releaseAttemptId.toString(),
                    "mutationAllowed", true
            );
            createApproved(repository, olderPatchId, sessionId, userId, agentId, workspaceId, LocalAgentToolName.PATCH_APPLY, input);
            createApproved(repository, latestPatchId, sessionId, userId, agentId, workspaceId, LocalAgentToolName.PATCH_APPLY, input);
            createApproved(repository, commandId, sessionId, userId, agentId, workspaceId, LocalAgentToolName.COMMAND_RUN_ALLOWED, input);
            createApproved(repository, statusId, sessionId, userId, agentId, workspaceId, LocalAgentToolName.GIT_STATUS, input);
            createApproved(repository, rollbackId, sessionId, userId, agentId, workspaceId, LocalAgentToolName.ROLLBACK_RESTORE, input);
            createApproved(repository, ignoredOtherUserId, sessionId, otherUserId, agentId, workspaceId, LocalAgentToolName.GIT_STATUS, input);
            createApproved(repository, ignoredRunningId, sessionId, userId, agentId, workspaceId, LocalAgentToolName.ROLLBACK_RESTORE, input);

            complete(repository, olderPatchId, sessionId, userId, agentId, workspaceId, LocalAgentToolName.PATCH_APPLY,
                    Map.of("mutationApplied", true, "version", "older"), OffsetDateTime.now().minusSeconds(30));
            complete(repository, commandId, sessionId, userId, agentId, workspaceId, LocalAgentToolName.COMMAND_RUN_ALLOWED,
                    Map.of("exitCode", 0), OffsetDateTime.now().minusSeconds(20));
            complete(repository, statusId, sessionId, userId, agentId, workspaceId, LocalAgentToolName.GIT_STATUS,
                    Map.of("clean", false), OffsetDateTime.now().minusSeconds(10));
            complete(repository, rollbackId, sessionId, userId, agentId, workspaceId, LocalAgentToolName.ROLLBACK_RESTORE,
                    Map.of("restored", true), OffsetDateTime.now().minusSeconds(5));
            complete(repository, latestPatchId, sessionId, userId, agentId, workspaceId, LocalAgentToolName.PATCH_APPLY,
                    Map.of("mutationApplied", true, "version", "latest"), OffsetDateTime.now());
            complete(repository, ignoredOtherUserId, sessionId, otherUserId, agentId, workspaceId, LocalAgentToolName.GIT_STATUS,
                    Map.of("clean", true), OffsetDateTime.now());

            List<LocalAgentToolExecution> rows = repository.findCompletedApprovedExecutionFlowRowsForReleaseAttempt(userId, releaseAttemptId);

            assertThat(repository.countMutationEnabledExecutionRowsForReleaseAttempt(userId, releaseAttemptId))
                    .isEqualTo(6);
            assertThat(rows)
                    .extracting(LocalAgentToolExecution::id)
                    .containsExactly(latestPatchId, commandId, statusId, rollbackId);
            assertThat(rows)
                    .extracting(LocalAgentToolExecution::toolName)
                    .containsExactly(
                            LocalAgentToolName.PATCH_APPLY,
                            LocalAgentToolName.COMMAND_RUN_ALLOWED,
                            LocalAgentToolName.GIT_STATUS,
                            LocalAgentToolName.ROLLBACK_RESTORE
                    );
            assertThat(rows.get(0).output()).containsEntry("version", "latest");
        } finally {
            cleanup(jdbc, allRequestIds, userId);
            jdbc.update("DELETE FROM app_users WHERE id = :userId",
                    new MapSqlParameterSource().addValue("userId", otherUserId));
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

    private void createApproved(
            LocalAgentToolExecutionRepository repository,
            UUID requestId,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            LocalAgentToolName toolName,
            Map<String, Object> input
    ) {
        repository.create(requestId, new LocalAgentToolRequest(
                sessionId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                toolName,
                input,
                LocalAgentApprovalState.APPROVED,
                OffsetDateTime.now(),
                List.of("approved execution-flow live repository contract")
        ));
    }

    private void assertClaimAndComplete(
            LocalAgentToolExecutionRepository repository,
            UUID requestId,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            LocalAgentToolName toolName,
            Map<String, Object> output
    ) {
        var claimed = repository.claimNext(userId, agentId).orElseThrow();
        assertThat(claimed.id()).isEqualTo(requestId);
        assertThat(claimed.status()).isEqualTo(LocalAgentToolStatus.RUNNING);
        assertThat(claimed.toolName()).isEqualTo(toolName);
        repository.complete(new LocalAgentToolResponse(
                sessionId,
                requestId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                toolName,
                LocalAgentToolStatus.SUCCEEDED,
                output,
                null,
                null,
                OffsetDateTime.now().minusSeconds(1),
                OffsetDateTime.now(),
                List.of("approved execution-flow response persisted")
        ));
        assertThat(repository.find(requestId).orElseThrow().status()).isEqualTo(LocalAgentToolStatus.SUCCEEDED);
    }

    private void complete(
            LocalAgentToolExecutionRepository repository,
            UUID requestId,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            LocalAgentToolName toolName,
            Map<String, Object> output,
            OffsetDateTime finishedAt
    ) {
        repository.complete(new LocalAgentToolResponse(
                sessionId,
                requestId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                toolName,
                LocalAgentToolStatus.SUCCEEDED,
                output,
                null,
                null,
                finishedAt.minusSeconds(1),
                finishedAt,
                List.of("completed for durable row lookup")
        ));
    }

    private void completeClaimed(
            LocalAgentToolGatewayService gateway,
            UUID expectedRequestId,
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            LocalAgentToolName toolName,
            Map<String, Object> output
    ) {
        var claimed = gateway.claimNext(userId, agentId).orElseThrow();
        assertThat(claimed.requestId()).isEqualTo(expectedRequestId);
        assertThat(claimed.request().toolName()).isEqualTo(toolName);
        gateway.complete(new LocalAgentToolResponse(
                sessionId,
                expectedRequestId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                toolName,
                LocalAgentToolStatus.SUCCEEDED,
                output,
                null,
                null,
                OffsetDateTime.now().minusSeconds(1),
                OffsetDateTime.now(),
                List.of("durable linked-evidence observation completed")
        ));
    }

    private Map<String, Object> validatedPatchInput() {
        return Map.ofEntries(
                Map.entry("schemaVersion", 1),
                Map.entry("repositoryId", UUID.randomUUID().toString()),
                Map.entry("sourceRepository", Map.of(
                        "id", UUID.randomUUID().toString(),
                        "name", "learnbot",
                        "sourceType", "GIT",
                        "gitUrl", "https://example.com/acme/learnbot.git",
                        "branch", "main",
                        "lastIndexedCommit", "abc123"
                )),
                Map.entry("workspaceVerification", Map.of(
                        "status", "UNVERIFIED",
                        "blocking", true
                )),
                Map.entry("instruction", "fix"),
                Map.entry("diff", "--- a/src/App.java\n+++ b/src/App.java\n@@ -1 +1 @@\n-class App {}\n+class App { /* ok */ }\n"),
                Map.entry("targetFiles", List.of("src/App.java")),
                Map.entry("expectedFiles", List.of(Map.of(
                        "path", "src/App.java",
                        "sha256", "abc123",
                        "bytes", 13
                ))),
                Map.entry("requiresSnapshot", true),
                Map.entry("snapshotPolicy", Map.of(
                        "required", true,
                        "scope", "TARGET_FILES",
                        "location", "LOCAL_AGENT_MANAGED",
                        "createBeforeMutation", true,
                        "includeExpectedHashes", true
                )),
                Map.entry("rollbackPolicy", Map.of(
                        "required", true,
                        "tool", LocalAgentToolName.ROLLBACK_RESTORE.wireName(),
                        "restoreScope", "SNAPSHOT_TARGET_FILES",
                        "requiresUserApproval", true
                )),
                Map.entry("staleIndexPolicy", "REQUIRE_EXPECTED_HASH_OR_CONTEXT_MATCH")
        );
    }

    private Map<String, Object> patchDryRunOutput(String path) {
        return Map.of(
                "dryRun", true,
                "preflightPassed", true,
                "mutationApplied", false,
                "snapshotCreated", true,
                "snapshotObservation", Map.of(
                        "manifestPreview", Map.of(
                                "id", "snap-1234",
                                "version", 1,
                                "schema", "learnbot.local-agent.snapshot-manifest.v1",
                                "relativeManifestPath", "snap-1234/manifest.json",
                                "contentStrategy", "COPY_TARGET_FILES_BEFORE_MUTATION",
                                "created", true,
                                "writesPlanned", true,
                                "writesCompleted", true,
                                "files", List.of(Map.of(
                                        "path", path,
                                        "snapshotRelativePath", "files/" + path,
                                        "actualSha256", "abc123"
                                ))
                        )
                ),
                "rollbackObservation", Map.of(
                        "restored", false,
                        "restorePreconditions", List.of(
                                Map.of(
                                        "key", "snapshotManifestExists",
                                        "required", true,
                                        "previewOnly", true
                                ),
                                Map.of(
                                        "key", "userApprovalRequired",
                                        "required", true,
                                        "previewOnly", true
                                )
                        )
                )
        );
    }

    private LocalAgentApprovedExecutionFlowContract.Step persistedStep(LocalAgentToolExecution execution) {
        return new LocalAgentApprovedExecutionFlowContract.Step(
                new LocalAgentToolResponse(
                        execution.sessionId(),
                        execution.id(),
                        execution.userId(),
                        execution.agentId(),
                        execution.workspaceId(),
                        execution.executionTarget(),
                        execution.toolName(),
                        execution.status(),
                        execution.output(),
                        execution.failureCode(),
                        execution.error(),
                        execution.startedAt(),
                        execution.finishedAt(),
                        execution.responseWarnings()
                ),
                execution.input()
        );
    }

    private void cleanup(NamedParameterJdbcTemplate jdbc, List<UUID> requestIds, UUID userId) {
        if (!requestIds.isEmpty()) {
            jdbc.update("DELETE FROM local_agent_tool_executions WHERE id IN (:requestIds)",
                    new MapSqlParameterSource().addValue("requestIds", requestIds));
        } else {
            jdbc.update("DELETE FROM local_agent_tool_executions WHERE user_id = :userId",
                    new MapSqlParameterSource().addValue("userId", userId));
        }
        jdbc.update("DELETE FROM app_users WHERE id = :userId",
                new MapSqlParameterSource().addValue("userId", userId));
    }

    private void cleanupLoop(NamedParameterJdbcTemplate jdbc, UUID loopId) {
        jdbc.update("DELETE FROM code_agent_loop_timeline_events WHERE timeline_id = :loopId",
                new MapSqlParameterSource().addValue("loopId", loopId));
        jdbc.update("DELETE FROM code_agent_loop_timelines WHERE id = :loopId",
                new MapSqlParameterSource().addValue("loopId", loopId));
    }

    private void cleanupReleaseAttempt(NamedParameterJdbcTemplate jdbc, UUID releaseAttemptId) {
        jdbc.update("DELETE FROM local_agent_mutation_observation_intake WHERE release_attempt_id = :releaseAttemptId",
                new MapSqlParameterSource().addValue("releaseAttemptId", releaseAttemptId));
        jdbc.update("DELETE FROM local_agent_patch_release_attempts WHERE id = :releaseAttemptId",
                new MapSqlParameterSource().addValue("releaseAttemptId", releaseAttemptId));
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
