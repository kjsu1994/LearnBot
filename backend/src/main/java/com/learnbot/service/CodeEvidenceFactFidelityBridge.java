package com.learnbot.service;

import com.learnbot.dto.CodeSearchResult;

import java.util.List;

/**
 * Temporary public boundary around the legacy query-time fact-fidelity rules.
 *
 * <p>New answer-layer code depends on this bridge instead of making the legacy rule holder
 * public. The bridge can be replaced by the typed evidence IR without changing the answer
 * verifier contract.</p>
 */
public final class CodeEvidenceFactFidelityBridge {
    private CodeEvidenceFactFidelityBridge() {
    }

    public static String promptFacts(String question, List<CodeSearchResult> evidence) {
        return CodeEvidenceFactFidelity.promptFacts(question, evidence);
    }

    public static String missingReason(String question, String answer, List<CodeSearchResult> evidence) {
        return CodeEvidenceFactFidelity.missingReason(question, answer, evidence);
    }
}
