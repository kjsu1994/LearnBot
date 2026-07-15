package com.learnbot.service.coderag.answer;

/**
 * Result of validating one generated Code RAG answer.
 */
public record CodeAnswerVerification(
        Disposition disposition,
        FailureKind failureKind,
        String reason,
        CitationQuality citationQuality
) {
    public CodeAnswerVerification {
        disposition = disposition == null ? Disposition.BLOCK : disposition;
        failureKind = failureKind == null ? FailureKind.VERIFIER_ERROR : failureKind;
        reason = reason == null ? "" : reason.trim();
        citationQuality = citationQuality == null ? CitationQuality.empty() : citationQuality;
    }

    public boolean accepted() {
        return disposition == Disposition.ACCEPT;
    }

    public enum Disposition {
        ACCEPT,
        RETRY,
        FALLBACK,
        BLOCK
    }

    public enum FailureKind {
        NONE,
        GENERATION_BLOCKED,
        BLANK,
        TOO_SHORT,
        MISSING_CITATION,
        INVALID_CITATION,
        OUTPUT_INCOMPLETE,
        BASE_QUALITY,
        EXACT_FACT,
        VERIFIER_ERROR
    }

    /**
     * Citation diagnostics retain the current metric semantics: coverage and weak lexical
     * support are observable signals, while the verifier separately decides whether a defect
     * is blocking.
     */
    public record CitationQuality(
            int referencedCount,
            int invalidCount,
            int coveragePercent,
            int weakSupportCount,
            String summary
    ) {
        public CitationQuality {
            referencedCount = Math.max(0, referencedCount);
            invalidCount = Math.max(0, invalidCount);
            coveragePercent = Math.max(0, Math.min(100, coveragePercent));
            weakSupportCount = Math.max(0, weakSupportCount);
            summary = summary == null ? "" : summary.trim();
        }

        public static CitationQuality empty() {
            return new CitationQuality(0, 0, 0, 0, "");
        }
    }
}
