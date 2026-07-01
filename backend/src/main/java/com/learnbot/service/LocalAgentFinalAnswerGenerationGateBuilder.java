package com.learnbot.service;

import com.learnbot.dto.AgentExecutionTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class LocalAgentFinalAnswerGenerationGateBuilder {

    Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> mutationPublicationGate,
            Map<String, Object> finalAnswerPublicationBoundary
    ) {
        boolean publicationReady = "REFUSED_PUBLICATION_DISABLED".equals(mutationPublicationGate.get("status"))
                && Boolean.TRUE.equals(mutationPublicationGate.get("prerequisitesPassed"));
        boolean finalAnswerPublicationBoundaryReady = "READY_PUBLICATION_DISABLED".equals(finalAnswerPublicationBoundary.get("status"))
                && Boolean.TRUE.equals(finalAnswerPublicationBoundary.get("prerequisitesPassed"));
        int expectedResultCount = numericValue(mutationPublicationGate.get("expectedResultCount"));
        int completedResultCount = numericValue(mutationPublicationGate.get("completedResultCount"));
        int acceptedResultCount = numericValue(mutationPublicationGate.get("acceptedResultCount"));
        int rejectedResultCount = numericValue(mutationPublicationGate.get("rejectedResultCount"));
        int intakePersistedResultCount = numericValue(mutationPublicationGate.get("intakePersistedResultCount"));
        List<Map<String, Object>> policyChecks = List.of(
                policyCheck(
                        "mutationPublicationGate",
                        publicationReady,
                        String.valueOf(mutationPublicationGate.getOrDefault("status", "UNKNOWN")),
                        "A disabled publication gate must refuse publication before final-answer generation can be considered."
                ),
                policyCheck(
                        "finalAnswerPublicationBoundary",
                        finalAnswerPublicationBoundaryReady,
                        String.valueOf(finalAnswerPublicationBoundary.getOrDefault("status", "UNKNOWN")),
                        "The disabled final-answer publication boundary must preserve final report draft, observation summary, and answer-quality context before final-answer generation can be considered."
                ),
                policyCheck(
                        "finalAnswerGenerationPolicy",
                        false,
                        "DISABLED",
                        "Mutation final-answer generation is disabled."
                ),
                policyCheck(
                        "finalAnswerGeneration",
                        false,
                        "DISABLED",
                        "No final answer may be generated while final-answer generation is disabled."
                )
        );
        List<String> blockingKeys = new ArrayList<>(policyChecks.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("blocking")))
                .map(item -> String.valueOf(item.get("key")))
                .toList());
        for (String key : List.of(
                "finalAnswerGenerationEnabled",
                "finalAnswerCompletionEnabled",
                "finalAnswerDeliveryEnabled",
                "finalResponseHandoffEnabled",
                "deliveryReceiptEnabled",
                "acknowledgementSaveEnabled",
                "mutationAllowed"
        )) {
            if (!blockingKeys.contains(key)) {
                blockingKeys.add(key);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.mutation-final-answer-generation-gate.v1");
        result.put("status", publicationReady ? "REFUSED_FINAL_ANSWER_GENERATION_DISABLED" : "BLOCKED_FINAL_ANSWER_GENERATION_DISABLED");
        result.put("publicationReady", publicationReady);
        result.put("prerequisitesPassed", publicationReady);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourcePublicationGateSchema", mutationPublicationGate.get("schema"));
        result.put("sourcePublicationGateStatus", mutationPublicationGate.get("status"));
        result.put("sourcePublicationGateSessionId", mutationPublicationGate.get("sessionId"));
        result.put("sourcePublicationGateUserId", mutationPublicationGate.get("userId"));
        result.put("sourcePublicationGateAgentId", mutationPublicationGate.get("agentId"));
        result.put("sourcePublicationGateWorkspaceId", mutationPublicationGate.get("workspaceId"));
        result.put("sourcePublicationGateAcceptedObservationSummaryStatus", mutationPublicationGate.get("sourceResultAggregationGateAcceptedObservationSummaryStatus"));
        result.put("sourcePublicationGateAcceptedObservationCount", mutationPublicationGate.get("sourceResultAggregationGateAcceptedObservationCount"));
        result.put("sourcePublicationGateAcceptedObservationAcceptedCount", mutationPublicationGate.get("sourceResultAggregationGateAcceptedObservationAcceptedCount"));
        result.put("sourcePublicationGateAcceptedObservationRejectedCount", mutationPublicationGate.get("sourceResultAggregationGateAcceptedObservationRejectedCount"));
        result.put("sourcePublicationGateMissingMutationResultRiskVisible", mutationPublicationGate.get("sourceResultAggregationGateMissingMutationResultRiskVisible"));
        result.put("sourcePublicationGateStaleIndexRiskVisible", mutationPublicationGate.get("sourceResultAggregationGateStaleIndexRiskVisible"));
        result.put("sourcePublicationGateLatestAcceptedObservationStatus", mutationPublicationGate.get("sourceResultAggregationGateLatestAcceptedObservationStatus"));
        result.put("sourcePublicationGateLatestAcceptedObservationToolName", mutationPublicationGate.get("sourceResultAggregationGateLatestAcceptedObservationToolName"));
        result.put("sourcePublicationGateLatestAcceptedObservationVerificationStatus", mutationPublicationGate.get("sourceResultAggregationGateLatestAcceptedObservationVerificationStatus"));
        result.put("sourcePublicationGateRollbackAcceptedObservationSummaryStatus", mutationPublicationGate.get("sourceResultAggregationGateRollbackAcceptedObservationSummaryStatus"));
        result.put("sourcePublicationGateRollbackAcceptedObservationSummaryObservationCount", mutationPublicationGate.get("sourceResultAggregationGateRollbackAcceptedObservationSummaryObservationCount"));
        result.put("sourcePublicationGateRollbackAcceptedObservationSummaryAcceptedCount", mutationPublicationGate.get("sourceResultAggregationGateRollbackAcceptedObservationSummaryAcceptedCount"));
        result.put("sourcePublicationGateRollbackAcceptedObservationSummaryRejectedCount", mutationPublicationGate.get("sourceResultAggregationGateRollbackAcceptedObservationSummaryRejectedCount"));
        result.put("sourcePublicationGateRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible", mutationPublicationGate.get("sourceResultAggregationGateRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible"));
        result.put("sourcePublicationGateRollbackAcceptedObservationSummaryStaleIndexRiskVisible", mutationPublicationGate.get("sourceResultAggregationGateRollbackAcceptedObservationSummaryStaleIndexRiskVisible"));
        result.put("sourceFinalAnswerPublicationBoundarySchema", finalAnswerPublicationBoundary.get("schema"));
        result.put("sourceFinalAnswerPublicationBoundaryStatus", finalAnswerPublicationBoundary.get("status"));
        result.put("sourceFinalAnswerPublicationBoundaryPrerequisitesPassed", finalAnswerPublicationBoundary.get("prerequisitesPassed"));
        result.put("sourceFinalAnswerPublicationBoundaryDraftStatus", finalAnswerPublicationBoundary.get("finalMutationReportDraftStatus"));
        result.put("sourceFinalAnswerPublicationBoundaryDraftSections", finalAnswerPublicationBoundary.get("finalMutationReportDraftSections"));
        result.put("sourceFinalAnswerPublicationBoundaryAcceptedObservationSummarySchema", finalAnswerPublicationBoundary.get("acceptedMutationObservationSummarySchema"));
        result.put("sourceFinalAnswerPublicationBoundaryAcceptedObservationSummaryStatus", finalAnswerPublicationBoundary.get("acceptedMutationObservationSummaryStatus"));
        result.put("sourceFinalAnswerPublicationBoundaryAcceptedObservationCount", finalAnswerPublicationBoundary.get("acceptedMutationObservationCount"));
        result.put("sourceFinalAnswerPublicationBoundaryAcceptedObservationAcceptedCount", finalAnswerPublicationBoundary.get("acceptedMutationObservationAcceptedCount"));
        result.put("sourceFinalAnswerPublicationBoundaryAcceptedObservationRejectedCount", finalAnswerPublicationBoundary.get("acceptedMutationObservationRejectedCount"));
        result.put("sourceFinalAnswerPublicationBoundaryMissingMutationResultRiskVisible", finalAnswerPublicationBoundary.get("missingMutationResultRiskVisible"));
        result.put("sourceFinalAnswerPublicationBoundaryStaleIndexRiskVisible", finalAnswerPublicationBoundary.get("staleIndexRiskVisible"));
        result.put("finalAnswerGenerationPolicy", "DISABLED_AUDIT_ONLY");
        result.put("finalAnswerGenerationInvocationEnabled", false);
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
        result.put("finalAnswerDeliveryEnabled", false);
        result.put("finalResponseHandoffEnabled", false);
        result.put("deliveryReceiptEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("blockingKeys", blockingKeys);
        result.put("message", publicationReady
                ? "Local Agent mutation final-answer generation is explicitly refused: no final answer is generated."
                : "Local Agent mutation final-answer generation is blocked because the disabled publication gate is incomplete.");
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
        result.put("finalResponseHandoffEnabled", false);
        result.put("deliveryReceiptEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("message", message);
        return result;
    }

    private int numericValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
