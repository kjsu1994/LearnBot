package com.learnbot.service.localagent;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.dto.LocalAgentFailureCode;
import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolResponse;
import com.learnbot.dto.LocalAgentToolStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LocalAgentMutationResultClassifierTest {
    @Test
    void enrichesApprovedMutationPatchResultWithoutEnablingIntake() {
        UUID requestId = UUID.randomUUID();
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        LocalAgentToolResponse response = response(
                requestId,
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentToolStatus.SUCCEEDED,
                Map.of(
                        "mutationApplied", true,
                        "snapshotManifestId", "snap-1",
                        "rollbackAvailable", true
                ),
                null
        );

        LocalAgentToolResponse enriched = LocalAgentMutationResultClassifier.enrich(response, Map.of(
                "sourceRequestId", sourceRequestId.toString(),
                "releaseAttemptId", releaseAttemptId.toString(),
                "mutationAllowed", true,
                "dryRunOnly", false
        ));

        assertThat(enriched.output()).containsKey("mutationResultIntakeCandidate");
        @SuppressWarnings("unchecked")
        Map<String, Object> candidate = (Map<String, Object>) enriched.output().get("mutationResultIntakeCandidate");
        assertThat(candidate)
                .containsEntry("schema", "learnbot.local-agent.mutation-result-intake-candidate.v1")
                .containsEntry("status", "OBSERVED")
                .containsEntry("toolName", "patch.apply")
                .containsEntry("sourceRequestId", sourceRequestId.toString())
                .containsEntry("releaseAttemptId", releaseAttemptId.toString())
                .containsEntry("mutationApplied", true)
                .containsEntry("snapshotManifestId", "snap-1")
                .containsEntry("verificationStatus", "APPLIED")
                .containsEntry("acceptanceStatus", "ACCEPTED")
                .containsEntry("resultIntakeEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false)
                .containsEntry("mutationAllowedForFollowup", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> accepted = (Map<String, Object>) enriched.output().get("acceptedMutationObservation");
        assertThat(accepted)
                .containsEntry("schema", "learnbot.local-agent.accepted-mutation-observation.v1")
                .containsEntry("status", "ACCEPTED")
                .containsEntry("accepted", true)
                .containsEntry("toolName", "patch.apply")
                .containsEntry("sourceRequestId", sourceRequestId.toString())
                .containsEntry("releaseAttemptId", releaseAttemptId.toString())
                .containsEntry("acceptedObservationPersistenceEnabled", false)
                .containsEntry("resultAggregationEnabled", false)
                .containsEntry("publicationEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("ragFreshnessUpdateEnabled", false);
    }

    @Test
    void enrichesFailedAllowedCommandAsTerminalFailure() {
        UUID releaseAttemptId = UUID.randomUUID();
        LocalAgentToolResponse response = response(
                UUID.randomUUID(),
                LocalAgentToolName.COMMAND_RUN_ALLOWED,
                LocalAgentToolStatus.FAILED,
                Map.of("commandId", "maven.backend.test", "exitCode", 1),
                LocalAgentFailureCode.TOOL_FAILED
        );

        LocalAgentToolResponse enriched = LocalAgentMutationResultClassifier.enrich(response, Map.of(
                "releaseAttemptId", releaseAttemptId.toString(),
                "mutationAllowed", true
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> candidate = (Map<String, Object>) enriched.output().get("mutationResultIntakeCandidate");
        assertThat(candidate)
                .containsEntry("status", "OBSERVED_TERMINAL_FAILURE")
                .containsEntry("toolName", "command.runAllowed")
                .containsEntry("verificationStatus", "FAILED")
                .containsEntry("acceptanceStatus", "REJECTED_MISSING_LINKAGE")
                .containsEntry("resultPersistenceEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> accepted = (Map<String, Object>) enriched.output().get("acceptedMutationObservation");
        assertThat(accepted)
                .containsEntry("status", "REJECTED_MISSING_LINKAGE")
                .containsEntry("accepted", false)
                .containsEntry("acceptedObservationPersistenceEnabled", false);
    }

    @Test
    void ignoresReadOnlyResponseWithoutMutationContext() {
        LocalAgentToolResponse response = response(
                UUID.randomUUID(),
                LocalAgentToolName.GIT_STATUS,
                LocalAgentToolStatus.SUCCEEDED,
                Map.of("branch", "main"),
                null
        );

        LocalAgentToolResponse enriched = LocalAgentMutationResultClassifier.enrich(response, Map.of());

        assertThat(enriched).isSameAs(response);
        assertThat(enriched.output()).doesNotContainKey("mutationResultIntakeCandidate");
    }

    @Test
    void rejectsDryRunCandidateEvenWhenReleaseAttemptIsLinked() {
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        LocalAgentToolResponse response = response(
                UUID.randomUUID(),
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentToolStatus.SUCCEEDED,
                Map.of("dryRun", true, "mutationApplied", false),
                null
        );

        LocalAgentToolResponse enriched = LocalAgentMutationResultClassifier.enrich(response, Map.of(
                "sourceRequestId", sourceRequestId.toString(),
                "releaseAttemptId", releaseAttemptId.toString(),
                "dryRunOnly", true,
                "mutationAllowed", false
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> candidate = (Map<String, Object>) enriched.output().get("mutationResultIntakeCandidate");
        @SuppressWarnings("unchecked")
        Map<String, Object> accepted = (Map<String, Object>) enriched.output().get("acceptedMutationObservation");
        assertThat(candidate).containsEntry("acceptanceStatus", "REJECTED_DRY_RUN");
        assertThat(accepted)
                .containsEntry("status", "REJECTED_DRY_RUN")
                .containsEntry("accepted", false)
                .containsEntry("publicationEnabled", false);
    }

    @Test
    void summarizesApprovedExecutionFlowWithoutEnablingServerOrchestration() {
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> linkedInput = Map.of(
                "sourceRequestId", sourceRequestId.toString(),
                "releaseAttemptId", releaseAttemptId.toString(),
                "mutationAllowed", true,
                "dryRunOnly", false
        );

        Map<String, Object> summary = LocalAgentApprovedExecutionFlowContract.summarize(List.of(
                new LocalAgentApprovedExecutionFlowContract.Step(
                        response(sessionId, userId, agentId, workspaceId, UUID.randomUUID(), LocalAgentToolName.PATCH_APPLY, LocalAgentToolStatus.SUCCEEDED, Map.of(
                                "mutationApplied", true,
                                "snapshotManifestId", "snap-1",
                                "rollbackAvailable", true
                        ), null),
                        linkedInput
                ),
                new LocalAgentApprovedExecutionFlowContract.Step(
                        response(sessionId, userId, agentId, workspaceId, UUID.randomUUID(), LocalAgentToolName.COMMAND_RUN_ALLOWED, LocalAgentToolStatus.SUCCEEDED, Map.of(
                                "commandId", "maven.backend.test",
                                "exitCode", 0
                        ), null),
                        linkedInput
                ),
                new LocalAgentApprovedExecutionFlowContract.Step(
                        response(sessionId, userId, agentId, workspaceId, UUID.randomUUID(), LocalAgentToolName.GIT_STATUS, LocalAgentToolStatus.SUCCEEDED, Map.of(
                                "clean", false,
                                "branch", "main"
                        ), null),
                        linkedInput
                ),
                new LocalAgentApprovedExecutionFlowContract.Step(
                        response(sessionId, userId, agentId, workspaceId, UUID.randomUUID(), LocalAgentToolName.ROLLBACK_RESTORE, LocalAgentToolStatus.SUCCEEDED, Map.of(
                                "restored", true,
                                "manifestId", "snap-1"
                        ), null),
                        linkedInput
                )
        ));

        assertThat(summary)
                .containsEntry("schema", "learnbot.local-agent.approved-execution-flow-contract.v1")
                .containsEntry("executionTarget", "USER_LOCAL_AGENT")
                .containsEntry("stepCount", 4)
                .containsEntry("ordered", true)
                .containsEntry("identityConsistent", true)
                .containsEntry("releaseAttemptLinked", true)
                .containsEntry("requestCreationEnabled", false)
                .containsEntry("pushEnabled", false)
                .containsEntry("claimEnabled", false)
                .containsEntry("resultIntakeEnabled", false)
                .containsEntry("acknowledgementSaveEnabled", false)
                .containsEntry("mutationAllowedForFollowup", false)
                .containsEntry("readyForServerOrchestration", false);
        assertThat(summary.get("expectedToolOrder"))
                .asList()
                .containsExactly("patch.apply", "command.runAllowed", "git.status", "rollback.restore");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) summary.get("steps");
        assertThat(steps)
                .extracting(step -> step.get("toolName"))
                .containsExactly("patch.apply", "command.runAllowed", "git.status", "rollback.restore");
        assertThat(steps)
                .extracting(step -> step.get("verificationStatus"))
                .containsExactly("APPLIED", "PASSED", "OBSERVED", "RESTORED");
        assertThat(steps)
                .extracting(step -> step.get("acceptanceStatus"))
                .containsExactly("ACCEPTED", "ACCEPTED", "ACCEPTED", "ACCEPTED");
        assertThat(steps)
                .allSatisfy(step -> assertThat(step)
                        .containsEntry("resultIntakeEnabled", false)
                        .containsEntry("acknowledgementSaveEnabled", false)
                        .containsEntry("mutationAllowedForFollowup", false));
    }

    private LocalAgentToolResponse response(
            UUID requestId,
            LocalAgentToolName toolName,
            LocalAgentToolStatus status,
            Map<String, Object> output,
            LocalAgentFailureCode failureCode
    ) {
        return response(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), requestId, toolName, status, output, failureCode);
    }

    private LocalAgentToolResponse response(
            UUID sessionId,
            UUID userId,
            UUID agentId,
            UUID workspaceId,
            UUID requestId,
            LocalAgentToolName toolName,
            LocalAgentToolStatus status,
            Map<String, Object> output,
            LocalAgentFailureCode failureCode
    ) {
        return new LocalAgentToolResponse(
                sessionId,
                requestId,
                userId,
                agentId,
                workspaceId,
                AgentExecutionTarget.USER_LOCAL_AGENT,
                toolName,
                status,
                output,
                failureCode,
                failureCode == null ? null : "failed",
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of()
        );
    }
}
