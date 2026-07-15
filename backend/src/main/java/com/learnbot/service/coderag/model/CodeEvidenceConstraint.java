package com.learnbot.service.coderag.model;

public record CodeEvidenceConstraint(
        Type type,
        String targetId,
        String reason
) {
    public enum Type {
        NAVIGATION_ONLY,
        DIRECT_PROOF_REQUIRED,
        EXACT_FACT_REQUIRED
    }

    public CodeEvidenceConstraint {
        if (type == null) throw new IllegalArgumentException("type must not be null");
        targetId = targetId == null ? "" : targetId.trim();
        reason = reason == null ? "" : reason.trim();
        if (targetId.isBlank()) throw new IllegalArgumentException("targetId must not be blank");
    }
}
