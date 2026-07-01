package com.learnbot.service.localagent;

import com.learnbot.dto.LocalAgentToolName;
import com.learnbot.dto.LocalAgentToolResponse;
import com.learnbot.dto.LocalAgentToolStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LocalAgentMutationResultClassifier {
    private LocalAgentMutationResultClassifier() {
    }

    public static LocalAgentToolResponse enrich(LocalAgentToolResponse response, Map<String, Object> requestInput) {
        Map<String, Object> candidate = classify(response, requestInput);
        if (candidate.isEmpty()) {
            return response;
        }
        Map<String, Object> output = new LinkedHashMap<>(response.output());
        output.put("mutationResultIntakeCandidate", candidate);
        output.put("acceptedMutationObservation", acceptedObservation(candidate));
        return new LocalAgentToolResponse(
                response.sessionId(),
                response.requestId(),
                response.userId(),
                response.agentId(),
                response.workspaceId(),
                response.executionTarget(),
                response.toolName(),
                response.status(),
                output,
                response.failureCode(),
                response.error(),
                response.startedAt(),
                response.finishedAt(),
                response.warnings()
        );
    }

    static Map<String, Object> classify(LocalAgentToolResponse response, Map<String, Object> requestInput) {
        Map<String, Object> input = requestInput == null ? Map.of() : requestInput;
        if (Boolean.TRUE.equals(input.get("freshObservationOnly"))
                || Boolean.TRUE.equals(response.output().get("freshObservationOnly"))) {
            return Map.of();
        }
        boolean hasMutationContext = hasValue(input.get("releaseAttemptId"))
                || hasValue(response.output().get("releaseAttemptId"))
                || Boolean.TRUE.equals(input.get("mutationAllowed"))
                || Boolean.TRUE.equals(response.output().get("mutationApplied"));
        if (!hasMutationContext || !isMutationSequenceTool(response.toolName())) {
            return Map.of();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.mutation-result-intake-candidate.v1");
        result.put("status", response.status() == LocalAgentToolStatus.SUCCEEDED ? "OBSERVED" : "OBSERVED_TERMINAL_FAILURE");
        result.put("toolName", response.toolName().wireName());
        result.put("requestId", response.requestId());
        result.put("sourceRequestId", firstValue(input.get("sourceRequestId"), response.output().get("sourceRequestId")));
        result.put("releaseAttemptId", firstValue(input.get("releaseAttemptId"), response.output().get("releaseAttemptId")));
        result.put("dryRun", firstValue(response.output().get("dryRun"), input.get("dryRunOnly")));
        result.put("mutationAllowed", firstValue(input.get("mutationAllowed"), response.output().get("mutationAllowed")));
        result.put("mutationApplied", response.output().get("mutationApplied"));
        result.put("snapshotManifestId", firstValue(
                response.output().get("snapshotManifestId"),
                response.output().get("manifestId"),
                input.get("snapshotManifestId"),
                input.get("manifestId")
        ));
        result.put("rollbackAvailable", firstValue(response.output().get("rollbackAvailable"), response.output().get("rollbackRestorable")));
        result.put("verificationStatus", verificationStatus(response));
        result.put("acceptanceStatus", acceptanceStatus(result, response));
        result.put("resultIntakeEnabled", false);
        result.put("resultPersistenceEnabled", false);
        result.put("aggregationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationAllowedForFollowup", false);
        result.put("message", "Local Agent mutation-sequence result was classified for audit-only intake; persistence, aggregation, publication, acknowledgement save, RAG freshness update, and follow-up mutation remain disabled.");
        return result;
    }

    private static Map<String, Object> acceptedObservation(Map<String, Object> candidate) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.accepted-mutation-observation.v1");
        result.put("status", candidate.get("acceptanceStatus"));
        result.put("accepted", "ACCEPTED".equals(candidate.get("acceptanceStatus")));
        result.put("toolName", candidate.get("toolName"));
        result.put("requestId", candidate.get("requestId"));
        result.put("sourceRequestId", candidate.get("sourceRequestId"));
        result.put("releaseAttemptId", candidate.get("releaseAttemptId"));
        result.put("verificationStatus", candidate.get("verificationStatus"));
        result.put("mutationApplied", candidate.get("mutationApplied"));
        result.put("snapshotManifestId", candidate.get("snapshotManifestId"));
        result.put("rollbackAvailable", candidate.get("rollbackAvailable"));
        result.put("acceptedObservationPersistenceEnabled", false);
        result.put("resultAggregationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationAllowedForFollowup", false);
        result.put("message", "Accepted mutation observation is preserved inside the completed Local Agent response only; dedicated intake persistence, aggregation, publication, acknowledgement save, RAG freshness update, and follow-up mutation remain disabled.");
        return result;
    }

    private static String acceptanceStatus(Map<String, Object> candidate, LocalAgentToolResponse response) {
        if (!hasValue(candidate.get("sourceRequestId")) || !hasValue(candidate.get("releaseAttemptId"))) {
            return "REJECTED_MISSING_LINKAGE";
        }
        if (Boolean.TRUE.equals(candidate.get("dryRun"))) {
            return "REJECTED_DRY_RUN";
        }
        if (response.status() != LocalAgentToolStatus.SUCCEEDED) {
            return "ACCEPTED_TERMINAL_FAILURE";
        }
        if (response.toolName() == LocalAgentToolName.PATCH_APPLY
                && !Boolean.TRUE.equals(candidate.get("mutationApplied"))) {
            return "REJECTED_PATCH_NOT_APPLIED";
        }
        return "ACCEPTED";
    }

    private static boolean isMutationSequenceTool(LocalAgentToolName toolName) {
        return List.of(
                LocalAgentToolName.PATCH_APPLY,
                LocalAgentToolName.COMMAND_RUN_ALLOWED,
                LocalAgentToolName.GIT_STATUS,
                LocalAgentToolName.ROLLBACK_RESTORE
        ).contains(toolName);
    }

    private static Object firstValue(Object... values) {
        for (Object value : values) {
            if (hasValue(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean hasValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return !text.isBlank();
        }
        return true;
    }

    private static String verificationStatus(LocalAgentToolResponse response) {
        if (response.status() != LocalAgentToolStatus.SUCCEEDED) {
            return "FAILED";
        }
        if (response.toolName() == LocalAgentToolName.COMMAND_RUN_ALLOWED) {
            Object exitCode = response.output().get("exitCode");
            if (exitCode instanceof Number number && number.intValue() == 0) {
                return "PASSED";
            }
            return "FAILED";
        }
        if (response.toolName() == LocalAgentToolName.PATCH_APPLY
                && Boolean.TRUE.equals(response.output().get("mutationApplied"))) {
            return "APPLIED";
        }
        if (response.toolName() == LocalAgentToolName.ROLLBACK_RESTORE
                && Boolean.TRUE.equals(response.output().get("restored"))) {
            return "RESTORED";
        }
        if (response.toolName() == LocalAgentToolName.GIT_STATUS) {
            return "OBSERVED";
        }
        return "OBSERVED";
    }
}
