package com.learnbot.service.localagent;

import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class LocalAgentApprovedExecutionFlowContract {
    private static final List<LocalAgentToolName> EXPECTED_ORDER = List.of(
            LocalAgentToolName.PATCH_APPLY,
            LocalAgentToolName.COMMAND_RUN_ALLOWED,
            LocalAgentToolName.GIT_STATUS,
            LocalAgentToolName.ROLLBACK_RESTORE
    );

    private LocalAgentApprovedExecutionFlowContract() {
    }

    public static Map<String, Object> summarize(List<Step> steps) {
        List<Step> safeSteps = steps == null ? List.of() : steps;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.approved-execution-flow-contract.v1");
        result.put("executionTarget", "USER_LOCAL_AGENT");
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimEnabled", false);
        result.put("resultIntakeEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("mutationAllowedForFollowup", false);
        result.put("expectedToolOrder", EXPECTED_ORDER.stream().map(LocalAgentToolName::wireName).toList());

        List<Map<String, Object>> stepSummaries = new ArrayList<>();
        for (int index = 0; index < safeSteps.size(); index++) {
            stepSummaries.add(stepSummary(index, safeSteps.get(index)));
        }
        result.put("steps", stepSummaries);
        result.put("stepCount", stepSummaries.size());
        result.put("ordered", ordered(safeSteps));
        result.put("identityConsistent", identityConsistent(safeSteps));
        result.put("releaseAttemptLinked", releaseAttemptLinked(safeSteps));
        result.put("approvalRequestLinked", approvalRequestLinked(safeSteps));
        result.put("postRetryVerification", postRetryVerification(safeSteps));
        result.put("allTerminal", safeSteps.stream().allMatch(step -> step.response().finishedAt() != null));
        result.put("readyForServerOrchestration", false);
        result.put("message", "Approved Local Agent execution-flow responses are modeled for server-side contract verification only; request creation, push, claim, result intake, acknowledgement save, rollback restore, and follow-up mutation remain disabled.");
        return result;
    }

    private static Map<String, Object> stepSummary(int index, Step step) {
        LocalAgentToolResponse enriched = LocalAgentMutationResultClassifier.enrich(step.response(), step.requestInput());
        Map<String, Object> candidate = mapValue(enriched.output().get("mutationResultIntakeCandidate"));
        Map<String, Object> accepted = mapValue(enriched.output().get("acceptedMutationObservation"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", index);
        result.put("toolName", step.response().toolName().wireName());
        result.put("requestId", step.response().requestId());
        result.put("sourceRequestId", step.requestInput().get("sourceRequestId"));
        result.put("releaseAttemptId", step.requestInput().get("releaseAttemptId"));
        result.put("approvalRequestId", step.requestInput().get("approvalRequestId"));
        result.put("status", step.response().status().name());
        result.put("verificationStatus", candidate.get("verificationStatus"));
        result.put("acceptanceStatus", accepted.get("status"));
        result.put("accepted", accepted.get("accepted"));
        result.put("resultIntakeEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("mutationAllowedForFollowup", false);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static boolean ordered(List<Step> steps) {
        if (steps.size() != 3 && steps.size() != EXPECTED_ORDER.size()) {
            return false;
        }
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).response().toolName() != EXPECTED_ORDER.get(index)) {
                return false;
            }
        }
        return true;
    }

    private static boolean identityConsistent(List<Step> steps) {
        if (steps.isEmpty()) {
            return false;
        }
        LocalAgentToolResponse first = steps.get(0).response();
        return steps.stream().allMatch(step -> {
            LocalAgentToolResponse response = step.response();
            return Objects.equals(response.sessionId(), first.sessionId())
                    && Objects.equals(response.userId(), first.userId())
                    && Objects.equals(response.agentId(), first.agentId())
                    && Objects.equals(response.workspaceId(), first.workspaceId());
        });
    }

    private static boolean releaseAttemptLinked(List<Step> steps) {
        if (steps.isEmpty()) {
            return false;
        }
        Object sourceRequestId = steps.get(0).requestInput().get("sourceRequestId");
        Object releaseAttemptId = steps.get(0).requestInput().get("releaseAttemptId");
        return hasText(sourceRequestId)
                && hasText(releaseAttemptId)
                && steps.stream().allMatch(step ->
                Objects.equals(step.requestInput().get("sourceRequestId"), sourceRequestId)
                        && Objects.equals(step.requestInput().get("releaseAttemptId"), releaseAttemptId));
    }

    private static boolean approvalRequestLinked(List<Step> steps) {
        if (steps.isEmpty()) {
            return false;
        }
        Object approvalRequestId = steps.get(0).requestInput().get("approvalRequestId");
        return hasText(approvalRequestId)
                && steps.stream().allMatch(step ->
                Objects.equals(step.requestInput().get("approvalRequestId"), approvalRequestId));
    }

    private static Map<String, Object> postRetryVerification(List<Step> steps) {
        Map<String, Object> result = new LinkedHashMap<>();
        Step commandStep = steps.stream()
                .filter(step -> step.response().toolName() == LocalAgentToolName.COMMAND_RUN_ALLOWED)
                .findFirst()
                .orElse(null);
        boolean releaseLinked = releaseAttemptLinked(steps);
        boolean approvalLinked = approvalRequestLinked(steps);
        boolean terminal = commandStep != null && commandStep.response().finishedAt() != null;
        boolean passed = commandStep != null
                && "PASSED".equals(verificationStatus(commandStep));
        result.put("schema", "learnbot.local-agent.post-retry-verification.v1");
        result.put("toolName", LocalAgentToolName.COMMAND_RUN_ALLOWED.wireName());
        result.put("observed", commandStep != null);
        result.put("terminal", terminal);
        result.put("passed", passed);
        result.put("approvalRequestLinked", approvalLinked);
        result.put("releaseAttemptLinked", releaseLinked);
        result.put("approvalRequestId", commandStep == null ? null : commandStep.requestInput().get("approvalRequestId"));
        result.put("releaseAttemptId", commandStep == null ? null : commandStep.requestInput().get("releaseAttemptId"));
        result.put("sourceRequestId", commandStep == null ? null : commandStep.requestInput().get("sourceRequestId"));
        result.put("partialReindexMarkerRequired", passed && approvalLinked && releaseLinked);
        result.put("partialReindexEnabled", false);
        result.put("message", passed && approvalLinked && releaseLinked
                ? "Post-retry verification passed for the same approval and release id; partial reindex marker or stale-index warning is required before final reporting."
                : "Post-retry verification is incomplete or not linked to the same approval/release id.");
        return result;
    }

    private static Object verificationStatus(Step step) {
        LocalAgentToolResponse enriched = LocalAgentMutationResultClassifier.enrich(step.response(), step.requestInput());
        Map<String, Object> candidate = mapValue(enriched.output().get("mutationResultIntakeCandidate"));
        return candidate.get("verificationStatus");
    }

    private static boolean hasText(Object value) {
        return value instanceof String text && !text.isBlank() || value instanceof UUID;
    }

    public record Step(LocalAgentToolResponse response, Map<String, Object> requestInput) {
        public Step {
            if (response == null) {
                throw new IllegalArgumentException("response is required.");
            }
            requestInput = requestInput == null ? Map.of() : Map.copyOf(requestInput);
        }
    }
}
