package com.learnbot.service.localagent;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.service.LocalAgentPatchReleaseAttempt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LocalAgentRagFreshnessMarkerBuilder {
    private LocalAgentRagFreshnessMarkerBuilder() {
    }

    public static Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> sourceInput,
            Map<String, Object> finalMutationReportSummary
    ) {
        return build(attempt, sourceInput, finalMutationReportSummary, Map.of());
    }

    public static Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> sourceInput,
            Map<String, Object> finalMutationReportSummary,
            Map<String, Object> postRetryVerification
    ) {
        boolean acceptedMutationObserved = Boolean.TRUE.equals(finalMutationReportSummary.get("acceptedMutationObserved"));
        boolean postRetryVerificationObserved = Boolean.TRUE.equals(postRetryVerification.get("observed"));
        boolean postRetryVerificationPassed = Boolean.TRUE.equals(postRetryVerification.get("passed"));
        boolean postRetryVerificationApprovalLinked = Boolean.TRUE.equals(postRetryVerification.get("approvalRequestLinked"));
        boolean postRetryVerificationReleaseLinked = Boolean.TRUE.equals(postRetryVerification.get("releaseAttemptLinked"));
        boolean postRetryVerificationLinked = postRetryVerificationApprovalLinked && postRetryVerificationReleaseLinked;
        boolean partialReindexMarkerRequired = Boolean.TRUE.equals(postRetryVerification.get("partialReindexMarkerRequired"));
        List<String> targetFiles = stringList(sourceInput.get("targetFiles"));
        boolean targetFilesKnown = !targetFiles.isEmpty();
        boolean staleWarningRequired = acceptedMutationObserved;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.rag-freshness-marker.v1");
        result.put("status", staleWarningRequired ? "STALE_INDEX_WARNING_REQUIRED" : "NO_MUTATION_OBSERVED");
        result.put("markerAvailable", staleWarningRequired);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourceFinalMutationReportSummarySchema", finalMutationReportSummary.get("schema"));
        result.put("sourceFinalMutationReportSummaryStatus", finalMutationReportSummary.get("status"));
        result.put("sourcePostRetryVerificationSchema", postRetryVerification.get("schema"));
        result.put("postRetryVerificationObserved", postRetryVerificationObserved);
        result.put("postRetryVerificationPassed", postRetryVerificationPassed);
        result.put("postRetryVerificationApprovalLinked", postRetryVerificationApprovalLinked);
        result.put("postRetryVerificationReleaseLinked", postRetryVerificationReleaseLinked);
        result.put("postRetryVerificationLinked", postRetryVerificationLinked);
        result.put("postRetryVerificationApprovalRequestId", postRetryVerification.get("approvalRequestId"));
        result.put("postRetryVerificationPartialReindexMarkerRequired", partialReindexMarkerRequired);
        result.put("acceptedMutationObserved", acceptedMutationObserved);
        result.put("staleIndexRiskVisible", staleWarningRequired);
        result.put("targetFilesKnown", targetFilesKnown);
        result.put("targetFiles", targetFiles);
        result.put("staleIndexPolicy", sourceInput.get("staleIndexPolicy"));
        result.put("freshnessAction", staleWarningRequired ? "REQUIRE_PARTIAL_REINDEX_OR_STALE_WARNING" : "NONE");
        result.put("partialReindexPlan", partialReindexPlan(
                attempt,
                sourceInput,
                targetFiles,
                targetFilesKnown,
                staleWarningRequired,
                partialReindexMarkerRequired,
                postRetryVerification
        ));
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("partialReindexEnabled", false);
        result.put("finalAnswerMustDiscloseStaleIndex", staleWarningRequired);
        result.put("finalReportGenerationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("mutationAllowed", false);
        result.put("blockingKeys", staleWarningRequired
                ? List.of("ragFreshnessUpdateEnabled", "partialReindexEnabled", "finalAnswerGenerationEnabled")
                : List.of("acceptedMutationObserved"));
        result.put("message", staleWarningRequired
                ? "Local Agent changed files; RAG freshness update is disabled, so final reporting must carry a stale-index warning or trigger a future partial reindex."
                : "No accepted mutation was observed, so no RAG freshness marker is available.");
        return result;
    }

    private static Map<String, Object> partialReindexPlan(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> sourceInput,
            List<String> targetFiles,
            boolean targetFilesKnown,
            boolean staleWarningRequired,
            boolean markerRequired,
            Map<String, Object> postRetryVerification
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.partial-reindex-plan.v1");
        result.put("status", markerRequired ? "PARTIAL_REINDEX_MARKER_REQUIRED_DISABLED" : "PARTIAL_REINDEX_NOT_REQUIRED_DISABLED");
        result.put("planAvailable", markerRequired || staleWarningRequired);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("approvalRequestId", postRetryVerification.get("approvalRequestId"));
        result.put("postRetryVerificationPassed", postRetryVerification.get("passed"));
        result.put("postRetryVerificationApprovalLinked", postRetryVerification.get("approvalRequestLinked"));
        result.put("postRetryVerificationReleaseLinked", postRetryVerification.get("releaseAttemptLinked"));
        result.put("targetFilesKnown", targetFilesKnown);
        result.put("targetFiles", targetFiles);
        result.put("targetFileCount", targetFiles.size());
        result.put("freshnessAction", markerRequired ? "PARTIAL_REINDEX_TARGET_FILES_AFTER_APPROVED_RETRY" : "STALE_WARNING_ONLY");
        result.put("repositoryId", sourceInput.get("repositoryId"));
        result.put("loopId", sourceInput.get("loopId"));
        result.put("partialReindexEnqueueBoundary", partialReindexEnqueueBoundary(
                attempt,
                sourceInput,
                targetFiles,
                targetFilesKnown,
                markerRequired
        ));
        result.put("partialReindexEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("publicationEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("mutationAllowed", false);
        result.put("blockingKeys", markerRequired
                ? List.of("partialReindexEnabled", "ragFreshnessUpdateEnabled")
                : List.of("postRetryVerificationPartialReindexMarkerRequired"));
        result.put("message", markerRequired
                ? "Approved retry verification passed and target files require a future partial reindex, but partial reindex execution remains disabled."
                : "Partial reindex execution remains disabled; final reporting must rely on stale-index disclosure when needed.");
        return result;
    }

    private static Map<String, Object> partialReindexEnqueueBoundary(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> sourceInput,
            List<String> targetFiles,
            boolean targetFilesKnown,
            boolean markerRequired
    ) {
        Object repositoryId = sourceInput.get("repositoryId");
        boolean repositoryKnown = hasText(repositoryId);
        boolean ready = markerRequired && repositoryKnown && targetFilesKnown;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.partial-reindex-enqueue-boundary.v1");
        result.put("status", ready ? "READY_ENQUEUE_DISABLED" : "BLOCKED_OR_NOT_REQUIRED_ENQUEUE_DISABLED");
        result.put("ready", ready);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("repositoryId", repositoryId);
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("targetFiles", targetFiles);
        result.put("targetFileCount", targetFiles.size());
        result.put("targetFilesKnown", targetFilesKnown);
        result.put("repositoryKnown", repositoryKnown);
        result.put("markerRequired", markerRequired);
        result.put("enqueueEnabled", false);
        result.put("jobCreationEnabled", false);
        result.put("partialReindexEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationAllowed", false);
        result.put("blockingKeys", ready
                ? List.of("enqueueEnabled", "partialReindexEnabled", "ragFreshnessUpdateEnabled")
                : List.of("repositoryId", "targetFiles", "postRetryVerificationPartialReindexMarkerRequired"));
        result.put("message", ready
                ? "Partial reindex enqueue inputs are available, but job creation and freshness update execution remain disabled."
                : "Partial reindex enqueue is disabled or missing repository/target-file readiness.");
        return result;
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        return List.of();
    }

    private static boolean hasText(Object value) {
        return value instanceof String text && !text.isBlank();
    }
}
