package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class LocalAgentFinalAnswerUserVisibleCompletionGateBuilder {

    Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationFinalAnswerConversationSaveGate
    ) {
        boolean finalAnswerConversationSaveReady = "REFUSED_FINAL_ANSWER_CONVERSATION_SAVE_DISABLED".equals(mutationFinalAnswerConversationSaveGate.get("status"))
                && Boolean.TRUE.equals(mutationFinalAnswerConversationSaveGate.get("prerequisitesPassed"));
        int expectedResultCount = numericValue(mutationFinalAnswerConversationSaveGate.get("expectedResultCount"));
        int completedResultCount = numericValue(mutationFinalAnswerConversationSaveGate.get("completedResultCount"));
        int acceptedResultCount = numericValue(mutationFinalAnswerConversationSaveGate.get("acceptedResultCount"));
        int rejectedResultCount = numericValue(mutationFinalAnswerConversationSaveGate.get("rejectedResultCount"));
        int intakePersistedResultCount = numericValue(mutationFinalAnswerConversationSaveGate.get("intakePersistedResultCount"));
        List<Map<String, Object>> policyChecks = List.of(
                policyCheck(
                        "mutationFinalAnswerConversationSaveGate",
                        finalAnswerConversationSaveReady,
                        String.valueOf(mutationFinalAnswerConversationSaveGate.getOrDefault("status", "UNKNOWN")),
                        "A disabled final-answer conversation-save gate must refuse conversation save before user-visible completion can be considered."
                ),
                policyCheck(
                        "userVisibleCompletionPolicy",
                        false,
                        "DISABLED",
                        "Mutation final-answer user-visible completion and final-response handoff are disabled."
                ),
                policyCheck(
                        "userVisibleCompletion",
                        false,
                        "DISABLED",
                        "No user-visible completion may be marked while user-visible completion is disabled."
                ),
                policyCheck(
                        "finalResponseHandoff",
                        false,
                        "DISABLED",
                        "No final response may be handed off while user-visible completion is disabled."
                ),
                policyCheck(
                        "conversationTurnSave",
                        false,
                        "DISABLED",
                        "No conversation turn may be saved while user-visible completion is disabled."
                )
        );
        List<String> blockingKeys = new ArrayList<>(policyChecks.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        for (String key : List.of(
                "userVisibleCompletionEnabled",
                "finalResponseHandoffEnabled",
                "deliveryReceiptEnabled",
                "acknowledgementSaveEnabled",
                "conversationTurnSaveEnabled",
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
        result.put("schema", "learnbot.local-agent.mutation-final-answer-user-visible-completion-gate.v1");
        result.put("status", finalAnswerConversationSaveReady ? "REFUSED_FINAL_ANSWER_USER_VISIBLE_COMPLETION_DISABLED" : "BLOCKED_FINAL_ANSWER_USER_VISIBLE_COMPLETION_DISABLED");
        result.put("finalAnswerConversationSaveReady", finalAnswerConversationSaveReady);
        result.put("prerequisitesPassed", finalAnswerConversationSaveReady);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourceFinalAnswerConversationSaveGateSchema", mutationFinalAnswerConversationSaveGate.get("schema"));
        result.put("sourceFinalAnswerConversationSaveGateStatus", mutationFinalAnswerConversationSaveGate.get("status"));
        result.put("sourceFinalAnswerConversationSaveGateSessionId", mutationFinalAnswerConversationSaveGate.get("sessionId"));
        result.put("sourceFinalAnswerConversationSaveGateUserId", mutationFinalAnswerConversationSaveGate.get("userId"));
        result.put("sourceFinalAnswerConversationSaveGateAgentId", mutationFinalAnswerConversationSaveGate.get("agentId"));
        result.put("sourceFinalAnswerConversationSaveGateWorkspaceId", mutationFinalAnswerConversationSaveGate.get("workspaceId"));
        result.put("userVisibleCompletionPolicy", "DISABLED_AUDIT_ONLY");
        result.put("userVisibleCompletionEnabled", false);
        result.put("finalResponseHandoffEnabled", false);
        result.put("deliveryReceiptEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
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
        result.put("conversationTurnSaveEnabled", false);
        result.put("deliveryReceiptEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("blockingKeys", blockingKeys);
        result.put("message", finalAnswerConversationSaveReady
                ? "Local Agent mutation final-answer user-visible completion is explicitly refused: no user-visible completion is marked and no final response is handed off."
                : "Local Agent mutation final-answer user-visible completion is blocked because the disabled final-answer conversation-save gate is incomplete.");
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
