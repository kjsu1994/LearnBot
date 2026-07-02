package com.learnbot.service.localagent;

import com.learnbot.dto.AgentExecutionTarget;
import com.learnbot.service.LocalAgentPatchReleaseAttempt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LocalAgentAcknowledgementSaveHandoffBuilder {
    private LocalAgentAcknowledgementSaveHandoffBuilder() {
    }

    public static Map<String, Object> build(
            LocalAgentPatchReleaseAttempt attempt,
            Map<String, Object> finalAnswerPublicationHandoff
    ) {
        boolean publicationHandoffAvailable = Boolean.TRUE.equals(finalAnswerPublicationHandoff.get("handoffAvailable"));
        boolean staleDisclosureModeled = Boolean.TRUE.equals(finalAnswerPublicationHandoff.get("staleIndexDisclosureModeled"));
        boolean acknowledgementPrerequisitesModeled = publicationHandoffAvailable && staleDisclosureModeled;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "learnbot.local-agent.acknowledgement-save-handoff.v1");
        result.put("status", acknowledgementPrerequisitesModeled
                ? "READY_ACKNOWLEDGEMENT_AUDIT_ONLY_SAVE_DISABLED"
                : "BLOCKED_FINAL_ANSWER_HANDOFF_INCOMPLETE");
        result.put("handoffAvailable", acknowledgementPrerequisitesModeled);
        result.put("blocking", true);
        result.put("releaseAttemptId", attempt.id());
        result.put("sourceRequestId", attempt.sourceRequestId());
        result.put("sessionId", attempt.sessionId());
        result.put("userId", attempt.userId());
        result.put("agentId", attempt.agentId());
        result.put("workspaceId", attempt.workspaceId());
        result.put("executionTarget", AgentExecutionTarget.USER_LOCAL_AGENT.name());
        result.put("sourceFinalAnswerPublicationHandoffSchema", finalAnswerPublicationHandoff.get("schema"));
        result.put("sourceFinalAnswerPublicationHandoffStatus", finalAnswerPublicationHandoff.get("status"));
        result.put("finalAnswerPublicationHandoffAvailable", publicationHandoffAvailable);
        result.put("staleIndexDisclosureModeled", staleDisclosureModeled);
        result.put("staleIndexDisclosureText", finalAnswerPublicationHandoff.get("staleIndexDisclosureText"));
        result.put("targetFiles", stringList(finalAnswerPublicationHandoff.get("targetFiles")));
        result.put("finalAnswerSections", stringList(finalAnswerPublicationHandoff.get("finalAnswerSections")));
        result.put("acknowledgementReceiptRequired", true);
        result.put("acknowledgementReceiptModeled", acknowledgementPrerequisitesModeled);
        result.put("acknowledgementSaveMode", "READ_ONLY_AUDIT");
        result.put("publicationEnabled", false);
        result.put("finalAnswerGenerationEnabled", false);
        result.put("finalAnswerDeliveryEnabled", false);
        result.put("conversationSaveEnabled", false);
        result.put("acknowledgementSaveEnabled", false);
        result.put("ragFreshnessUpdateEnabled", false);
        result.put("partialReindexEnabled", false);
        result.put("mutationAllowed", false);
        result.put("blockingKeys", acknowledgementPrerequisitesModeled
                ? List.of("acknowledgementSaveEnabled", "conversationSaveEnabled", "finalAnswerDeliveryEnabled")
                : List.of("finalAnswerPublicationHandoff", "staleIndexDisclosure", "acknowledgementReceipt"));
        result.put("message", acknowledgementPrerequisitesModeled
                ? "Acknowledgement save handoff has the final-answer disclosure context, but acknowledgement save, conversation save, delivery, publication, and RAG freshness update remain disabled."
                : "Acknowledgement save handoff refuses receipt creation until final-answer publication handoff and stale-index disclosure are present.");
        return result;
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        return List.of();
    }
}
