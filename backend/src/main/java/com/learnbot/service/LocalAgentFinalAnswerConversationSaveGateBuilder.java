package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class LocalAgentFinalAnswerConversationSaveGateBuilder {

    Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationFinalAnswerPersistenceGate
    ) {
        boolean finalAnswerPersistenceReady = "REFUSED_FINAL_ANSWER_PERSISTENCE_DISABLED".equals(mutationFinalAnswerPersistenceGate.get("status"))
                && Boolean.TRUE.equals(mutationFinalAnswerPersistenceGate.get("prerequisitesPassed"));
        int expectedResultCount = numericValue(mutationFinalAnswerPersistenceGate.get("expectedResultCount"));
        int completedResultCount = numericValue(mutationFinalAnswerPersistenceGate.get("completedResultCount"));
        int acceptedResultCount = numericValue(mutationFinalAnswerPersistenceGate.get("acceptedResultCount"));
        int rejectedResultCount = numericValue(mutationFinalAnswerPersistenceGate.get("rejectedResultCount"));
        int intakePersistedResultCount = numericValue(mutationFinalAnswerPersistenceGate.get("intakePersistedResultCount"));
        List<Map<String, Object>> policyChecks = List.of(
                policyCheck(
                        "mutationFinalAnswerPersistenceGate",
                        finalAnswerPersistenceReady,
                        String.valueOf(mutationFinalAnswerPersistenceGate.getOrDefault("status", "UNKNOWN")),
                        "A disabled final-answer persistence gate must refuse persistence before conversation save can be considered."
                ),
                policyCheck(
                        "finalAnswerConversationSavePolicy",
                        false,
                        "DISABLED",
                        "Mutation final-answer conversation save and user-visible completion are disabled."
                ),
                policyCheck(
                        "conversationTurnSave",
                        false,
                        "DISABLED",
                        "No conversation turn may be saved while conversation save is disabled."
                ),
                policyCheck(
                        "userVisibleCompletion",
                        false,
                        "DISABLED",
                        "No user-visible completion may be marked while conversation save is disabled."
                ),
                policyCheck(
                        "finalAnswerDelivery",
                        false,
                        "DISABLED",
                        "No final answer may be delivered while conversation save is disabled."
                )
        );
        List<String> blockingKeys = new ArrayList<>(policyChecks.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        for (String key : List.of(
                "conversationTurnSaveEnabled",
                "userVisibleCompletionEnabled",
                "finalResponseHandoffEnabled",
                "deliveryReceiptEnabled",
                "acknowledgementSaveEnabled",
                "finalAnswerPersistenceEnabled",
                "finalAnswerDeliveryEnabled",
                "finalAnswerCompletionEnabled",
                "finalAnswerGenerationEnabled",
                "mutationAllowed"
        )) {
            if (!blockingKeys.contains(key)) {
                blockingKeys.add(key);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.mutation-final-answer-conversation-save-gate.v1");
        result.put("status", finalAnswerPersistenceReady ? "REFUSED_FINAL_ANSWER_CONVERSATION_SAVE_DISABLED" : "BLOCKED_FINAL_ANSWER_CONVERSATION_SAVE_DISABLED");
        result.put("finalAnswerPersistenceReady", finalAnswerPersistenceReady);
        result.put("prerequisitesPassed", finalAnswerPersistenceReady);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourceFinalAnswerPersistenceGateSchema", mutationFinalAnswerPersistenceGate.get("schema"));
        result.put("sourceFinalAnswerPersistenceGateStatus", mutationFinalAnswerPersistenceGate.get("status"));
        result.put("sourceFinalAnswerPersistenceGateSessionId", mutationFinalAnswerPersistenceGate.get("sessionId"));
        result.put("sourceFinalAnswerPersistenceGateUserId", mutationFinalAnswerPersistenceGate.get("userId"));
        result.put("sourceFinalAnswerPersistenceGateAgentId", mutationFinalAnswerPersistenceGate.get("agentId"));
        result.put("sourceFinalAnswerPersistenceGateWorkspaceId", mutationFinalAnswerPersistenceGate.get("workspaceId"));
        result.put("finalAnswerConversationSavePolicy", "DISABLED_AUDIT_ONLY");
        result.put("conversationTurnSaveEnabled", false);
        result.put("conversationTurnSaveInvocationEnabled", false);
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
        result.put("finalAnswerDeliveryEnabled", false);
        result.put("finalAnswerPersistenceEnabled", false);
        result.put("finalResponseHandoffEnabled", false);
        result.put("deliveryReceiptEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("blockingKeys", blockingKeys);
        result.put("message", finalAnswerPersistenceReady
                ? "Local Agent mutation final-answer conversation save is explicitly refused: no conversation turn is saved and no user-visible completion is marked."
                : "Local Agent mutation final-answer conversation save is blocked because the disabled final-answer persistence gate is incomplete.");
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
        result.put("finalAnswerPersistenceEnabled", false);
        result.put("conversationTurnSaveEnabled", false);
        result.put("userVisibleCompletionEnabled", false);
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
