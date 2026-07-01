package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class LocalAgentResultIntakePersistenceGateBuilder {

    Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationObservationAcceptanceGate,
            Map<String, Object> acceptedMutationObservationSummary,
            Map<String, Object> acceptedMutationObservationReadiness
    ) {
        boolean observationAcceptanceReady = "REFUSED_OBSERVATION_ACCEPTANCE_DISABLED".equals(mutationObservationAcceptanceGate.get("status"))
                && Boolean.TRUE.equals(mutationObservationAcceptanceGate.get("prerequisitesPassed"));
        int expectedResultCount = numericValue(mutationObservationAcceptanceGate.get("expectedResultCount"));
        int completedResultCount = numericValue(mutationObservationAcceptanceGate.get("completedResultCount"));
        int acceptedResultCount = numericValue(mutationObservationAcceptanceGate.get("acceptedResultCount"));
        int rejectedResultCount = numericValue(mutationObservationAcceptanceGate.get("rejectedResultCount"));
        int intakePersistedResultCount = numericValue(mutationObservationAcceptanceGate.get("intakePersistedResultCount"));
        Map<String, Object> latestAcceptedObservation = mapValue(acceptedMutationObservationReadiness.get("latestObservation"));
        boolean acceptedObservationObserved = Boolean.TRUE.equals(acceptedMutationObservationReadiness.get("observed"));
        String acceptedObservationStatus = acceptedObservationObserved
                ? String.valueOf(latestAcceptedObservation.getOrDefault("status", "UNKNOWN"))
                : "MISSING";
        boolean terminalFailureAccepted = "ACCEPTED_TERMINAL_FAILURE".equals(acceptedObservationStatus);
        boolean rejectedObservation = acceptedObservationStatus.startsWith("REJECTED_");
        List<Map<String, Object>> policyChecks = List.of(
                policyCheck(
                        "mutationObservationAcceptanceGate",
                        observationAcceptanceReady,
                        String.valueOf(mutationObservationAcceptanceGate.getOrDefault("status", "UNKNOWN")),
                        "A disabled observation acceptance gate must refuse accepted observation intake before persistence can be considered."
                ),
                policyCheck(
                        "intakePersistencePolicy",
                        false,
                        "DISABLED",
                        "Accepted mutation result intake persistence is disabled."
                ),
                policyCheck(
                        "acceptedObservationPersistence",
                        false,
                        "DISABLED",
                        "Accepted mutation observations must not be persisted while intake persistence is disabled."
                ),
                policyCheck(
                        "rollbackFallbackExecution",
                        false,
                        "DISABLED",
                        "Rollback fallback execution remains disabled until accepted mutation observations are persisted."
                ),
                policyCheck(
                        "ragFreshnessUpdate",
                        false,
                        "DISABLED",
                        "RAG freshness updates remain disabled until accepted mutation observations are persisted."
                ),
                policyCheck(
                        "resultAggregation",
                        false,
                        "DISABLED",
                        "Mutation result aggregation remains disabled until accepted mutation observations are persisted."
                ),
                policyCheck(
                        "publication",
                        false,
                        "DISABLED",
                        "Final answer publication remains disabled until accepted mutation observations are persisted."
                ),
                policyCheck(
                        "finalAnswerGeneration",
                        false,
                        "DISABLED",
                        "Final-answer generation remains disabled until accepted mutation observations are persisted."
                )
        );
        List<Map<String, Object>> acceptedObservationAudit = List.of(
                auditCheck(
                        "acceptedMutationObservationReadiness",
                        acceptedObservationObserved ? "OBSERVED" : "MISSING",
                        acceptedObservationObserved,
                        "Latest accepted mutation observation visibility is recorded for audit only."
                ),
                auditCheck(
                        "acceptedMutationObservationStatus",
                        acceptedObservationStatus,
                        "ACCEPTED".equals(acceptedObservationStatus)
                                || terminalFailureAccepted
                                || rejectedObservation
                                || "MISSING".equals(acceptedObservationStatus),
                        "Accepted observation status is preserved so future intake can distinguish missing, rejected, terminal-failure, and accepted evidence."
                )
        );
        List<String> blockingKeys = new ArrayList<>(policyChecks.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        for (String key : List.of(
                "intakePersistenceEnabled",
                "acceptedObservationPersistenceEnabled",
                "rollbackFallbackExecutionEnabled",
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
        result.put("schema", "learnbot.local-agent.mutation-result-intake-persistence-gate.v1");
        result.put("status", observationAcceptanceReady ? "REFUSED_INTAKE_PERSISTENCE_DISABLED" : "BLOCKED_INTAKE_PERSISTENCE_DISABLED");
        result.put("observationAcceptanceReady", observationAcceptanceReady);
        result.put("prerequisitesPassed", observationAcceptanceReady);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourceObservationAcceptanceGateSchema", mutationObservationAcceptanceGate.get("schema"));
        result.put("sourceObservationAcceptanceGateStatus", mutationObservationAcceptanceGate.get("status"));
        result.put("sourceObservationAcceptanceGateSessionId", mutationObservationAcceptanceGate.get("sessionId"));
        result.put("sourceObservationAcceptanceGateUserId", mutationObservationAcceptanceGate.get("userId"));
        result.put("sourceObservationAcceptanceGateAgentId", mutationObservationAcceptanceGate.get("agentId"));
        result.put("sourceObservationAcceptanceGateWorkspaceId", mutationObservationAcceptanceGate.get("workspaceId"));
        result.put("sourceAcceptedMutationObservationSummarySchema", acceptedMutationObservationSummary.get("schema"));
        result.put("sourceAcceptedMutationObservationSummaryStatus", acceptedMutationObservationSummary.get("status"));
        result.put("sourceAcceptedMutationObservationSummaryObservationCount", acceptedMutationObservationSummary.get("observationCount"));
        result.put("sourceAcceptedMutationObservationSummaryAcceptedCount", acceptedMutationObservationSummary.get("acceptedCount"));
        result.put("sourceAcceptedMutationObservationSummaryRejectedCount", acceptedMutationObservationSummary.get("rejectedCount"));
        result.put("sourceAcceptedMutationObservationSummaryTerminalFailureAcceptedCount", acceptedMutationObservationSummary.get("terminalFailureAcceptedCount"));
        result.put("sourceAcceptedMutationObservationSummaryMissingMutationResultRiskVisible", numericValue(acceptedMutationObservationSummary.get("observationCount")) == 0);
        result.put("sourceAcceptedMutationObservationSummaryStaleIndexRiskVisible", numericValue(acceptedMutationObservationSummary.get("acceptedCount")) > 0);
        result.put("sourceAcceptedMutationObservationPublicationGateSchema", acceptedMutationObservationSummary.get("publicationGateSchema"));
        result.put("sourceAcceptedMutationObservationPublicationGateStatus", acceptedMutationObservationSummary.get("publicationGateStatus"));
        result.put("sourceAcceptedMutationObservationPublicationGateSessionId", acceptedMutationObservationSummary.get("publicationGateSessionId"));
        result.put("sourceAcceptedMutationObservationPublicationGateUserId", acceptedMutationObservationSummary.get("publicationGateUserId"));
        result.put("sourceAcceptedMutationObservationPublicationGateAgentId", acceptedMutationObservationSummary.get("publicationGateAgentId"));
        result.put("sourceAcceptedMutationObservationPublicationGateWorkspaceId", acceptedMutationObservationSummary.get("publicationGateWorkspaceId"));
        result.put("sourceAcceptedMutationObservationRollbackSummaryStatus", acceptedMutationObservationSummary.get("status"));
        result.put("sourceAcceptedMutationObservationRollbackSummaryObservationCount", acceptedMutationObservationSummary.get("observationCount"));
        result.put("sourceAcceptedMutationObservationRollbackSummaryAcceptedCount", acceptedMutationObservationSummary.get("acceptedCount"));
        result.put("sourceAcceptedMutationObservationRollbackSummaryRejectedCount", acceptedMutationObservationSummary.get("rejectedCount"));
        result.put("sourceAcceptedMutationObservationRollbackSummaryMissingMutationResultRiskVisible", numericValue(acceptedMutationObservationSummary.get("observationCount")) == 0);
        result.put("sourceAcceptedMutationObservationRollbackSummaryStaleIndexRiskVisible", numericValue(acceptedMutationObservationSummary.get("acceptedCount")) > 0);
        result.put("sourceAcceptedMutationObservationReadinessSchema", acceptedMutationObservationReadiness.get("schema"));
        result.put("sourceAcceptedMutationObservationReadinessStatus", acceptedMutationObservationReadiness.get("status"));
        result.put("sourceAcceptedMutationObservationObserved", acceptedObservationObserved);
        result.put("sourceAcceptedMutationObservationReadinessSessionId", acceptedMutationObservationReadiness.get("sessionId"));
        result.put("sourceAcceptedMutationObservationReadinessUserId", acceptedMutationObservationReadiness.get("userId"));
        result.put("sourceAcceptedMutationObservationReadinessAgentId", acceptedMutationObservationReadiness.get("agentId"));
        result.put("sourceAcceptedMutationObservationReadinessWorkspaceId", acceptedMutationObservationReadiness.get("workspaceId"));
        result.put("intakePersistencePolicy", "DISABLED_AUDIT_ONLY");
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
        result.put("message", observationAcceptanceReady
                ? "Local Agent mutation result intake persistence is explicitly refused: no accepted observation persistence, rollback fallback, RAG freshness update, aggregation, publication, or final answer is enabled."
                : "Local Agent mutation result intake persistence is blocked because the disabled observation acceptance gate is incomplete.");
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
        result.put("intakePersistenceEnabled", false);
        result.put("acceptedObservationPersistenceEnabled", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("publicationEnabled", false);
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
