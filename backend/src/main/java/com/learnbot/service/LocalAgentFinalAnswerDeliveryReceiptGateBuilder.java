package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class LocalAgentFinalAnswerDeliveryReceiptGateBuilder {

    Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationFinalAnswerDeliveryGate
    ) {
        boolean finalAnswerDeliveryReady = "REFUSED_FINAL_ANSWER_DELIVERY_DISABLED".equals(mutationFinalAnswerDeliveryGate.get("status"))
                && Boolean.TRUE.equals(mutationFinalAnswerDeliveryGate.get("prerequisitesPassed"));
        int expectedResultCount = numericValue(mutationFinalAnswerDeliveryGate.get("expectedResultCount"));
        int completedResultCount = numericValue(mutationFinalAnswerDeliveryGate.get("completedResultCount"));
        int acceptedResultCount = numericValue(mutationFinalAnswerDeliveryGate.get("acceptedResultCount"));
        int rejectedResultCount = numericValue(mutationFinalAnswerDeliveryGate.get("rejectedResultCount"));
        int intakePersistedResultCount = numericValue(mutationFinalAnswerDeliveryGate.get("intakePersistedResultCount"));
        List<Map<String, Object>> policyChecks = List.of(
                policyCheck(
                        "mutationFinalAnswerDeliveryGate",
                        finalAnswerDeliveryReady,
                        String.valueOf(mutationFinalAnswerDeliveryGate.getOrDefault("status", "UNKNOWN")),
                        "A disabled final-answer delivery gate must refuse delivery before delivery receipt can be considered."
                ),
                policyCheck(
                        "deliveryReceiptPolicy",
                        false,
                        "DISABLED",
                        "Mutation final-answer delivery receipt and acknowledgement are disabled."
                ),
                policyCheck(
                        "deliveryReceipt",
                        false,
                        "DISABLED",
                        "No delivery receipt may be recorded while delivery receipt is disabled."
                ),
                policyCheck(
                        "finalAnswerDelivery",
                        false,
                        "DISABLED",
                        "No final answer may be delivered while delivery receipt is disabled."
                ),
                policyCheck(
                        "deliveryHandoff",
                        false,
                        "DISABLED",
                        "No delivery handoff may run while delivery receipt is disabled."
                )
        );
        List<String> blockingKeys = new ArrayList<>(policyChecks.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        for (String key : List.of("deliveryReceiptEnabled", "finalAnswerDeliveryEnabled", "deliveryHandoffEnabled", "finalResponseHandoffEnabled", "userVisibleCompletionEnabled", "conversationTurnSaveEnabled", "finalAnswerPersistenceEnabled", "finalAnswerCompletionEnabled", "finalAnswerGenerationEnabled", "mutationAllowed")) {
            if (!blockingKeys.contains(key)) {
                blockingKeys.add(key);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.mutation-final-answer-delivery-receipt-gate.v1");
        result.put("status", finalAnswerDeliveryReady ? "REFUSED_FINAL_ANSWER_DELIVERY_RECEIPT_DISABLED" : "BLOCKED_FINAL_ANSWER_DELIVERY_RECEIPT_DISABLED");
        result.put("finalAnswerDeliveryReady", finalAnswerDeliveryReady);
        result.put("prerequisitesPassed", finalAnswerDeliveryReady);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourceFinalAnswerDeliveryGateSchema", mutationFinalAnswerDeliveryGate.get("schema"));
        result.put("sourceFinalAnswerDeliveryGateStatus", mutationFinalAnswerDeliveryGate.get("status"));
        result.put("deliveryReceiptPolicy", "DISABLED_AUDIT_ONLY");
        result.put("deliveryReceiptEnabled", false);
        result.put("finalAnswerDeliveryEnabled", false);
        result.put("deliveryHandoffEnabled", false);
        result.put("finalResponseHandoffEnabled", false);
        result.put("userVisibleCompletionEnabled", false);
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
        result.put("finalAnswerPersistenceEnabled", false);
        result.put("conversationTurnSaveEnabled", false);
        result.put("blockingKeys", blockingKeys);
        result.put("message", finalAnswerDeliveryReady
                ? "Local Agent mutation final-answer delivery receipt is explicitly refused: no delivery receipt is recorded and no acknowledgement is saved."
                : "Local Agent mutation final-answer delivery receipt is blocked because the disabled final-answer delivery gate is incomplete.");
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
        result.put("deliveryReceiptEnabled", false);
        result.put("finalAnswerPersistenceEnabled", false);
        result.put("conversationTurnSaveEnabled", false);
        result.put("userVisibleCompletionEnabled", false);
        result.put("finalResponseHandoffEnabled", false);
        result.put("deliveryHandoffEnabled", false);
        result.put("message", message);
        return result;
    }

    private int numericValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
