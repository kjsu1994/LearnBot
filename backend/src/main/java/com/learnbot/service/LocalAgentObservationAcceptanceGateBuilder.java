package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class LocalAgentObservationAcceptanceGateBuilder {

    Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationPostExecutionObservationGate
    ) {
        boolean postExecutionObservationReady = "REFUSED_POST_EXECUTION_OBSERVATION_DISABLED".equals(
                mutationPostExecutionObservationGate.get("status"))
                && Boolean.TRUE.equals(mutationPostExecutionObservationGate.get("prerequisitesPassed"));
        int expectedResultCount = numericValue(mutationPostExecutionObservationGate.get("expectedResultCount"));
        int completedResultCount = numericValue(mutationPostExecutionObservationGate.get("completedResultCount"));
        int acceptedResultCount = numericValue(mutationPostExecutionObservationGate.get("acceptedResultCount"));
        int rejectedResultCount = numericValue(mutationPostExecutionObservationGate.get("rejectedResultCount"));
        int intakePersistedResultCount = 0;
        List<Map<String, Object>> policyChecks = List.of(
                policyCheck(
                        "mutationPostExecutionObservationGate",
                        postExecutionObservationReady,
                        String.valueOf(mutationPostExecutionObservationGate.getOrDefault("status", "UNKNOWN")),
                        "A disabled post-execution observation gate must refuse completed-result capture before acceptance can be considered."
                ),
                policyCheck(
                        "acceptancePolicy",
                        false,
                        "DISABLED",
                        "Completed mutation observation acceptance is disabled."
                ),
                policyCheck(
                        "intakePersistence",
                        false,
                        "DISABLED",
                        "Accepted mutation observation intake must not be persisted while acceptance is disabled."
                ),
                policyCheck(
                        "rollbackFallbackExecution",
                        false,
                        "DISABLED",
                        "Rollback fallback execution remains disabled until accepted observations are persisted."
                ),
                policyCheck(
                        "ragFreshnessUpdate",
                        false,
                        "DISABLED",
                        "RAG freshness updates remain disabled until accepted observations are persisted."
                ),
                policyCheck(
                        "resultAggregation",
                        false,
                        "DISABLED",
                        "Mutation result aggregation remains disabled until accepted observations are persisted."
                ),
                policyCheck(
                        "publication",
                        false,
                        "DISABLED",
                        "Final answer publication remains disabled until accepted observations are persisted."
                ),
                policyCheck(
                        "finalAnswerGeneration",
                        false,
                        "DISABLED",
                        "Final-answer generation remains disabled until accepted observations are persisted."
                )
        );
        List<String> blockingKeys = new ArrayList<>(policyChecks.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        for (String key : List.of(
                "observationAcceptanceEnabled",
                "intakePersistenceEnabled",
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
        result.put("schema", "learnbot.local-agent.mutation-observation-acceptance-gate.v1");
        result.put("status", postExecutionObservationReady ? "REFUSED_OBSERVATION_ACCEPTANCE_DISABLED" : "BLOCKED_OBSERVATION_ACCEPTANCE_DISABLED");
        result.put("postExecutionObservationReady", postExecutionObservationReady);
        result.put("prerequisitesPassed", postExecutionObservationReady);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourcePostExecutionObservationGateSchema", mutationPostExecutionObservationGate.get("schema"));
        result.put("sourcePostExecutionObservationGateStatus", mutationPostExecutionObservationGate.get("status"));
        result.put("sourcePostExecutionObservationGateSessionId", mutationPostExecutionObservationGate.get("sessionId"));
        result.put("sourcePostExecutionObservationGateUserId", mutationPostExecutionObservationGate.get("userId"));
        result.put("sourcePostExecutionObservationGateAgentId", mutationPostExecutionObservationGate.get("agentId"));
        result.put("sourcePostExecutionObservationGateWorkspaceId", mutationPostExecutionObservationGate.get("workspaceId"));
        result.put("acceptancePolicy", "DISABLED_AUDIT_ONLY");
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
        result.put("rollbackFallbackExecutionEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("blockingKeys", blockingKeys);
        result.put("message", postExecutionObservationReady
                ? "Local Agent mutation observation acceptance is explicitly refused: no accepted observation intake, rollback fallback, RAG freshness update, aggregation, publication, or final answer is enabled."
                : "Local Agent mutation observation acceptance is blocked because the disabled post-execution observation gate is incomplete.");
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
        result.put("observationAcceptanceEnabled", false);
        result.put("intakePersistenceEnabled", false);
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
