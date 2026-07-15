package com.learnbot.service.coderag.answer;

import com.learnbot.dto.CodeSearchResult;
import java.util.List;

/**
 * Compatibility boundary for the existing exact-fact fidelity policy.
 *
 * <p>The policy remains unchanged while callers migrate to the answer layer.
 * New verification rules should be added behind {@link CodeAnswerVerifier}, not
 * directly to the orchestration service.</p>
 */
public final class CodeEvidenceFactFidelityBridge {
    private CodeEvidenceFactFidelityBridge() {
    }

    public static String promptFacts(String question, List<CodeSearchResult> evidence) {
        return com.learnbot.service.CodeEvidenceFactFidelityBridge.promptFacts(question, evidence);
    }

    public static String missingReason(
            String question,
            String answer,
            List<CodeSearchResult> evidence
    ) {
        return com.learnbot.service.CodeEvidenceFactFidelityBridge.missingReason(question, answer, evidence);
    }
}
