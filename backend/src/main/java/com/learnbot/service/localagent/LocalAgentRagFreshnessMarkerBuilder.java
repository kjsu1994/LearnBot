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
        boolean acceptedMutationObserved = Boolean.TRUE.equals(finalMutationReportSummary.get("acceptedMutationObserved"));
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
        result.put("acceptedMutationObserved", acceptedMutationObserved);
        result.put("staleIndexRiskVisible", staleWarningRequired);
        result.put("targetFilesKnown", targetFilesKnown);
        result.put("targetFiles", targetFiles);
        result.put("staleIndexPolicy", sourceInput.get("staleIndexPolicy"));
        result.put("freshnessAction", staleWarningRequired ? "REQUIRE_PARTIAL_REINDEX_OR_STALE_WARNING" : "NONE");
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

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        return List.of();
    }
}
