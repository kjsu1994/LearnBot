package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class LocalAgentMutationExecutionReadinessBoundaryBuilder {

    Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationHandoffSummary,
            Map<String, Object> mutationExecutionGate,
            Map<String, Object> mutationWriteHelperSafetyGate
    ) {
        boolean handoffReady = "READY_HANDOFF_DISABLED".equals(mutationHandoffSummary.get("status"))
                && Boolean.TRUE.equals(mutationHandoffSummary.get("prerequisitesPassed"));
        boolean executionGateReady = "REFUSED_EXECUTION_DISABLED".equals(mutationExecutionGate.get("status"))
                && Boolean.TRUE.equals(mutationExecutionGate.get("prerequisitesPassed"));
        boolean writeHelperGateReady = "REFUSED_WRITE_HELPER_DISABLED".equals(mutationWriteHelperSafetyGate.get("status"))
                && Boolean.TRUE.equals(mutationWriteHelperSafetyGate.get("prerequisitesPassed"));
        List<Map<String, Object>> readinessChecks = List.of(
                readinessCheck(
                        "mutationHandoffSummary",
                        handoffReady,
                        String.valueOf(mutationHandoffSummary.getOrDefault("status", "UNKNOWN")),
                        "The disabled handoff summary must prove dispatch, request creation, push, claim, execution, result intake, final response, and delivery receipt are modeled."
                ),
                readinessCheck(
                        "mutationExecutionGate",
                        executionGateReady,
                        String.valueOf(mutationExecutionGate.getOrDefault("status", "UNKNOWN")),
                        "The disabled execution gate must refuse Local Agent tool running while preserving expected request and result counts."
                ),
                readinessCheck(
                        "mutationWriteHelperSafetyGate",
                        writeHelperGateReady,
                        String.valueOf(mutationWriteHelperSafetyGate.getOrDefault("status", "UNKNOWN")),
                        "The disabled write-helper safety gate must refuse patch writes until containment, snapshots, hash rechecks, atomic writes, and rollback are implemented end to end."
                ),
                readinessCheck(
                        "runtimeExecutionSwitch",
                        false,
                        "DISABLED",
                        "The runtime switch for Local Agent mutation execution remains disabled."
                ),
                readinessCheck(
                        "sideEffectTransport",
                        false,
                        "DISABLED",
                        "Request creation, push, claim, tool execution, result intake, rollback restore, and final response handoff remain disabled."
                )
        );
        boolean prerequisitesPassed = handoffReady && executionGateReady && writeHelperGateReady;
        List<String> blockingKeys = new ArrayList<>(readinessChecks.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        for (String key : List.of(
                "releaseGateEnabled",
                "requestCreationEnabled",
                "pushEnabled",
                "claimEnabled",
                "executionEnabled",
                "writeHelperEnabled",
                "applyEnabled",
                "testEnabled",
                "rollbackRestoreEnabled",
                "resultIntakeEnabled",
                "mutationAllowed"
        )) {
            if (!blockingKeys.contains(key)) {
                blockingKeys.add(key);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.mutation-execution-readiness-boundary.v1");
        result.put("status", prerequisitesPassed ? "REFUSED_EXECUTION_READINESS_DISABLED" : "BLOCKED_EXECUTION_READINESS_DISABLED");
        result.put("prerequisitesPassed", prerequisitesPassed);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourceHandoffSummarySchema", mutationHandoffSummary.get("schema"));
        result.put("sourceHandoffSummaryStatus", mutationHandoffSummary.get("status"));
        result.put("sourceHandoffSummarySessionId", mutationHandoffSummary.get("sessionId"));
        result.put("sourceHandoffSummaryUserId", mutationHandoffSummary.get("userId"));
        result.put("sourceHandoffSummaryAgentId", mutationHandoffSummary.get("agentId"));
        result.put("sourceHandoffSummaryWorkspaceId", mutationHandoffSummary.get("workspaceId"));
        result.put("sourceHandoffSummaryDeliveryReceiptGateSchema", mutationHandoffSummary.get("sourceCompletionSummaryDeliveryReceiptGateSchema"));
        result.put("sourceHandoffSummaryDeliveryReceiptGateStatus", mutationHandoffSummary.get("sourceCompletionSummaryDeliveryReceiptGateStatus"));
        result.put("sourceHandoffSummaryDeliveryReceiptGateSessionId", mutationHandoffSummary.get("sourceCompletionSummaryDeliveryReceiptGateSessionId"));
        result.put("sourceHandoffSummaryDeliveryReceiptGateUserId", mutationHandoffSummary.get("sourceCompletionSummaryDeliveryReceiptGateUserId"));
        result.put("sourceHandoffSummaryDeliveryReceiptGateAgentId", mutationHandoffSummary.get("sourceCompletionSummaryDeliveryReceiptGateAgentId"));
        result.put("sourceHandoffSummaryDeliveryReceiptGateWorkspaceId", mutationHandoffSummary.get("sourceCompletionSummaryDeliveryReceiptGateWorkspaceId"));
        result.put("sourceExecutionGateSchema", mutationExecutionGate.get("schema"));
        result.put("sourceExecutionGateStatus", mutationExecutionGate.get("status"));
        result.put("sourceExecutionGateSessionId", mutationExecutionGate.get("sessionId"));
        result.put("sourceExecutionGateUserId", mutationExecutionGate.get("userId"));
        result.put("sourceExecutionGateAgentId", mutationExecutionGate.get("agentId"));
        result.put("sourceExecutionGateWorkspaceId", mutationExecutionGate.get("workspaceId"));
        result.put("sourceWriteHelperSafetyGateSchema", mutationWriteHelperSafetyGate.get("schema"));
        result.put("sourceWriteHelperSafetyGateStatus", mutationWriteHelperSafetyGate.get("status"));
        result.put("sourceWriteHelperSafetyGateSessionId", mutationWriteHelperSafetyGate.get("sessionId"));
        result.put("sourceWriteHelperSafetyGateUserId", mutationWriteHelperSafetyGate.get("userId"));
        result.put("sourceWriteHelperSafetyGateAgentId", mutationWriteHelperSafetyGate.get("agentId"));
        result.put("sourceWriteHelperSafetyGateWorkspaceId", mutationWriteHelperSafetyGate.get("workspaceId"));
        result.put("expectedRequestCount", numericValue(mutationExecutionGate.get("expectedRequestCount")));
        result.put("completedRequestCount", numericValue(mutationExecutionGate.get("completedRequestCount")));
        result.put("readinessChecks", readinessChecks);
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimEnabled", false);
        result.put("executionEnabled", false);
        result.put("toolRunnerEnabled", false);
        result.put("writeHelperEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        result.put("resultIntakeEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("finalResponseHandoffEnabled", false);
        result.put("deliveryReceiptEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("blockingKeys", blockingKeys);
        result.put("message", prerequisitesPassed
                ? "Local Agent mutation execution inputs are modeled, but runtime execution, request creation, push, claim, write helper, apply, test, rollback restore, result intake, final response handoff, delivery receipt, and mutation remain disabled."
                : "Local Agent mutation execution readiness is blocked by incomplete disabled handoff, execution, or write-helper inputs.");
        return result;
    }

    private Map<String, Object> readinessCheck(
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
        result.put("toolRunnerEnabled", false);
        result.put("writeHelperEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        result.put("resultIntakeEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("message", message);
        return result;
    }

    private int numericValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
