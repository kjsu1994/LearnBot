package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class LocalAgentWriteHelperSafetyGateBuilder {

    Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationExecutionGate
    ) {
        boolean executionGateReady = "REFUSED_EXECUTION_DISABLED".equals(mutationExecutionGate.get("status"))
                && Boolean.TRUE.equals(mutationExecutionGate.get("prerequisitesPassed"));
        int expectedRequestCount = numericValue(mutationExecutionGate.get("expectedRequestCount"));
        List<Map<String, Object>> policyChecks = List.of(
                policyCheck(
                        "mutationExecutionGate",
                        executionGateReady,
                        String.valueOf(mutationExecutionGate.getOrDefault("status", "UNKNOWN")),
                        "A disabled execution gate must refuse mutation execution before write-helper safety can be considered."
                ),
                policyCheck(
                        "writeHelperPolicy",
                        false,
                        "DISABLED",
                        "The Local Agent write helper is not connected to public patch.apply mutation."
                ),
                policyCheck(
                        "workspaceContainment",
                        false,
                        "REQUIRED_DISABLED",
                        "A future write helper must re-check approved workspace containment immediately before every write."
                ),
                policyCheck(
                        "snapshotManifest",
                        false,
                        "REQUIRED_DISABLED",
                        "A future write helper must require a fresh managed snapshot manifest before mutation."
                ),
                policyCheck(
                        "hashRecheck",
                        false,
                        "REQUIRED_DISABLED",
                        "A future write helper must re-check expected hashes after snapshot creation and before rewriting files."
                ),
                policyCheck(
                        "atomicRewrite",
                        false,
                        "REQUIRED_DISABLED",
                        "A future write helper must use the guarded temp-file rewrite sequence and report before/after hashes."
                ),
                policyCheck(
                        "rollbackContract",
                        false,
                        "REQUIRED_DISABLED",
                        "A future write helper must keep rollback restore approval and manifest validation available before writes."
                )
        );
        List<String> blockingKeys = new ArrayList<>(policyChecks.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        for (String key : List.of(
                "writeHelperEnabled",
                "applyEnabled",
                "mutationAllowed",
                "rollbackRestoreEnabled",
                "requestCreationEnabled",
                "pushEnabled",
                "claimEnabled"
        )) {
            if (!blockingKeys.contains(key)) {
                blockingKeys.add(key);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.mutation-write-helper-safety-gate.v1");
        result.put("status", executionGateReady ? "REFUSED_WRITE_HELPER_DISABLED" : "BLOCKED_WRITE_HELPER_DISABLED");
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
        result.put("writeHelperPolicy", "DISABLED_AUDIT_ONLY");
        result.put("expectedRequestCount", expectedRequestCount);
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
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("blockingKeys", blockingKeys);
        result.put("message", executionGateReady
                ? "Local Agent write-helper safety is explicitly refused: write helper, apply, mutation, and rollback restore remain disabled until guarded write preconditions are implemented end to end."
                : "Local Agent write-helper safety is blocked because the disabled mutation execution gate is incomplete.");
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
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        result.put("message", message);
        return result;
    }

    private int numericValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
