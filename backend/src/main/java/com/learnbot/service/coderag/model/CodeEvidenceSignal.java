package com.learnbot.service.coderag.model;

public record CodeEvidenceSignal(
        Type type,
        String sourceEvidenceId,
        double strength,
        String reason
) {
    public enum Type {
        DIRECT_OBSERVATION,
        ENDPOINT_STRUCTURE,
        EXACT_LITERAL,
        STATE_TRANSITION,
        TRANSACTION_BOUNDARY,
        OBSERVED_NAVIGATION,
        PERSISTENCE_RELATION,
        CLAIM_LINKED_SEARCH_HEAD,
        QUESTION_CALLABLE_BODY,
        SOURCE_BUNDLE_MEMBER,
        SOURCE_BUNDLE_BOUNDARY
    }

    public CodeEvidenceSignal {
        if (type == null) throw new IllegalArgumentException("type must not be null");
        sourceEvidenceId = sourceEvidenceId == null ? "" : sourceEvidenceId.trim();
        if (sourceEvidenceId.isBlank()) {
            throw new IllegalArgumentException("sourceEvidenceId must not be blank");
        }
        strength = Math.max(0.0, Math.min(1.0, strength));
        reason = reason == null ? "" : reason.trim();
    }
}
