package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class LocalAgentFinalResponseHandoffGateBuilder {

    Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationFinalAnswerUserVisibleCompletionGate
    ) {
        boolean userVisibleCompletionReady = "REFUSED_FINAL_ANSWER_USER_VISIBLE_COMPLETION_DISABLED".equals(mutationFinalAnswerUserVisibleCompletionGate.get("status"))
                && Boolean.TRUE.equals(mutationFinalAnswerUserVisibleCompletionGate.get("prerequisitesPassed"));
        int expectedResultCount = numericValue(mutationFinalAnswerUserVisibleCompletionGate.get("expectedResultCount"));
        int completedResultCount = numericValue(mutationFinalAnswerUserVisibleCompletionGate.get("completedResultCount"));
        int acceptedResultCount = numericValue(mutationFinalAnswerUserVisibleCompletionGate.get("acceptedResultCount"));
        int rejectedResultCount = numericValue(mutationFinalAnswerUserVisibleCompletionGate.get("rejectedResultCount"));
        int intakePersistedResultCount = numericValue(mutationFinalAnswerUserVisibleCompletionGate.get("intakePersistedResultCount"));
        List<Map<String, Object>> policyChecks = List.of(
                policyCheck(
                        "mutationFinalAnswerUserVisibleCompletionGate",
                        userVisibleCompletionReady,
                        String.valueOf(mutationFinalAnswerUserVisibleCompletionGate.getOrDefault("status", "UNKNOWN")),
                        "A disabled final-answer user-visible completion gate must refuse user-visible completion before final-response handoff can be considered."
                ),
                policyCheck(
                        "finalResponseHandoffPolicy",
                        false,
                        "DISABLED",
                        "Mutation final-response handoff and delivery handoff are disabled."
                ),
                policyCheck(
                        "finalResponseHandoff",
                        false,
                        "DISABLED",
                        "No final response may be handed off while final-response handoff is disabled."
                ),
                policyCheck(
                        "finalAnswerDelivery",
                        false,
                        "DISABLED",
                        "No final answer may be delivered while final-response handoff is disabled."
                ),
                policyCheck(
                        "userVisibleCompletion",
                        false,
                        "DISABLED",
                        "No user-visible completion may be marked while final-response handoff is disabled."
                )
        );
        List<String> blockingKeys = new ArrayList<>(policyChecks.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        for (String key : List.of(
                "finalResponseHandoffEnabled",
                "deliveryHandoffEnabled",
                "deliveryReceiptEnabled",
                "acknowledgementSaveEnabled",
                "finalAnswerDeliveryEnabled",
                "userVisibleCompletionEnabled",
                "conversationTurnSaveEnabled",
                "finalAnswerPersistenceEnabled",
                "finalAnswerCompletionEnabled",
                "finalAnswerGenerationEnabled",
                "mutationAllowed"
        )) {
            if (!blockingKeys.contains(key)) {
                blockingKeys.add(key);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.mutation-final-response-handoff-gate.v1");
        result.put("status", userVisibleCompletionReady ? "REFUSED_FINAL_RESPONSE_HANDOFF_DISABLED" : "BLOCKED_FINAL_RESPONSE_HANDOFF_DISABLED");
        result.put("userVisibleCompletionReady", userVisibleCompletionReady);
        result.put("prerequisitesPassed", userVisibleCompletionReady);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourceFinalAnswerUserVisibleCompletionGateSchema", mutationFinalAnswerUserVisibleCompletionGate.get("schema"));
        result.put("sourceFinalAnswerUserVisibleCompletionGateStatus", mutationFinalAnswerUserVisibleCompletionGate.get("status"));
        result.put("sourceFinalAnswerUserVisibleCompletionGateSessionId", mutationFinalAnswerUserVisibleCompletionGate.get("sessionId"));
        result.put("sourceFinalAnswerUserVisibleCompletionGateUserId", mutationFinalAnswerUserVisibleCompletionGate.get("userId"));
        result.put("sourceFinalAnswerUserVisibleCompletionGateAgentId", mutationFinalAnswerUserVisibleCompletionGate.get("agentId"));
        result.put("sourceFinalAnswerUserVisibleCompletionGateWorkspaceId", mutationFinalAnswerUserVisibleCompletionGate.get("workspaceId"));
        result.put("sourceFinalAnswerUserVisibleCompletionGatePublicationGateSchema", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationGateSchema"));
        result.put("sourceFinalAnswerUserVisibleCompletionGatePublicationGateStatus", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationGateStatus"));
        result.put("sourceFinalAnswerUserVisibleCompletionGatePublicationGateSessionId", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationGateSessionId"));
        result.put("sourceFinalAnswerUserVisibleCompletionGatePublicationGateUserId", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationGateUserId"));
        result.put("sourceFinalAnswerUserVisibleCompletionGatePublicationGateAgentId", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationGateAgentId"));
        result.put("sourceFinalAnswerUserVisibleCompletionGatePublicationGateWorkspaceId", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationGateWorkspaceId"));
        result.put("sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryStatus", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationBoundaryStatus"));
        result.put("sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryPrerequisitesPassed", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationBoundaryPrerequisitesPassed"));
        result.put("sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryDraftStatus", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationBoundaryDraftStatus"));
        result.put("sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryDraftSections", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationBoundaryDraftSections"));
        result.put("sourceFinalAnswerUserVisibleCompletionGateAcceptedObservationSummaryStatus", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGateAcceptedObservationSummaryStatus"));
        result.put("sourceFinalAnswerUserVisibleCompletionGateAcceptedObservationCount", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGateAcceptedObservationCount"));
        result.put("sourceFinalAnswerUserVisibleCompletionGateAcceptedObservationAcceptedCount", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGateAcceptedObservationAcceptedCount"));
        result.put("sourceFinalAnswerUserVisibleCompletionGateAcceptedObservationRejectedCount", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGateAcceptedObservationRejectedCount"));
        result.put("sourceFinalAnswerUserVisibleCompletionGateMissingMutationResultRiskVisible", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGateMissingMutationResultRiskVisible"));
        result.put("sourceFinalAnswerUserVisibleCompletionGateStaleIndexRiskVisible", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGateStaleIndexRiskVisible"));
        result.put("sourceFinalAnswerUserVisibleCompletionGatePublicationAcceptedObservationSummaryStatus", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationAcceptedObservationSummaryStatus"));
        result.put("sourceFinalAnswerUserVisibleCompletionGatePublicationAcceptedObservationCount", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationAcceptedObservationCount"));
        result.put("sourceFinalAnswerUserVisibleCompletionGatePublicationAcceptedObservationAcceptedCount", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationAcceptedObservationAcceptedCount"));
        result.put("sourceFinalAnswerUserVisibleCompletionGatePublicationAcceptedObservationRejectedCount", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationAcceptedObservationRejectedCount"));
        result.put("sourceFinalAnswerUserVisibleCompletionGatePublicationMissingMutationResultRiskVisible", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationMissingMutationResultRiskVisible"));
        result.put("sourceFinalAnswerUserVisibleCompletionGatePublicationStaleIndexRiskVisible", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationStaleIndexRiskVisible"));
        result.put("sourceFinalAnswerUserVisibleCompletionGatePublicationLatestAcceptedObservationStatus", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationLatestAcceptedObservationStatus"));
        result.put("sourceFinalAnswerUserVisibleCompletionGatePublicationLatestAcceptedObservationToolName", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationLatestAcceptedObservationToolName"));
        result.put("sourceFinalAnswerUserVisibleCompletionGatePublicationLatestAcceptedObservationVerificationStatus", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationLatestAcceptedObservationVerificationStatus"));
        result.put("sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryStatus", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryStatus"));
        result.put("sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryObservationCount", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryObservationCount"));
        result.put("sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryAcceptedCount", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryAcceptedCount"));
        result.put("sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryRejectedCount", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryRejectedCount"));
        result.put("sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible"));
        result.put("sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible", mutationFinalAnswerUserVisibleCompletionGate.get("sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible"));
        result.put("finalResponseHandoffPolicy", "DISABLED_AUDIT_ONLY");
        result.put("finalResponseHandoffEnabled", false);
        result.put("deliveryHandoffEnabled", false);
        result.put("deliveryReceiptEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("finalAnswerDeliveryEnabled", false);
        result.put("userVisibleCompletionEnabled", false);
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
        result.put("finalAnswerPersistenceEnabled", false);
        result.put("conversationTurnSaveEnabled", false);
        result.put("deliveryReceiptEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("blockingKeys", blockingKeys);
        result.put("message", userVisibleCompletionReady
                ? "Local Agent mutation final-response handoff is explicitly refused: no final response is handed off and no final answer is delivered."
                : "Local Agent mutation final-response handoff is blocked because the disabled final-answer user-visible completion gate is incomplete.");
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
        result.put("userVisibleCompletionEnabled", false);
        result.put("finalResponseHandoffEnabled", false);
        result.put("deliveryHandoffEnabled", false);
        result.put("deliveryReceiptEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("message", message);
        return result;
    }

    private int numericValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
