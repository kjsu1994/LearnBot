package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class LocalAgentFinalAnswerDeliveryGateBuilder {

    Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationFinalResponseHandoffGate
    ) {
        boolean finalResponseHandoffReady = "REFUSED_FINAL_RESPONSE_HANDOFF_DISABLED".equals(mutationFinalResponseHandoffGate.get("status"))
                && Boolean.TRUE.equals(mutationFinalResponseHandoffGate.get("prerequisitesPassed"));
        int expectedResultCount = numericValue(mutationFinalResponseHandoffGate.get("expectedResultCount"));
        int completedResultCount = numericValue(mutationFinalResponseHandoffGate.get("completedResultCount"));
        int acceptedResultCount = numericValue(mutationFinalResponseHandoffGate.get("acceptedResultCount"));
        int rejectedResultCount = numericValue(mutationFinalResponseHandoffGate.get("rejectedResultCount"));
        int intakePersistedResultCount = numericValue(mutationFinalResponseHandoffGate.get("intakePersistedResultCount"));
        List<Map<String, Object>> policyChecks = List.of(
                policyCheck(
                        "mutationFinalResponseHandoffGate",
                        finalResponseHandoffReady,
                        String.valueOf(mutationFinalResponseHandoffGate.getOrDefault("status", "UNKNOWN")),
                        "A disabled final-response handoff gate must refuse handoff before final-answer delivery can be considered."
                ),
                policyCheck(
                        "finalAnswerDeliveryPolicy",
                        false,
                        "DISABLED",
                        "Mutation final-answer delivery and delivery handoff are disabled."
                ),
                policyCheck(
                        "finalAnswerDelivery",
                        false,
                        "DISABLED",
                        "No final answer may be delivered while final-answer delivery is disabled."
                ),
                policyCheck(
                        "deliveryHandoff",
                        false,
                        "DISABLED",
                        "No delivery handoff may run while final-answer delivery is disabled."
                ),
                policyCheck(
                        "finalResponseHandoff",
                        false,
                        "DISABLED",
                        "No final response may be handed off while final-answer delivery is disabled."
                )
        );
        List<String> blockingKeys = new ArrayList<>(policyChecks.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        for (String key : List.of("finalAnswerDeliveryEnabled", "deliveryHandoffEnabled", "deliveryReceiptEnabled", "acknowledgementSaveEnabled", "finalResponseHandoffEnabled", "userVisibleCompletionEnabled", "conversationTurnSaveEnabled", "finalAnswerPersistenceEnabled", "finalAnswerCompletionEnabled", "finalAnswerGenerationEnabled", "mutationAllowed")) {
            if (!blockingKeys.contains(key)) {
                blockingKeys.add(key);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.mutation-final-answer-delivery-gate.v1");
        result.put("status", finalResponseHandoffReady ? "REFUSED_FINAL_ANSWER_DELIVERY_DISABLED" : "BLOCKED_FINAL_ANSWER_DELIVERY_DISABLED");
        result.put("finalResponseHandoffReady", finalResponseHandoffReady);
        result.put("prerequisitesPassed", finalResponseHandoffReady);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourceFinalResponseHandoffGateSchema", mutationFinalResponseHandoffGate.get("schema"));
        result.put("sourceFinalResponseHandoffGateStatus", mutationFinalResponseHandoffGate.get("status"));
        result.put("sourceFinalResponseHandoffGateSessionId", mutationFinalResponseHandoffGate.get("sessionId"));
        result.put("sourceFinalResponseHandoffGateUserId", mutationFinalResponseHandoffGate.get("userId"));
        result.put("sourceFinalResponseHandoffGateAgentId", mutationFinalResponseHandoffGate.get("agentId"));
        result.put("sourceFinalResponseHandoffGateWorkspaceId", mutationFinalResponseHandoffGate.get("workspaceId"));
        result.put("sourceFinalResponseHandoffGatePublicationGateSchema", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationGateSchema"));
        result.put("sourceFinalResponseHandoffGatePublicationGateStatus", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationGateStatus"));
        result.put("sourceFinalResponseHandoffGatePublicationGateSessionId", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationGateSessionId"));
        result.put("sourceFinalResponseHandoffGatePublicationGateUserId", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationGateUserId"));
        result.put("sourceFinalResponseHandoffGatePublicationGateAgentId", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationGateAgentId"));
        result.put("sourceFinalResponseHandoffGatePublicationGateWorkspaceId", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationGateWorkspaceId"));
        result.put("sourceFinalResponseHandoffGatePublicationBoundaryStatus", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryStatus"));
        result.put("sourceFinalResponseHandoffGatePublicationBoundaryPrerequisitesPassed", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryPrerequisitesPassed"));
        result.put("sourceFinalResponseHandoffGatePublicationBoundaryDraftStatus", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryDraftStatus"));
        result.put("sourceFinalResponseHandoffGatePublicationBoundaryDraftSections", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryDraftSections"));
        result.put("sourceFinalResponseHandoffGateAcceptedObservationSummaryStatus", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGateAcceptedObservationSummaryStatus"));
        result.put("sourceFinalResponseHandoffGateAcceptedObservationCount", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGateAcceptedObservationCount"));
        result.put("sourceFinalResponseHandoffGateAcceptedObservationAcceptedCount", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGateAcceptedObservationAcceptedCount"));
        result.put("sourceFinalResponseHandoffGateAcceptedObservationRejectedCount", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGateAcceptedObservationRejectedCount"));
        result.put("sourceFinalResponseHandoffGateMissingMutationResultRiskVisible", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGateMissingMutationResultRiskVisible"));
        result.put("sourceFinalResponseHandoffGateStaleIndexRiskVisible", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGateStaleIndexRiskVisible"));
        result.put("sourceFinalResponseHandoffGatePublicationAcceptedObservationSummaryStatus", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationAcceptedObservationSummaryStatus"));
        result.put("sourceFinalResponseHandoffGatePublicationAcceptedObservationCount", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationAcceptedObservationCount"));
        result.put("sourceFinalResponseHandoffGatePublicationAcceptedObservationAcceptedCount", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationAcceptedObservationAcceptedCount"));
        result.put("sourceFinalResponseHandoffGatePublicationAcceptedObservationRejectedCount", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationAcceptedObservationRejectedCount"));
        result.put("sourceFinalResponseHandoffGatePublicationMissingMutationResultRiskVisible", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationMissingMutationResultRiskVisible"));
        result.put("sourceFinalResponseHandoffGatePublicationStaleIndexRiskVisible", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationStaleIndexRiskVisible"));
        result.put("sourceFinalResponseHandoffGatePublicationLatestAcceptedObservationStatus", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationLatestAcceptedObservationStatus"));
        result.put("sourceFinalResponseHandoffGatePublicationLatestAcceptedObservationToolName", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationLatestAcceptedObservationToolName"));
        result.put("sourceFinalResponseHandoffGatePublicationLatestAcceptedObservationVerificationStatus", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationLatestAcceptedObservationVerificationStatus"));
        result.put("sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryStatus", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryStatus"));
        result.put("sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryObservationCount", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryObservationCount"));
        result.put("sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryAcceptedCount", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryAcceptedCount"));
        result.put("sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryRejectedCount", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryRejectedCount"));
        result.put("sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible"));
        result.put("sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible", mutationFinalResponseHandoffGate.get("sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible"));
        result.put("finalAnswerDeliveryPolicy", "DISABLED_AUDIT_ONLY");
        result.put("finalAnswerDeliveryEnabled", false);
        result.put("deliveryHandoffEnabled", false);
        result.put("deliveryReceiptEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("finalResponseHandoffEnabled", false);
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
        result.put("blockingKeys", blockingKeys);
        result.put("message", finalResponseHandoffReady
                ? "Local Agent mutation final-answer delivery is explicitly refused: no final answer is delivered and no delivery handoff runs."
                : "Local Agent mutation final-answer delivery is blocked because the disabled final-response handoff gate is incomplete.");
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
        result.put("deliveryReceiptEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("finalAnswerPersistenceEnabled", false);
        result.put("conversationTurnSaveEnabled", false);
        result.put("userVisibleCompletionEnabled", false);
        result.put("finalResponseHandoffEnabled", false);
        result.put("deliveryHandoffEnabled", false);
        result.put("message", message);
        return result;
    }

    private int numericValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
