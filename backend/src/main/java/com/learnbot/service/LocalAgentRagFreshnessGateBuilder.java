package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class LocalAgentRagFreshnessGateBuilder {

    Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationRollbackFallbackGate,
            Map<String, Object> acceptedMutationObservationSummary
    ) {
        boolean rollbackFallbackReady = "REFUSED_ROLLBACK_FALLBACK_DISABLED".equals(mutationRollbackFallbackGate.get("status"))
                && Boolean.TRUE.equals(mutationRollbackFallbackGate.get("prerequisitesPassed"));
        int expectedResultCount = numericValue(mutationRollbackFallbackGate.get("expectedResultCount"));
        int completedResultCount = numericValue(mutationRollbackFallbackGate.get("completedResultCount"));
        int acceptedResultCount = numericValue(mutationRollbackFallbackGate.get("acceptedResultCount"));
        int rejectedResultCount = numericValue(mutationRollbackFallbackGate.get("rejectedResultCount"));
        int intakePersistedResultCount = numericValue(mutationRollbackFallbackGate.get("intakePersistedResultCount"));
        int observationCount = numericValue(acceptedMutationObservationSummary.get("observationCount"));
        int acceptedObservationCount = numericValue(acceptedMutationObservationSummary.get("acceptedCount"));
        int rejectedObservationCount = numericValue(acceptedMutationObservationSummary.get("rejectedCount"));
        int terminalFailureAcceptedObservationCount = numericValue(acceptedMutationObservationSummary.get("terminalFailureAcceptedCount"));
        List<Map<String, Object>> policyChecks = List.of(
                policyCheck(
                        "mutationRollbackFallbackGate",
                        rollbackFallbackReady,
                        String.valueOf(mutationRollbackFallbackGate.getOrDefault("status", "UNKNOWN")),
                        "A disabled rollback fallback gate must refuse rollback execution before RAG freshness can be considered."
                ),
                policyCheck(
                        "ragFreshnessPolicy",
                        false,
                        "DISABLED",
                        "RAG freshness updates are disabled."
                ),
                policyCheck(
                        "ragFreshnessUpdate",
                        false,
                        "DISABLED",
                        "No code or document index freshness update may run while RAG freshness is disabled."
                ),
                policyCheck(
                        "resultAggregation",
                        false,
                        "DISABLED",
                        "Mutation result aggregation remains disabled until RAG freshness state is modeled."
                ),
                policyCheck(
                        "publication",
                        false,
                        "DISABLED",
                        "Final answer publication remains disabled until RAG freshness state is modeled."
                ),
                policyCheck(
                        "finalAnswerGeneration",
                        false,
                        "DISABLED",
                        "Final-answer generation remains disabled until RAG freshness state is modeled."
                )
        );
        List<String> blockingKeys = new ArrayList<>(policyChecks.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        for (String key : List.of(
                "ragFreshnessUpdateEnabled",
                "mutationResultAggregationEnabled",
                "publicationEnabled",
                "finalAnswerGenerationEnabled",
                "finalAnswerCompletionEnabled",
                "finalAnswerDeliveryEnabled",
                "finalResponseHandoffEnabled",
                "deliveryReceiptEnabled",
                "acknowledgementSaveEnabled",
                "mutationAllowed"
        )) {
            if (!blockingKeys.contains(key)) {
                blockingKeys.add(key);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.mutation-rag-freshness-gate.v1");
        result.put("status", rollbackFallbackReady ? "REFUSED_RAG_FRESHNESS_DISABLED" : "BLOCKED_RAG_FRESHNESS_DISABLED");
        result.put("rollbackFallbackReady", rollbackFallbackReady);
        result.put("prerequisitesPassed", rollbackFallbackReady);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourceRollbackFallbackGateSchema", mutationRollbackFallbackGate.get("schema"));
        result.put("sourceRollbackFallbackGateStatus", mutationRollbackFallbackGate.get("status"));
        result.put("sourceRollbackFallbackGateSessionId", mutationRollbackFallbackGate.get("sessionId"));
        result.put("sourceRollbackFallbackGateUserId", mutationRollbackFallbackGate.get("userId"));
        result.put("sourceRollbackFallbackGateAgentId", mutationRollbackFallbackGate.get("agentId"));
        result.put("sourceRollbackFallbackGateWorkspaceId", mutationRollbackFallbackGate.get("workspaceId"));
        result.put("sourceRollbackFallbackGateAcceptedObservationAuditStatus", mutationRollbackFallbackGate.get("sourceResultIntakePersistenceGateAcceptedObservationAuditStatus"));
        result.put("sourceRollbackFallbackGateLatestAcceptedObservationStatus", mutationRollbackFallbackGate.get("sourceResultIntakePersistenceGateLatestAcceptedObservationStatus"));
        result.put("sourceRollbackFallbackGateLatestAcceptedObservationAccepted", mutationRollbackFallbackGate.get("sourceResultIntakePersistenceGateLatestAcceptedObservationAccepted"));
        result.put("sourceRollbackFallbackGateLatestAcceptedObservationRejected", mutationRollbackFallbackGate.get("sourceResultIntakePersistenceGateLatestAcceptedObservationRejected"));
        result.put("sourceRollbackFallbackGateLatestAcceptedObservationTerminalFailureAccepted", mutationRollbackFallbackGate.get("sourceResultIntakePersistenceGateLatestAcceptedObservationTerminalFailureAccepted"));
        result.put("sourceRollbackFallbackGateLatestAcceptedObservationToolName", mutationRollbackFallbackGate.get("sourceResultIntakePersistenceGateLatestAcceptedObservationToolName"));
        result.put("sourceRollbackFallbackGateLatestAcceptedObservationVerificationStatus", mutationRollbackFallbackGate.get("sourceResultIntakePersistenceGateLatestAcceptedObservationVerificationStatus"));
        result.put("sourceRollbackFallbackGateAcceptedObservationSummaryStatus", mutationRollbackFallbackGate.get("sourceResultIntakePersistenceGateAcceptedObservationSummaryStatus"));
        result.put("sourceRollbackFallbackGateAcceptedObservationSummaryObservationCount", mutationRollbackFallbackGate.get("sourceResultIntakePersistenceGateAcceptedObservationSummaryObservationCount"));
        result.put("sourceRollbackFallbackGateAcceptedObservationSummaryAcceptedCount", mutationRollbackFallbackGate.get("sourceResultIntakePersistenceGateAcceptedObservationSummaryAcceptedCount"));
        result.put("sourceRollbackFallbackGateAcceptedObservationSummaryRejectedCount", mutationRollbackFallbackGate.get("sourceResultIntakePersistenceGateAcceptedObservationSummaryRejectedCount"));
        result.put("sourceRollbackFallbackGateAcceptedObservationSummaryMissingMutationResultRiskVisible", mutationRollbackFallbackGate.get("sourceResultIntakePersistenceGateAcceptedObservationSummaryMissingMutationResultRiskVisible"));
        result.put("sourceRollbackFallbackGateAcceptedObservationSummaryStaleIndexRiskVisible", mutationRollbackFallbackGate.get("sourceResultIntakePersistenceGateAcceptedObservationSummaryStaleIndexRiskVisible"));
        result.put("sourceRollbackFallbackGatePublicationGateSchema", mutationRollbackFallbackGate.get("sourceResultIntakePersistenceGatePublicationGateSchema"));
        result.put("sourceRollbackFallbackGatePublicationGateStatus", mutationRollbackFallbackGate.get("sourceResultIntakePersistenceGatePublicationGateStatus"));
        result.put("sourceRollbackFallbackGatePublicationGateSessionId", mutationRollbackFallbackGate.get("sourceResultIntakePersistenceGatePublicationGateSessionId"));
        result.put("sourceRollbackFallbackGatePublicationGateUserId", mutationRollbackFallbackGate.get("sourceResultIntakePersistenceGatePublicationGateUserId"));
        result.put("sourceRollbackFallbackGatePublicationGateAgentId", mutationRollbackFallbackGate.get("sourceResultIntakePersistenceGatePublicationGateAgentId"));
        result.put("sourceRollbackFallbackGatePublicationGateWorkspaceId", mutationRollbackFallbackGate.get("sourceResultIntakePersistenceGatePublicationGateWorkspaceId"));
        result.put("sourceRollbackFallbackGateRollbackAcceptedObservationSummaryStatus", mutationRollbackFallbackGate.get("sourceResultIntakePersistenceGateRollbackAcceptedObservationSummaryStatus"));
        result.put("sourceRollbackFallbackGateRollbackAcceptedObservationSummaryObservationCount", mutationRollbackFallbackGate.get("sourceResultIntakePersistenceGateRollbackAcceptedObservationSummaryObservationCount"));
        result.put("sourceRollbackFallbackGateRollbackAcceptedObservationSummaryAcceptedCount", mutationRollbackFallbackGate.get("sourceResultIntakePersistenceGateRollbackAcceptedObservationSummaryAcceptedCount"));
        result.put("sourceRollbackFallbackGateRollbackAcceptedObservationSummaryRejectedCount", mutationRollbackFallbackGate.get("sourceResultIntakePersistenceGateRollbackAcceptedObservationSummaryRejectedCount"));
        result.put("sourceRollbackFallbackGateRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible", mutationRollbackFallbackGate.get("sourceResultIntakePersistenceGateRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible"));
        result.put("sourceRollbackFallbackGateRollbackAcceptedObservationSummaryStaleIndexRiskVisible", mutationRollbackFallbackGate.get("sourceResultIntakePersistenceGateRollbackAcceptedObservationSummaryStaleIndexRiskVisible"));
        result.put("ragFreshnessPolicy", "DISABLED_AUDIT_ONLY");
        result.put("ragFreshnessUpdateInvocationEnabled", false);
        result.put("acceptedMutationObservationSummarySchema", acceptedMutationObservationSummary.get("schema"));
        result.put("acceptedMutationObservationSummaryStatus", acceptedMutationObservationSummary.get("status"));
        result.put("acceptedMutationObservationCount", observationCount);
        result.put("acceptedMutationObservationAcceptedCount", acceptedObservationCount);
        result.put("acceptedMutationObservationRejectedCount", rejectedObservationCount);
        result.put("acceptedMutationObservationTerminalFailureAcceptedCount", terminalFailureAcceptedObservationCount);
        result.put("acceptedMutationObservationToolCounts", acceptedMutationObservationSummary.get("toolObservationCounts"));
        result.put("acceptedMutationObservationStatusCounts", acceptedMutationObservationSummary.get("statusObservationCounts"));
        result.put("missingMutationResultRiskVisible", observationCount == 0);
        result.put("staleIndexRiskVisible", acceptedObservationCount > 0);
        result.put("expectedResultCount", expectedResultCount);
        result.put("completedResultCount", completedResultCount);
        result.put("acceptedResultCount", acceptedResultCount);
        result.put("rejectedResultCount", rejectedResultCount);
        result.put("intakePersistedResultCount", intakePersistedResultCount);
        result.put("policyChecks", policyChecks);
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimEnabled", false);
        result.put("executionEnabled", false);
        result.put("writeHelperEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        result.put("postExecutionObservationEnabled", false);
        result.put("completedResultPersistenceEnabled", false);
        result.put("observationAcceptanceEnabled", false);
        result.put("intakePersistenceEnabled", false);
        result.put("acceptedObservationPersistenceEnabled", false);
        result.put("rollbackFallbackExecutionEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("finalAnswerCompletionEnabled", false);
        result.put("finalAnswerDeliveryEnabled", false);
        result.put("finalResponseHandoffEnabled", false);
        result.put("deliveryReceiptEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("blockingKeys", blockingKeys);
        result.put("message", rollbackFallbackReady
                ? "Local Agent mutation RAG freshness is explicitly refused: no freshness update, aggregation, publication, or final answer is enabled."
                : "Local Agent mutation RAG freshness is blocked because the disabled rollback fallback gate is incomplete.");
        return result;
    }

    private Map<String, Object> policyCheck(
            String key,
            boolean passed,
            String status,
            String message
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("status", status);
        result.put("passed", passed);
        result.put("blocking", !passed);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimEnabled", false);
        result.put("executionEnabled", false);
        result.put("writeHelperEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("finalAnswerCompletionEnabled", false);
        result.put("finalAnswerDeliveryEnabled", false);
        result.put("finalResponseHandoffEnabled", false);
        result.put("deliveryReceiptEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("message", message);
        return result;
    }

    private int numericValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
