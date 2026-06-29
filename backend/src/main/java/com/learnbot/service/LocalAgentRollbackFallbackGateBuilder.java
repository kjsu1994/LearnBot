package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class LocalAgentRollbackFallbackGateBuilder {

    Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationResultIntakePersistenceGate
    ) {
        boolean intakePersistenceReady = "REFUSED_INTAKE_PERSISTENCE_DISABLED".equals(
                mutationResultIntakePersistenceGate.get("status"))
                && Boolean.TRUE.equals(mutationResultIntakePersistenceGate.get("prerequisitesPassed"));
        int expectedResultCount = numericValue(mutationResultIntakePersistenceGate.get("expectedResultCount"));
        int completedResultCount = numericValue(mutationResultIntakePersistenceGate.get("completedResultCount"));
        int acceptedResultCount = numericValue(mutationResultIntakePersistenceGate.get("acceptedResultCount"));
        int rejectedResultCount = numericValue(mutationResultIntakePersistenceGate.get("rejectedResultCount"));
        int intakePersistedResultCount = numericValue(mutationResultIntakePersistenceGate.get("intakePersistedResultCount"));
        List<Map<String, Object>> policyChecks = List.of(
                policyCheck(
                        "mutationResultIntakePersistenceGate",
                        intakePersistenceReady,
                        String.valueOf(mutationResultIntakePersistenceGate.getOrDefault("status", "UNKNOWN")),
                        "A disabled intake persistence gate must refuse accepted-observation persistence before rollback fallback can be considered."
                ),
                policyCheck(
                        "rollbackFallbackPolicy",
                        false,
                        "DISABLED",
                        "Rollback fallback execution is disabled."
                ),
                policyCheck(
                        "rollbackFallbackExecution",
                        false,
                        "DISABLED",
                        "No rollback fallback may execute while the rollback fallback gate is disabled."
                ),
                policyCheck(
                        "ragFreshnessUpdate",
                        false,
                        "DISABLED",
                        "RAG freshness updates remain disabled until rollback fallback outcomes are modeled."
                ),
                policyCheck(
                        "resultAggregation",
                        false,
                        "DISABLED",
                        "Mutation result aggregation remains disabled until rollback fallback outcomes are modeled."
                ),
                policyCheck(
                        "publication",
                        false,
                        "DISABLED",
                        "Final answer publication remains disabled until rollback fallback outcomes are modeled."
                ),
                policyCheck(
                        "finalAnswerGeneration",
                        false,
                        "DISABLED",
                        "Final-answer generation remains disabled until rollback fallback outcomes are modeled."
                )
        );
        List<String> blockingKeys = new ArrayList<>(policyChecks.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        for (String key : List.of(
                "rollbackFallbackExecutionEnabled",
                "ragFreshnessUpdateEnabled",
                "mutationResultAggregationEnabled",
                "publicationEnabled",
                "finalAnswerGenerationEnabled",
                "mutationAllowed"
        )) {
            if (!blockingKeys.contains(key)) {
                blockingKeys.add(key);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.mutation-rollback-fallback-gate.v1");
        result.put("status", intakePersistenceReady ? "REFUSED_ROLLBACK_FALLBACK_DISABLED" : "BLOCKED_ROLLBACK_FALLBACK_DISABLED");
        result.put("intakePersistenceReady", intakePersistenceReady);
        result.put("prerequisitesPassed", intakePersistenceReady);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourceResultIntakePersistenceGateSchema", mutationResultIntakePersistenceGate.get("schema"));
        result.put("sourceResultIntakePersistenceGateStatus", mutationResultIntakePersistenceGate.get("status"));
        result.put("rollbackFallbackPolicy", "DISABLED_AUDIT_ONLY");
        result.put("rollbackFallbackInvocationEnabled", false);
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
        result.put("blockingKeys", blockingKeys);
        result.put("message", intakePersistenceReady
                ? "Local Agent mutation rollback fallback is explicitly refused: no rollback fallback execution, RAG freshness update, aggregation, publication, or final answer is enabled."
                : "Local Agent mutation rollback fallback is blocked because the disabled intake persistence gate is incomplete.");
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
        result.put("rollbackFallbackExecutionEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("message", message);
        return result;
    }

    private int numericValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
