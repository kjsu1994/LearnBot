package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class LocalAgentFinalAnswerPersistenceGateBuilder {

    Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationFinalAnswerCompletionGate
    ) {
        boolean finalAnswerCompletionReady = "REFUSED_FINAL_ANSWER_COMPLETION_DISABLED".equals(mutationFinalAnswerCompletionGate.get("status"))
                && Boolean.TRUE.equals(mutationFinalAnswerCompletionGate.get("prerequisitesPassed"));
        int expectedResultCount = numericValue(mutationFinalAnswerCompletionGate.get("expectedResultCount"));
        int completedResultCount = numericValue(mutationFinalAnswerCompletionGate.get("completedResultCount"));
        int acceptedResultCount = numericValue(mutationFinalAnswerCompletionGate.get("acceptedResultCount"));
        int rejectedResultCount = numericValue(mutationFinalAnswerCompletionGate.get("rejectedResultCount"));
        int intakePersistedResultCount = numericValue(mutationFinalAnswerCompletionGate.get("intakePersistedResultCount"));
        List<Map<String, Object>> policyChecks = List.of(
                policyCheck(
                        "mutationFinalAnswerCompletionGate",
                        finalAnswerCompletionReady,
                        String.valueOf(mutationFinalAnswerCompletionGate.getOrDefault("status", "UNKNOWN")),
                        "A disabled final-answer completion gate must refuse completion before final-answer persistence can be considered."
                ),
                policyCheck(
                        "finalAnswerPersistencePolicy",
                        false,
                        "DISABLED",
                        "Mutation final-answer persistence and conversation save are disabled."
                ),
                policyCheck(
                        "finalAnswerPersistence",
                        false,
                        "DISABLED",
                        "No final answer may be persisted while final-answer persistence is disabled."
                ),
                policyCheck(
                        "conversationTurnSave",
                        false,
                        "DISABLED",
                        "No conversation turn may be saved while final-answer persistence is disabled."
                ),
                policyCheck(
                        "finalAnswerDelivery",
                        false,
                        "DISABLED",
                        "No final answer may be delivered while final-answer persistence is disabled."
                )
        );
        List<String> blockingKeys = new ArrayList<>(policyChecks.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        for (String key : List.of("finalAnswerPersistenceEnabled", "conversationTurnSaveEnabled", "finalAnswerCompletionEnabled", "finalAnswerDeliveryEnabled", "finalAnswerGenerationEnabled", "mutationAllowed")) {
            if (!blockingKeys.contains(key)) {
                blockingKeys.add(key);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.mutation-final-answer-persistence-gate.v1");
        result.put("status", finalAnswerCompletionReady ? "REFUSED_FINAL_ANSWER_PERSISTENCE_DISABLED" : "BLOCKED_FINAL_ANSWER_PERSISTENCE_DISABLED");
        result.put("finalAnswerCompletionReady", finalAnswerCompletionReady);
        result.put("prerequisitesPassed", finalAnswerCompletionReady);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourceFinalAnswerCompletionGateSchema", mutationFinalAnswerCompletionGate.get("schema"));
        result.put("sourceFinalAnswerCompletionGateStatus", mutationFinalAnswerCompletionGate.get("status"));
        result.put("finalAnswerPersistencePolicy", "DISABLED_AUDIT_ONLY");
        result.put("finalAnswerPersistenceInvocationEnabled", false);
        result.put("conversationTurnSaveEnabled", false);
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
        result.put("blockingKeys", blockingKeys);
        result.put("message", finalAnswerCompletionReady
                ? "Local Agent mutation final-answer persistence is explicitly refused: no final answer is persisted and no conversation turn is saved."
                : "Local Agent mutation final-answer persistence is blocked because the disabled final-answer completion gate is incomplete.");
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
        result.put("message", message);
        return result;
    }

    private int numericValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
