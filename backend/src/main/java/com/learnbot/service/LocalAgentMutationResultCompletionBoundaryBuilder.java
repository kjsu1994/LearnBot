package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class LocalAgentMutationResultCompletionBoundaryBuilder {

    Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationToolRunnerBoundary,
            Map<String, Object> mutationPostExecutionObservationGate
    ) {
        boolean runnerBoundaryReady = "REFUSED_TOOL_RUNNER_DISABLED".equals(mutationToolRunnerBoundary.get("status"))
                && Boolean.TRUE.equals(mutationToolRunnerBoundary.get("prerequisitesPassed"));
        boolean observationGateReady = "REFUSED_POST_EXECUTION_OBSERVATION_DISABLED"
                .equals(mutationPostExecutionObservationGate.get("status"))
                && Boolean.TRUE.equals(mutationPostExecutionObservationGate.get("prerequisitesPassed"));
        int expectedResultCount = numericValue(mutationPostExecutionObservationGate.get("expectedResultCount"));
        int completedResultCount = numericValue(mutationPostExecutionObservationGate.get("completedResultCount"));
        int acceptedResultCount = numericValue(mutationPostExecutionObservationGate.get("acceptedResultCount"));
        int rejectedResultCount = numericValue(mutationPostExecutionObservationGate.get("rejectedResultCount"));

        List<Map<String, Object>> resultChecks = List.of(
                resultCheck(
                        "mutationToolRunnerBoundary",
                        runnerBoundaryReady,
                        String.valueOf(mutationToolRunnerBoundary.getOrDefault("status", "UNKNOWN")),
                        "The disabled tool-runner boundary must refuse runner invocation and completed transitions before result completion can be considered."
                ),
                resultCheck(
                        "mutationPostExecutionObservationGate",
                        observationGateReady,
                        String.valueOf(mutationPostExecutionObservationGate.getOrDefault("status", "UNKNOWN")),
                        "The disabled post-execution observation gate must refuse completed-result capture before result completion can be considered."
                ),
                resultCheck(
                        "completedResultTransition",
                        false,
                        "DISABLED",
                        "No Local Agent mutation result can transition to completed while result completion is disabled."
                ),
                resultCheck(
                        "resultEnvelopePersistence",
                        false,
                        "DISABLED",
                        "No completed mutation result envelope can be persisted while result completion is disabled."
                ),
                resultCheck(
                        "observationCapture",
                        false,
                        "DISABLED",
                        "No completed mutation observation can be captured while result completion is disabled."
                )
        );
        boolean prerequisitesPassed = runnerBoundaryReady && observationGateReady;
        List<String> blockingKeys = new ArrayList<>(resultChecks.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        for (String key : List.of(
                "toolRunnerEnabled",
                "completedResultTransitionEnabled",
                "completedResultPersistenceEnabled",
                "postExecutionObservationEnabled",
                "resultIntakeEnabled",
                "mutationAllowed"
        )) {
            if (!blockingKeys.contains(key)) {
                blockingKeys.add(key);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.mutation-result-completion-boundary.v1");
        result.put("status", prerequisitesPassed ? "REFUSED_RESULT_COMPLETION_DISABLED" : "BLOCKED_RESULT_COMPLETION_DISABLED");
        result.put("prerequisitesPassed", prerequisitesPassed);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourceToolRunnerBoundarySchema", mutationToolRunnerBoundary.get("schema"));
        result.put("sourceToolRunnerBoundaryStatus", mutationToolRunnerBoundary.get("status"));
        result.put("sourceToolRunnerBoundarySessionId", mutationToolRunnerBoundary.get("sessionId"));
        result.put("sourceToolRunnerBoundaryUserId", mutationToolRunnerBoundary.get("userId"));
        result.put("sourceToolRunnerBoundaryAgentId", mutationToolRunnerBoundary.get("agentId"));
        result.put("sourceToolRunnerBoundaryWorkspaceId", mutationToolRunnerBoundary.get("workspaceId"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGateSchema", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGateSchema"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGateStatus", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGateStatus"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGateSessionId", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGateSessionId"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGateUserId", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGateUserId"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGateAgentId", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGateAgentId"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGateWorkspaceId", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGateWorkspaceId"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationGateSchema", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateSchema"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationGateStatus", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateStatus"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationGateSessionId", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateSessionId"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationGateUserId", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateUserId"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationGateAgentId", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateAgentId"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationGateWorkspaceId", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateWorkspaceId"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationBoundaryStatus", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationBoundaryStatus"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationBoundaryPrerequisitesPassed", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationBoundaryPrerequisitesPassed"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationBoundaryDraftStatus", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationBoundaryDraftStatus"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationBoundaryDraftSections", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationBoundaryDraftSections"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGateAcceptedObservationSummaryStatus", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGateAcceptedObservationSummaryStatus"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGateAcceptedObservationCount", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGateAcceptedObservationCount"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGateAcceptedObservationAcceptedCount", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGateAcceptedObservationAcceptedCount"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGateAcceptedObservationRejectedCount", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGateAcceptedObservationRejectedCount"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGateMissingMutationResultRiskVisible", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGateMissingMutationResultRiskVisible"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGateStaleIndexRiskVisible", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGateStaleIndexRiskVisible"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationAcceptedObservationCount", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationAcceptedObservationCount"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationAcceptedObservationRejectedCount", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationAcceptedObservationRejectedCount"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationMissingMutationResultRiskVisible", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationMissingMutationResultRiskVisible"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationStaleIndexRiskVisible", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationStaleIndexRiskVisible"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationStatus", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationStatus"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationToolName", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationToolName"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible"));
        result.put("sourceToolRunnerBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible", mutationToolRunnerBoundary.get("sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible"));
        result.put("sourcePostExecutionObservationGateSchema", mutationPostExecutionObservationGate.get("schema"));
        result.put("sourcePostExecutionObservationGateStatus", mutationPostExecutionObservationGate.get("status"));
        result.put("sourcePostExecutionObservationGateSessionId", mutationPostExecutionObservationGate.get("sessionId"));
        result.put("sourcePostExecutionObservationGateUserId", mutationPostExecutionObservationGate.get("userId"));
        result.put("sourcePostExecutionObservationGateAgentId", mutationPostExecutionObservationGate.get("agentId"));
        result.put("sourcePostExecutionObservationGateWorkspaceId", mutationPostExecutionObservationGate.get("workspaceId"));
        result.put("completionPolicy", "DISABLED_AUDIT_ONLY");
        result.put("expectedResultCount", expectedResultCount);
        result.put("completedResultCount", completedResultCount);
        result.put("acceptedResultCount", acceptedResultCount);
        result.put("rejectedResultCount", rejectedResultCount);
        result.put("resultChecks", resultChecks);
        result.put("releaseGateEnabled", false);
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
        result.put("completedResultTransitionEnabled", false);
        result.put("completedResultPersistenceEnabled", false);
        result.put("postExecutionObservationEnabled", false);
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
                ? "Local Agent mutation result-completion inputs are modeled, but completed transition, result persistence, observation capture, result intake, and mutation remain disabled."
                : "Local Agent mutation result completion is blocked by incomplete disabled tool-runner or post-execution observation inputs.");
        return result;
    }

    private Map<String, Object> resultCheck(
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
        result.put("toolRunnerEnabled", false);
        result.put("completedResultTransitionEnabled", false);
        result.put("completedResultPersistenceEnabled", false);
        result.put("postExecutionObservationEnabled", false);
        result.put("resultIntakeEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("message", message);
        return result;
    }

    private int numericValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
