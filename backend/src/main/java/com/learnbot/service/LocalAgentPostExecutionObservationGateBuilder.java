package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class LocalAgentPostExecutionObservationGateBuilder {

    Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationExecutionGate
    ) {
        boolean executionGateReady = "REFUSED_EXECUTION_DISABLED".equals(mutationExecutionGate.get("status"))
                && Boolean.TRUE.equals(mutationExecutionGate.get("prerequisitesPassed"));
        int expectedResultCount = numericValue(mutationExecutionGate.get("expectedRequestCount"));
        int completedResultCount = numericValue(mutationExecutionGate.get("completedRequestCount"));
        int acceptedResultCount = 0;
        int rejectedResultCount = 0;
        List<Map<String, Object>> policyChecks = List.of(
                policyCheck(
                        "mutationExecutionGate",
                        executionGateReady,
                        String.valueOf(mutationExecutionGate.getOrDefault("status", "UNKNOWN")),
                        "A disabled execution gate must refuse Local Agent execution before post-execution observations can be considered."
                ),
                policyCheck(
                        "observationPolicy",
                        false,
                        "DISABLED",
                        "Post-execution mutation observation capture is disabled."
                ),
                policyCheck(
                        "completedResultPersistence",
                        false,
                        "DISABLED",
                        "Completed mutation results must not be persisted while observation capture is disabled."
                ),
                policyCheck(
                        "rollbackFallbackExecution",
                        false,
                        "DISABLED",
                        "Rollback fallback execution remains disabled until mutation observations are accepted."
                ),
                policyCheck(
                        "ragFreshnessUpdate",
                        false,
                        "DISABLED",
                        "RAG freshness updates remain disabled until completed mutation observations are accepted."
                ),
                policyCheck(
                        "resultAggregation",
                        false,
                        "DISABLED",
                        "Mutation result aggregation remains disabled until post-execution observations are accepted."
                ),
                policyCheck(
                        "publication",
                        false,
                        "DISABLED",
                        "Final answer publication remains disabled until completed mutation observations are accepted."
                )
        );
        List<String> blockingKeys = new ArrayList<>(policyChecks.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        for (String key : List.of(
                "postExecutionObservationEnabled",
                "completedResultPersistenceEnabled",
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
        result.put("schema", "learnbot.local-agent.mutation-post-execution-observation-gate.v1");
        result.put("status", executionGateReady ? "REFUSED_POST_EXECUTION_OBSERVATION_DISABLED" : "BLOCKED_POST_EXECUTION_OBSERVATION_DISABLED");
        result.put("executionGateReady", executionGateReady);
        result.put("prerequisitesPassed", executionGateReady);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourceExecutionGateSchema", mutationExecutionGate.get("schema"));
        result.put("sourceExecutionGateStatus", mutationExecutionGate.get("status"));
        result.put("observationPolicy", "DISABLED_AUDIT_ONLY");
        result.put("expectedResultCount", expectedResultCount);
        result.put("completedResultCount", completedResultCount);
        result.put("acceptedResultCount", acceptedResultCount);
        result.put("rejectedResultCount", rejectedResultCount);
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
        result.put("rollbackFallbackExecutionEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("blockingKeys", blockingKeys);
        result.put("message", executionGateReady
                ? "Local Agent post-execution mutation observation is explicitly refused: no completed-result capture, rollback fallback, RAG freshness update, aggregation, publication, or final answer is enabled."
                : "Local Agent post-execution mutation observation is blocked because the disabled mutation execution gate is incomplete.");
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
