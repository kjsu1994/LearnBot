package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class LocalAgentMutationToolRunnerBoundaryBuilder {

    Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationExecutionReadinessBoundary,
            Map<String, Object> mutationExecutionGate
    ) {
        boolean executionReadinessReady = "REFUSED_EXECUTION_READINESS_DISABLED"
                .equals(mutationExecutionReadinessBoundary.get("status"))
                && Boolean.TRUE.equals(mutationExecutionReadinessBoundary.get("prerequisitesPassed"));
        boolean executionGateReady = "REFUSED_EXECUTION_DISABLED".equals(mutationExecutionGate.get("status"))
                && Boolean.TRUE.equals(mutationExecutionGate.get("prerequisitesPassed"));
        int expectedRequestCount = numericValue(mutationExecutionGate.get("expectedRequestCount"));
        int runningRequestCount = numericValue(mutationExecutionGate.get("runningRequestCount"));
        int completedRequestCount = numericValue(mutationExecutionGate.get("completedRequestCount"));

        List<Map<String, Object>> runnerChecks = List.of(
                runnerCheck(
                        "mutationExecutionReadinessBoundary",
                        executionReadinessReady,
                        String.valueOf(mutationExecutionReadinessBoundary.getOrDefault("status", "UNKNOWN")),
                        "Disabled execution readiness must be modeled before a future tool runner can be considered."
                ),
                runnerCheck(
                        "mutationExecutionGate",
                        executionGateReady,
                        String.valueOf(mutationExecutionGate.getOrDefault("status", "UNKNOWN")),
                        "The disabled execution gate must refuse tool-runner invocation before runtime dispatch can be considered."
                ),
                runnerCheck(
                        "toolRunnerPolicy",
                        false,
                        "DISABLED",
                        "The Local Agent mutation tool runner is not wired to execute patch.apply, command.runAllowed, git.status, or rollback.restore."
                ),
                runnerCheck(
                        "requestRunningTransition",
                        false,
                        "DISABLED",
                        "No mutation request can transition to running while the runner boundary is disabled."
                ),
                runnerCheck(
                        "resultCompletionTransition",
                        false,
                        "DISABLED",
                        "No mutation request can transition to completed result intake while the runner boundary is disabled."
                )
        );
        boolean prerequisitesPassed = executionReadinessReady && executionGateReady;
        List<String> blockingKeys = new ArrayList<>(runnerChecks.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        for (String key : List.of(
                "requestCreationEnabled",
                "pushEnabled",
                "claimEnabled",
                "runningTransitionEnabled",
                "toolRunnerEnabled",
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
        result.put("schema", "learnbot.local-agent.mutation-tool-runner-boundary.v1");
        result.put("status", prerequisitesPassed ? "REFUSED_TOOL_RUNNER_DISABLED" : "BLOCKED_TOOL_RUNNER_DISABLED");
        result.put("prerequisitesPassed", prerequisitesPassed);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourceExecutionReadinessBoundarySchema", mutationExecutionReadinessBoundary.get("schema"));
        result.put("sourceExecutionReadinessBoundaryStatus", mutationExecutionReadinessBoundary.get("status"));
        result.put("sourceExecutionReadinessBoundarySessionId", mutationExecutionReadinessBoundary.get("sessionId"));
        result.put("sourceExecutionReadinessBoundaryUserId", mutationExecutionReadinessBoundary.get("userId"));
        result.put("sourceExecutionReadinessBoundaryAgentId", mutationExecutionReadinessBoundary.get("agentId"));
        result.put("sourceExecutionReadinessBoundaryWorkspaceId", mutationExecutionReadinessBoundary.get("workspaceId"));
        result.put("sourceExecutionReadinessBoundaryDeliveryReceiptGateSchema", mutationExecutionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGateSchema"));
        result.put("sourceExecutionReadinessBoundaryDeliveryReceiptGateStatus", mutationExecutionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGateStatus"));
        result.put("sourceExecutionReadinessBoundaryDeliveryReceiptGateSessionId", mutationExecutionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGateSessionId"));
        result.put("sourceExecutionReadinessBoundaryDeliveryReceiptGateUserId", mutationExecutionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGateUserId"));
        result.put("sourceExecutionReadinessBoundaryDeliveryReceiptGateAgentId", mutationExecutionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGateAgentId"));
        result.put("sourceExecutionReadinessBoundaryDeliveryReceiptGateWorkspaceId", mutationExecutionReadinessBoundary.get("sourceHandoffSummaryDeliveryReceiptGateWorkspaceId"));
        result.put("sourceExecutionGateSchema", mutationExecutionGate.get("schema"));
        result.put("sourceExecutionGateStatus", mutationExecutionGate.get("status"));
        result.put("sourceExecutionGateSessionId", mutationExecutionGate.get("sessionId"));
        result.put("sourceExecutionGateUserId", mutationExecutionGate.get("userId"));
        result.put("sourceExecutionGateAgentId", mutationExecutionGate.get("agentId"));
        result.put("sourceExecutionGateWorkspaceId", mutationExecutionGate.get("workspaceId"));
        result.put("toolRunnerPolicy", "DISABLED_AUDIT_ONLY");
        result.put("expectedRequestCount", expectedRequestCount);
        result.put("runningRequestCount", runningRequestCount);
        result.put("completedRequestCount", completedRequestCount);
        result.put("runnerChecks", runnerChecks);
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimEnabled", false);
        result.put("claimable", false);
        result.put("runningTransitionEnabled", false);
        result.put("executionEnabled", false);
        result.put("toolRunnerEnabled", false);
        result.put("writeHelperEnabled", false);
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
                ? "Local Agent mutation tool-runner inputs are modeled, but runner invocation, running transition, result completion, write helper, apply, test, rollback restore, result intake, and mutation remain disabled."
                : "Local Agent mutation tool-runner boundary is blocked by incomplete disabled execution readiness or execution gate inputs.");
        return result;
    }

    private Map<String, Object> runnerCheck(
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
        result.put("runningTransitionEnabled", false);
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
