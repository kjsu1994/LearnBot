package com.learnbot.service.coderag.answer;

import com.learnbot.dto.CodeEvidence;

import java.util.List;

/**
 * Receives the externally visible events produced while a Code RAG answer is generated.
 *
 * <p>The orchestrator owns event ordering. Implementations should forward events as-is and
 * should not retain request-scoped state after the request completes.</p>
 */
public interface CodeAnswerStreamSink {
    default void onStatus(String stage, String message) {
    }

    void onEvidence(List<CodeEvidence> evidence);

    void onDelta(String text);

    void onReplace(String answer, String reason);
}
