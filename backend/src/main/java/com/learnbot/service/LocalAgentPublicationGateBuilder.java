package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class LocalAgentPublicationGateBuilder {

    Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationResultAggregationGate
    ) {
        boolean aggregationReady = "REFUSED_RESULT_AGGREGATION_DISABLED".equals(mutationResultAggregationGate.get("status"))
                && Boolean.TRUE.equals(mutationResultAggregationGate.get("prerequisitesPassed"));
        int expectedResultCount = numericValue(mutationResultAggregationGate.get("expectedResultCount"));
        int completedResultCount = numericValue(mutationResultAggregationGate.get("completedResultCount"));
        int acceptedResultCount = numericValue(mutationResultAggregationGate.get("acceptedResultCount"));
        int rejectedResultCount = numericValue(mutationResultAggregationGate.get("rejectedResultCount"));
        int intakePersistedResultCount = numericValue(mutationResultAggregationGate.get("intakePersistedResultCount"));
        List<Map<String, Object>> policyChecks = List.of(
                policyCheck(
                        "mutationResultAggregationGate",
                        aggregationReady,
                        String.valueOf(mutationResultAggregationGate.getOrDefault("status", "UNKNOWN")),
                        "A disabled result aggregation gate must refuse aggregation before publication can be considered."
                ),
                policyCheck(
                        "publicationPolicy",
                        false,
                        "DISABLED",
                        "Mutation publication is disabled."
                ),
                policyCheck(
                        "publication",
                        false,
                        "DISABLED",
                        "No mutation publication may run while publication is disabled."
                ),
                policyCheck(
                        "finalAnswerGeneration",
                        false,
                        "DISABLED",
                        "Final-answer generation remains disabled until publication state is modeled."
                )
        );
        List<String> blockingKeys = new ArrayList<>(policyChecks.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        for (String key : List.of(
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
        result.put("schema", "learnbot.local-agent.mutation-publication-gate.v1");
        result.put("status", aggregationReady ? "REFUSED_PUBLICATION_DISABLED" : "BLOCKED_PUBLICATION_DISABLED");
        result.put("resultAggregationReady", aggregationReady);
        result.put("prerequisitesPassed", aggregationReady);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourceResultAggregationGateSchema", mutationResultAggregationGate.get("schema"));
        result.put("sourceResultAggregationGateStatus", mutationResultAggregationGate.get("status"));
        result.put("sourceResultAggregationGateSessionId", mutationResultAggregationGate.get("sessionId"));
        result.put("sourceResultAggregationGateUserId", mutationResultAggregationGate.get("userId"));
        result.put("sourceResultAggregationGateAgentId", mutationResultAggregationGate.get("agentId"));
        result.put("sourceResultAggregationGateWorkspaceId", mutationResultAggregationGate.get("workspaceId"));
        result.put("publicationPolicy", "DISABLED_AUDIT_ONLY");
        result.put("publicationInvocationEnabled", false);
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
        result.put("message", aggregationReady
                ? "Local Agent mutation publication is explicitly refused: no publication or final answer is enabled."
                : "Local Agent mutation publication is blocked because the disabled result aggregation gate is incomplete.");
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
}
