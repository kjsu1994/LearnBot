package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class LocalAgentResultIntakePersistenceGateBuilder {

    Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationObservationAcceptanceGate
    ) {
        boolean observationAcceptanceReady = "REFUSED_OBSERVATION_ACCEPTANCE_DISABLED".equals(mutationObservationAcceptanceGate.get("status"))
                && Boolean.TRUE.equals(mutationObservationAcceptanceGate.get("prerequisitesPassed"));
        int expectedResultCount = numericValue(mutationObservationAcceptanceGate.get("expectedResultCount"));
        int completedResultCount = numericValue(mutationObservationAcceptanceGate.get("completedResultCount"));
        int acceptedResultCount = numericValue(mutationObservationAcceptanceGate.get("acceptedResultCount"));
        int rejectedResultCount = numericValue(mutationObservationAcceptanceGate.get("rejectedResultCount"));
        int intakePersistedResultCount = numericValue(mutationObservationAcceptanceGate.get("intakePersistedResultCount"));
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
        result.put("intakePersistencePolicy", "DISABLED_AUDIT_ONLY");
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
        result.put("message", observationAcceptanceReady
                ? "Local Agent mutation result intake persistence is explicitly refused: no accepted observation persistence, rollback fallback, RAG freshness update, aggregation, publication, or final answer is enabled."
                : "Local Agent mutation result intake persistence is blocked because the disabled observation acceptance gate is incomplete.");
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
        result.put("message", message);
        return result;
    }

    private int numericValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
