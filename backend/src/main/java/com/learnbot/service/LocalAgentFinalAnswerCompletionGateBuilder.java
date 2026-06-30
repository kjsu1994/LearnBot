package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class LocalAgentFinalAnswerCompletionGateBuilder {

    Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationFinalAnswerGenerationGate
    ) {
        boolean finalAnswerGenerationReady = "REFUSED_FINAL_ANSWER_GENERATION_DISABLED".equals(mutationFinalAnswerGenerationGate.get("status"))
                && Boolean.TRUE.equals(mutationFinalAnswerGenerationGate.get("prerequisitesPassed"));
        int expectedResultCount = numericValue(mutationFinalAnswerGenerationGate.get("expectedResultCount"));
        int completedResultCount = numericValue(mutationFinalAnswerGenerationGate.get("completedResultCount"));
        int acceptedResultCount = numericValue(mutationFinalAnswerGenerationGate.get("acceptedResultCount"));
        int rejectedResultCount = numericValue(mutationFinalAnswerGenerationGate.get("rejectedResultCount"));
        int intakePersistedResultCount = numericValue(mutationFinalAnswerGenerationGate.get("intakePersistedResultCount"));
        List<Map<String, Object>> policyChecks = List.of(
                policyCheck(
                        "mutationFinalAnswerGenerationGate",
                        finalAnswerGenerationReady,
                        String.valueOf(mutationFinalAnswerGenerationGate.getOrDefault("status", "UNKNOWN")),
                        "A disabled final-answer generation gate must refuse generation before final-answer completion can be considered."
                ),
                policyCheck(
                        "finalAnswerCompletionPolicy",
                        false,
                        "DISABLED",
                        "Mutation final-answer completion and delivery are disabled."
                ),
                policyCheck(
                        "finalAnswerCompletion",
                        false,
                        "DISABLED",
                        "No final answer may be completed while final-answer completion is disabled."
                ),
                policyCheck(
                        "finalAnswerDelivery",
                        false,
                        "DISABLED",
                        "No final answer may be delivered while final-answer delivery is disabled."
                )
        );
        List<String> blockingKeys = new ArrayList<>(policyChecks.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        for (String key : List.of(
                "finalAnswerCompletionEnabled",
                "finalAnswerDeliveryEnabled",
                "finalResponseHandoffEnabled",
                "deliveryReceiptEnabled",
                "acknowledgementSaveEnabled",
                "finalAnswerGenerationEnabled",
                "mutationAllowed"
        )) {
            if (!blockingKeys.contains(key)) {
                blockingKeys.add(key);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.mutation-final-answer-completion-gate.v1");
        result.put("status", finalAnswerGenerationReady ? "REFUSED_FINAL_ANSWER_COMPLETION_DISABLED" : "BLOCKED_FINAL_ANSWER_COMPLETION_DISABLED");
        result.put("finalAnswerGenerationReady", finalAnswerGenerationReady);
        result.put("prerequisitesPassed", finalAnswerGenerationReady);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourceFinalAnswerGenerationGateSchema", mutationFinalAnswerGenerationGate.get("schema"));
        result.put("sourceFinalAnswerGenerationGateStatus", mutationFinalAnswerGenerationGate.get("status"));
        result.put("sourceFinalAnswerGenerationGateSessionId", mutationFinalAnswerGenerationGate.get("sessionId"));
        result.put("sourceFinalAnswerGenerationGateUserId", mutationFinalAnswerGenerationGate.get("userId"));
        result.put("sourceFinalAnswerGenerationGateAgentId", mutationFinalAnswerGenerationGate.get("agentId"));
        result.put("sourceFinalAnswerGenerationGateWorkspaceId", mutationFinalAnswerGenerationGate.get("workspaceId"));
        result.put("finalAnswerCompletionPolicy", "DISABLED_AUDIT_ONLY");
        result.put("finalAnswerCompletionInvocationEnabled", false);
        result.put("finalAnswerDeliveryEnabled", false);
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
        result.put("message", finalAnswerGenerationReady
                ? "Local Agent mutation final-answer completion is explicitly refused: no final answer is completed or delivered."
                : "Local Agent mutation final-answer completion is blocked because the disabled final-answer generation gate is incomplete.");
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
