package com.learnbot.service.localagent;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.service.LocalAgentPatchReleaseAttempt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class LocalAgentFinalMutationReportDraftBuilder {
    private LocalAgentFinalMutationReportDraftBuilder() {
    }

    public static Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> aggregationPlan,
            Map<String, Object> finalMutationReportContract,
            Map<String, Object> acceptedMutationObservationSummary
    ) {
        List<Map<String, Object>> sections = finalMutationReportContractRequiredSections(finalMutationReportContract);
        Map<String, Map<String, Object>> stepsByTarget = aggregationPlanSteps(aggregationPlan).stream()
                .collect(Collectors.toMap(
                        item -> String.valueOf(item.getOrDefault("targetSectionKey", "")),
                        item -> item,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        boolean prerequisitesPassed = "READY_AGGREGATION_DISABLED".equals(aggregationPlan.get("status"))
                && "CONTRACT_DISABLED".equals(finalMutationReportContract.get("status"))
                && sections.size() == 7;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.final-mutation-report-draft.v1");
        result.put("status", prerequisitesPassed ? "READY_DRAFT_DISABLED" : "BLOCKED_DRAFT_DISABLED");
        result.put("prerequisitesPassed", prerequisitesPassed);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("aggregationPlanSchema", aggregationPlan.get("schema"));
        result.put("aggregationPlanStatus", aggregationPlan.get("status"));
        result.put("finalMutationReportSchema", finalMutationReportContract.get("schema"));
        result.put("finalMutationReportStatus", finalMutationReportContract.get("status"));
        copyAcceptedObservationSummary(result, acceptedMutationObservationSummary);
        result.put("missingMutationResultRiskVisible", numericValue(acceptedMutationObservationSummary.get("observationCount")) == 0);
        result.put("staleIndexRiskVisible", numericValue(acceptedMutationObservationSummary.get("acceptedCount")) > 0);
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimEnabled", false);
        result.put("writeHelperEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("applyEnabled", false);
        result.put("testEnabled", false);
        result.put("rollbackRestoreEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("acceptedObservationAggregationEnabled", false);
        result.put("finalMutationReportDraftEnabled", false);
        result.put("finalReportGenerationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("sections", sections.stream()
                .map(section -> draftSection(section, stepsByTarget.get(String.valueOf(section.get("key")))))
                .toList());
        result.put("blockingKeys", prerequisitesPassed
                ? List.of("mutationResultAggregationEnabled", "finalReportGenerationEnabled", "publicationEnabled")
                : List.of("aggregationPlan", "finalMutationReportContract"));
        result.put("message", prerequisitesPassed
                ? "Future final mutation report draft is modeled from the aggregation plan, but aggregation, final report generation, publication, and final-answer generation remain disabled."
                : "Future final mutation report draft prerequisites are incomplete, and final report generation remains disabled.");
        return result;
    }

    private static Map<String, Object> draftSection(Map<String, Object> section, Map<String, Object> step) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", section.get("key"));
        result.put("status", "PENDING_RESULT_DISABLED");
        result.put("required", section.get("required"));
        result.put("resultRequired", section.get("resultRequired"));
        result.put("sourceOutcomeKey", section.get("sourceOutcomeKey"));
        result.put("aggregationStepStatus", step == null ? "MISSING_STEP_DISABLED" : step.getOrDefault("status", "PLANNED_DISABLED"));
        result.put("aggregationSourceOutcomeKey", step == null ? null : step.get("sourceOutcomeKey"));
        result.put("sourceOutcomeModeled", step != null || section.get("sourceOutcomeKey") == null);
        result.put("message", section.get("message"));
        result.put("releaseGateEnabled", false);
        result.put("requestCreationEnabled", false);
        result.put("pushEnabled", false);
        result.put("claimable", false);
        result.put("mutationAllowed", false);
        result.put("mutationResultAggregationEnabled", false);
        result.put("finalReportGenerationEnabled", false);
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        return result;
    }

    private static void copyAcceptedObservationSummary(
            Map<String, Object> target,
            Map<String, Object> acceptedMutationObservationSummary
    ) {
        target.put("acceptedMutationObservationSummarySchema", acceptedMutationObservationSummary.get("schema"));
        target.put("acceptedMutationObservationSummaryStatus", acceptedMutationObservationSummary.get("status"));
        target.put("acceptedMutationObservationCount", acceptedMutationObservationSummary.get("observationCount"));
        target.put("acceptedMutationObservationAcceptedCount", acceptedMutationObservationSummary.get("acceptedCount"));
        target.put("acceptedMutationObservationRejectedCount", acceptedMutationObservationSummary.get("rejectedCount"));
        target.put("acceptedMutationObservationTerminalFailureAcceptedCount", acceptedMutationObservationSummary.get("terminalFailureAcceptedCount"));
        target.put("acceptedMutationObservationToolCounts", acceptedMutationObservationSummary.get("toolObservationCounts"));
        target.put("acceptedMutationObservationStatusCounts", acceptedMutationObservationSummary.get("statusObservationCounts"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> aggregationPlanSteps(Map<String, Object> aggregationPlan) {
        Object steps = aggregationPlan.get("steps");
        if (steps instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .<Map<String, Object>>map(item -> new LinkedHashMap<>((Map<String, Object>) item))
                    .toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> finalMutationReportContractRequiredSections(Map<String, Object> contract) {
        Object sections = contract.get("requiredSections");
        if (sections instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .<Map<String, Object>>map(item -> new LinkedHashMap<>((Map<String, Object>) item))
                    .toList();
        }
        return List.of();
    }

    private static int numericValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
