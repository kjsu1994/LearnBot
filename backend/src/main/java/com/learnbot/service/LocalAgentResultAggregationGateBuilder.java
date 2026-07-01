package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class LocalAgentResultAggregationGateBuilder {

    Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationRagFreshnessGate,
            Map<String, Object> acceptedMutationObservationReadiness
    ) {
        boolean ragFreshnessReady = "REFUSED_RAG_FRESHNESS_DISABLED".equals(mutationRagFreshnessGate.get("status"))
                && Boolean.TRUE.equals(mutationRagFreshnessGate.get("prerequisitesPassed"));
        int expectedResultCount = numericValue(mutationRagFreshnessGate.get("expectedResultCount"));
        int completedResultCount = numericValue(mutationRagFreshnessGate.get("completedResultCount"));
        int acceptedResultCount = numericValue(mutationRagFreshnessGate.get("acceptedResultCount"));
        int rejectedResultCount = numericValue(mutationRagFreshnessGate.get("rejectedResultCount"));
        int intakePersistedResultCount = numericValue(mutationRagFreshnessGate.get("intakePersistedResultCount"));
        Map<String, Object> latestAcceptedObservation = mapValue(acceptedMutationObservationReadiness.get("latestObservation"));
        boolean acceptedObservationObserved = Boolean.TRUE.equals(acceptedMutationObservationReadiness.get("observed"));
        String acceptedObservationStatus = acceptedObservationObserved
                ? String.valueOf(latestAcceptedObservation.getOrDefault("status", "UNKNOWN"))
                : "MISSING";
        boolean terminalFailureAccepted = "ACCEPTED_TERMINAL_FAILURE".equals(acceptedObservationStatus);
        boolean rejectedObservation = acceptedObservationStatus.startsWith("REJECTED_");
        List<Map<String, Object>> policyChecks = List.of(
                policyCheck(
                        "mutationRagFreshnessGate",
                        ragFreshnessReady,
                        String.valueOf(mutationRagFreshnessGate.getOrDefault("status", "UNKNOWN")),
                        "A disabled RAG freshness gate must refuse freshness updates before result aggregation can be considered."
                ),
                policyCheck(
                        "resultAggregationPolicy",
                        false,
                        "DISABLED",
                        "Mutation result aggregation is disabled."
                ),
                policyCheck(
                        "resultAggregation",
                        false,
                        "DISABLED",
                        "No mutation result aggregation may run while aggregation is disabled."
                ),
                policyCheck(
                        "publication",
                        false,
                        "DISABLED",
                        "Final answer publication remains disabled until aggregation state is modeled."
                ),
                policyCheck(
                        "finalAnswerGeneration",
                        false,
                        "DISABLED",
                        "Final-answer generation remains disabled until aggregation state is modeled."
                )
        );
        List<Map<String, Object>> acceptedObservationAudit = List.of(
                auditCheck(
                        "acceptedMutationObservationReadiness",
                        acceptedObservationObserved ? "OBSERVED" : "MISSING",
                        acceptedObservationObserved,
                        "Latest accepted mutation observation visibility is available to the aggregation summary for audit only."
                ),
                auditCheck(
                        "acceptedMutationObservationStatus",
                        acceptedObservationStatus,
                        "ACCEPTED".equals(acceptedObservationStatus)
                                || terminalFailureAccepted
                                || rejectedObservation
                                || "MISSING".equals(acceptedObservationStatus),
                        "Aggregation can distinguish missing, rejected, terminal-failure, and accepted evidence without running aggregation."
                )
        );
        List<String> blockingKeys = new ArrayList<>(policyChecks.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        for (String key : List.of(
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
        result.put("schema", "learnbot.local-agent.mutation-result-aggregation-gate.v1");
        result.put("status", ragFreshnessReady ? "REFUSED_RESULT_AGGREGATION_DISABLED" : "BLOCKED_RESULT_AGGREGATION_DISABLED");
        result.put("ragFreshnessReady", ragFreshnessReady);
        result.put("prerequisitesPassed", ragFreshnessReady);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourceRagFreshnessGateSchema", mutationRagFreshnessGate.get("schema"));
        result.put("sourceRagFreshnessGateStatus", mutationRagFreshnessGate.get("status"));
        result.put("sourceRagFreshnessGateSessionId", mutationRagFreshnessGate.get("sessionId"));
        result.put("sourceRagFreshnessGateUserId", mutationRagFreshnessGate.get("userId"));
        result.put("sourceRagFreshnessGateAgentId", mutationRagFreshnessGate.get("agentId"));
        result.put("sourceRagFreshnessGateWorkspaceId", mutationRagFreshnessGate.get("workspaceId"));
        result.put("sourceRagFreshnessGateAcceptedObservationSummaryStatus", mutationRagFreshnessGate.get("acceptedMutationObservationSummaryStatus"));
        result.put("sourceRagFreshnessGateAcceptedObservationCount", mutationRagFreshnessGate.get("acceptedMutationObservationCount"));
        result.put("sourceRagFreshnessGateAcceptedObservationAcceptedCount", mutationRagFreshnessGate.get("acceptedMutationObservationAcceptedCount"));
        result.put("sourceRagFreshnessGateAcceptedObservationRejectedCount", mutationRagFreshnessGate.get("acceptedMutationObservationRejectedCount"));
        result.put("sourceRagFreshnessGateMissingMutationResultRiskVisible", mutationRagFreshnessGate.get("missingMutationResultRiskVisible"));
        result.put("sourceRagFreshnessGateStaleIndexRiskVisible", mutationRagFreshnessGate.get("staleIndexRiskVisible"));
        result.put("sourceRagFreshnessGatePublicationGateSchema", mutationRagFreshnessGate.get("sourceRollbackFallbackGatePublicationGateSchema"));
        result.put("sourceRagFreshnessGatePublicationGateStatus", mutationRagFreshnessGate.get("sourceRollbackFallbackGatePublicationGateStatus"));
        result.put("sourceRagFreshnessGatePublicationGateSessionId", mutationRagFreshnessGate.get("sourceRollbackFallbackGatePublicationGateSessionId"));
        result.put("sourceRagFreshnessGatePublicationGateUserId", mutationRagFreshnessGate.get("sourceRollbackFallbackGatePublicationGateUserId"));
        result.put("sourceRagFreshnessGatePublicationGateAgentId", mutationRagFreshnessGate.get("sourceRollbackFallbackGatePublicationGateAgentId"));
        result.put("sourceRagFreshnessGatePublicationGateWorkspaceId", mutationRagFreshnessGate.get("sourceRollbackFallbackGatePublicationGateWorkspaceId"));
        result.put("sourceRagFreshnessGateLatestAcceptedObservationStatus", mutationRagFreshnessGate.get("sourceRollbackFallbackGateLatestAcceptedObservationStatus"));
        result.put("sourceRagFreshnessGateLatestAcceptedObservationToolName", mutationRagFreshnessGate.get("sourceRollbackFallbackGateLatestAcceptedObservationToolName"));
        result.put("sourceRagFreshnessGateLatestAcceptedObservationVerificationStatus", mutationRagFreshnessGate.get("sourceRollbackFallbackGateLatestAcceptedObservationVerificationStatus"));
        result.put("sourceRagFreshnessGateRollbackAcceptedObservationSummaryStatus", mutationRagFreshnessGate.get("sourceRollbackFallbackGateRollbackAcceptedObservationSummaryStatus"));
        result.put("sourceRagFreshnessGateRollbackAcceptedObservationSummaryObservationCount", mutationRagFreshnessGate.get("sourceRollbackFallbackGateRollbackAcceptedObservationSummaryObservationCount"));
        result.put("sourceRagFreshnessGateRollbackAcceptedObservationSummaryAcceptedCount", mutationRagFreshnessGate.get("sourceRollbackFallbackGateRollbackAcceptedObservationSummaryAcceptedCount"));
        result.put("sourceRagFreshnessGateRollbackAcceptedObservationSummaryRejectedCount", mutationRagFreshnessGate.get("sourceRollbackFallbackGateRollbackAcceptedObservationSummaryRejectedCount"));
        result.put("sourceRagFreshnessGateRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible", mutationRagFreshnessGate.get("sourceRollbackFallbackGateRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible"));
        result.put("sourceRagFreshnessGateRollbackAcceptedObservationSummaryStaleIndexRiskVisible", mutationRagFreshnessGate.get("sourceRollbackFallbackGateRollbackAcceptedObservationSummaryStaleIndexRiskVisible"));
        result.put("sourceAcceptedMutationObservationReadinessSchema", acceptedMutationObservationReadiness.get("schema"));
        result.put("sourceAcceptedMutationObservationReadinessStatus", acceptedMutationObservationReadiness.get("status"));
        result.put("sourceAcceptedMutationObservationObserved", acceptedObservationObserved);
        result.put("sourceAcceptedMutationObservationReadinessSessionId", acceptedMutationObservationReadiness.get("sessionId"));
        result.put("sourceAcceptedMutationObservationReadinessUserId", acceptedMutationObservationReadiness.get("userId"));
        result.put("sourceAcceptedMutationObservationReadinessAgentId", acceptedMutationObservationReadiness.get("agentId"));
        result.put("sourceAcceptedMutationObservationReadinessWorkspaceId", acceptedMutationObservationReadiness.get("workspaceId"));
        result.put("resultAggregationPolicy", "DISABLED_AUDIT_ONLY");
        result.put("resultAggregationInvocationEnabled", false);
        result.put("acceptedMutationObservationAuditStatus", acceptedObservationObserved ? "OBSERVED" : "MISSING");
        result.put("latestAcceptedMutationObservationStatus", acceptedObservationStatus);
        result.put("latestAcceptedMutationObservationAccepted", Boolean.TRUE.equals(latestAcceptedObservation.get("accepted")));
        result.put("latestAcceptedMutationObservationRejected", rejectedObservation);
        result.put("latestAcceptedMutationObservationTerminalFailureAccepted", terminalFailureAccepted);
        result.put("latestAcceptedMutationObservationToolName", latestAcceptedObservation.get("toolName"));
        result.put("latestAcceptedMutationObservationVerificationStatus", latestAcceptedObservation.get("verificationStatus"));
        result.put("latestAcceptedMutationObservation", latestAcceptedObservation);
        result.put("expectedResultCount", expectedResultCount);
        result.put("completedResultCount", completedResultCount);
        result.put("acceptedResultCount", acceptedResultCount);
        result.put("rejectedResultCount", rejectedResultCount);
        result.put("intakePersistedResultCount", intakePersistedResultCount);
        result.put("policyChecks", policyChecks);
        result.put("acceptedObservationAudit", acceptedObservationAudit);
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
        result.put("message", ragFreshnessReady
                ? "Local Agent mutation result aggregation is explicitly refused: no aggregation, publication, or final answer is enabled."
                : "Local Agent mutation result aggregation is blocked because the disabled RAG freshness gate is incomplete.");
        return result;
    }

    private Map<String, Object> auditCheck(
            String key,
            String status,
            boolean observed,
            String message
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("status", status);
        result.put("observed", observed);
        result.put("blocking", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationAllowed", false);
        result.put("message", message);
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

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
