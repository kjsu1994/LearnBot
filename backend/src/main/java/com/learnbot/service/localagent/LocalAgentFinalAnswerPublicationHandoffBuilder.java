package com.learnbot.service.localagent;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.service.LocalAgentPatchReleaseAttempt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LocalAgentFinalAnswerPublicationHandoffBuilder {
    private static final String STALE_INDEX_DISCLOSURE =
            "Local files changed and code RAG may be stale until partial reindex completes.";

    private LocalAgentFinalAnswerPublicationHandoffBuilder() {
    }

    public static Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> finalMutationReportSummary,
            Map<String, Object> ragFreshnessMarker
    ) {
        boolean summaryAvailable = Boolean.TRUE.equals(finalMutationReportSummary.get("summaryAvailable"));
        boolean staleDisclosureRequired = Boolean.TRUE.equals(ragFreshnessMarker.get("finalAnswerMustDiscloseStaleIndex"));
        boolean staleDisclosureModeled = staleDisclosureRequired
                && Boolean.TRUE.equals(ragFreshnessMarker.get("markerAvailable"))
                && Boolean.TRUE.equals(ragFreshnessMarker.get("staleIndexRiskVisible"));
        boolean handoffReady = summaryAvailable && (!staleDisclosureRequired || staleDisclosureModeled);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.final-answer-publication-handoff.v1");
        result.put("status", handoffReady
                ? "READY_HANDOFF_AUDIT_ONLY_PUBLICATION_DISABLED"
                : "BLOCKED_STALE_INDEX_DISCLOSURE_MISSING");
        result.put("handoffAvailable", handoffReady);
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
        result.put("sourceRagFreshnessMarkerSchema", ragFreshnessMarker.get("schema"));
        result.put("sourceRagFreshnessMarkerStatus", ragFreshnessMarker.get("status"));
        result.put("finalMutationReportSummaryAvailable", summaryAvailable);
        result.put("staleIndexDisclosureRequired", staleDisclosureRequired);
        result.put("staleIndexDisclosureModeled", staleDisclosureModeled);
        result.put("staleIndexDisclosureText", staleDisclosureModeled ? STALE_INDEX_DISCLOSURE : null);
        result.put("targetFiles", stringList(ragFreshnessMarker.get("targetFiles")));
        result.put("staleIndexPolicy", ragFreshnessMarker.get("staleIndexPolicy"));
        result.put("freshnessAction", ragFreshnessMarker.get("freshnessAction"));
        result.put("finalAnswerSections", sectionKeys(finalMutationReportSummary.get("sections")));
        result.put("publicationRefusalReason", handoffReady
                ? "Publication handoff has the required stale-index disclosure, but publication remains disabled until final-answer generation and acknowledgement save are enabled."
                : "Publication handoff is blocked because stale-index disclosure is missing or final report summary is unavailable.");
        result.put("finalAnswerPublicationHandoffMode", "READ_ONLY_AUDIT");
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("finalAnswerDeliveryEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("partialReindexEnabled", false);
        result.put("mutationAllowed", false);
        result.put("blockingKeys", handoffReady
                ? List.of("publicationEnabled", "finalAnswerGenerationEnabled", "acknowledgementSaveEnabled")
                : List.of("finalMutationReportSummary", "ragFreshnessMarker", "staleIndexDisclosure"));
        result.put("message", handoffReady
                ? "Final-answer publication handoff can carry the stale-index disclosure, but publication, final answer delivery, acknowledgement save, and RAG freshness update remain disabled."
                : "Final-answer publication handoff refuses publication until final report summary and stale-index disclosure are present.");
        return result;
    }

    private static List<String> sectionKeys(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(section -> section.get("key"))
                    .map(String::valueOf)
                    .filter(key -> !key.isBlank())
                    .toList();
        }
        return List.of();
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
