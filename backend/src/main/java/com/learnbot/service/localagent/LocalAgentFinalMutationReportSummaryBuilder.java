package com.learnbot.service.localagent;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.service.LocalAgentPatchReleaseAttempt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LocalAgentFinalMutationReportSummaryBuilder {
    private LocalAgentFinalMutationReportSummaryBuilder() {
    }

    public static Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> acceptedMutationObservationSummary,
            Map<String, Object> acceptedMutationObservationReadiness
    ) {
        Map<String, Object> latest = mapValue(acceptedMutationObservationReadiness.get("latestObservation"));
        int observationCount = numericValue(acceptedMutationObservationSummary.get("observationCount"));
        int acceptedCount = numericValue(acceptedMutationObservationSummary.get("acceptedCount"));
        boolean summaryAvailable = observationCount > 0;
        boolean acceptedMutationObserved = acceptedCount > 0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.final-mutation-report-summary.v1");
        result.put("status", summaryAvailable ? "READY_SUMMARY_AUDIT_ONLY" : "MISSING_OBSERVATIONS_AUDIT_ONLY");
        result.put("summaryAvailable", summaryAvailable);
        result.put("acceptedMutationObserved", acceptedMutationObserved);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        copyAcceptedObservationSummary(result, acceptedMutationObservationSummary);
        result.put("latestAcceptedMutationObservation", latest);
        result.put("sections", List.of(
                changedFilesSection(latest, acceptedMutationObserved),
                verificationSection(latest, summaryAvailable),
                rollbackSection(latest, summaryAvailable),
                ragFreshnessSection(acceptedMutationObserved),
                residualRiskSection(observationCount, acceptedCount)
        ));
        result.put("finalMutationReportSummaryMode", "READ_ONLY_AUDIT");
        result.put("finalMutationReportSummaryAvailable", summaryAvailable);
        result.put("finalReportGenerationEnabled", false);
        result.put("resultAggregationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationAllowed", false);
        result.put("blockingKeys", summaryAvailable
                ? List.of("finalReportGenerationEnabled", "publicationEnabled", "finalAnswerGenerationEnabled")
                : List.of("acceptedMutationObservationSummary", "finalReportGenerationEnabled"));
        result.put("message", summaryAvailable
                ? "Accepted mutation observations are summarized for the final report, but report generation, publication, final answer, acknowledgement save, and RAG freshness update remain disabled."
                : "No accepted mutation observations are available for a final report summary.");
        return result;
    }

    private static Map<String, Object> changedFilesSection(Map<String, Object> latest, boolean acceptedMutationObserved) {
        Map<String, Object> result = section("changedFiles", acceptedMutationObserved ? "OBSERVED" : "MISSING");
        result.put("toolName", latest.get("toolName"));
        result.put("mutationApplied", latest.get("mutationApplied"));
        result.put("snapshotManifestId", latest.get("snapshotManifestId"));
        result.put("message", acceptedMutationObserved
                ? "A Local Agent mutation observation is available for changed-file reporting."
                : "No accepted Local Agent mutation observation is available for changed-file reporting.");
        return result;
    }

    private static Map<String, Object> verificationSection(Map<String, Object> latest, boolean summaryAvailable) {
        Map<String, Object> result = section("verification", summaryAvailable ? "OBSERVED" : "MISSING");
        result.put("verificationStatus", latest.get("verificationStatus"));
        result.put("observationStatus", latest.get("status"));
        result.put("message", summaryAvailable
                ? "Verification status is carried from the latest Local Agent mutation observation."
                : "Verification status is unavailable because mutation observations are missing.");
        return result;
    }

    private static Map<String, Object> rollbackSection(Map<String, Object> latest, boolean summaryAvailable) {
        Map<String, Object> result = section("rollback", summaryAvailable ? "OBSERVED" : "MISSING");
        result.put("rollbackAvailable", latest.get("rollbackAvailable"));
        result.put("snapshotManifestId", latest.get("snapshotManifestId"));
        result.put("message", summaryAvailable
                ? "Rollback availability is carried from the latest Local Agent mutation observation."
                : "Rollback availability is unavailable because mutation observations are missing.");
        return result;
    }

    private static Map<String, Object> ragFreshnessSection(boolean acceptedMutationObserved) {
        Map<String, Object> result = section("ragFreshness", acceptedMutationObserved ? "STALE_INDEX_WARNING_REQUIRED" : "MISSING_MUTATION");
        result.put("staleIndexRiskVisible", acceptedMutationObserved);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("message", acceptedMutationObserved
                ? "A local mutation was observed, so final reporting must carry a stale-index warning until RAG freshness is updated."
                : "No accepted mutation was observed, so RAG freshness cannot be assessed.");
        return result;
    }

    private static Map<String, Object> residualRiskSection(int observationCount, int acceptedCount) {
        Map<String, Object> result = section("residualRisk", observationCount > 0 ? "OBSERVED" : "MISSING");
        result.put("missingMutationResultRiskVisible", observationCount == 0);
        result.put("staleIndexRiskVisible", acceptedCount > 0);
        result.put("message", "Residual risk remains visible until final report publication and acknowledgement are enabled.");
        return result;
    }

    private static Map<String, Object> section(String key, String status) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("status", status);
        result.put("resultAggregationEnabled", false);
        result.put("finalReportGenerationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("mutationAllowed", false);
        return result;
    }

    private static void copyAcceptedObservationSummary(
            Map<String, Object> target,
            Map<String, Object> acceptedMutationObservationSummary
    ) {
        target.put("acceptedMutationObservationSummarySchema", acceptedMutationObservationSummary.get("schema"));
        target.put("acceptedMutationObservationSummaryStatus", acceptedMutationObservationSummary.get("status"));
        target.put("acceptedMutationObservationCount", acceptedMutationObservationSummary.get("observationCount"));
        target.put("acceptedMutationObservationAcceptedCount", acceptedMutationObservationSummary.get("acceptedCount"));
        target.put("acceptedMutationObservationRejectedCount", acceptedMutationObservationSummary.get("rejectedCount"));
        target.put("acceptedMutationObservationTerminalFailureAcceptedCount", acceptedMutationObservationSummary.get("terminalFailureAcceptedCount"));
        target.put("acceptedMutationObservationToolCounts", acceptedMutationObservationSummary.get("toolObservationCounts"));
        target.put("acceptedMutationObservationStatusCounts", acceptedMutationObservationSummary.get("statusObservationCounts"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private static int numericValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
