package com.learnbot.service.coderag.answer;

import com.learnbot.dto.CodeSearchResult;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.learnbot.service.coderag.answer.CodeAnswerVerification.Disposition;
import static com.learnbot.service.coderag.answer.CodeAnswerVerification.FailureKind;

/**
 * Validates generated answers without owning generation, retry, or fallback behavior.
 *
 * <p>The built-in checks preserve the existing Code RAG minimum answer and citation rules.
 * A caller can provide the runtime pipeline quality policy, while exact-fact checking is
 * hidden behind a replaceable boundary so typed evidence can supersede the legacy rules.</p>
 */
public final class CodeAnswerVerifier {
    private static final int MINIMUM_ANSWER_CHARS = 30;
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");
    private static final Pattern CLAIM_SEPARATOR = Pattern.compile("[\\n.!?]+");
    private static final Pattern SUPPORT_TERM_PATTERN = Pattern.compile("[\\p{L}\\p{N}_-]{3,}");
    private static final Set<String> CITATION_STOP_WORDS = Set.of(
            "the", "and", "for", "that", "this", "with", "from", "into", "also", "then",
            "when", "where", "what", "how", "why", "are", "was", "were", "has", "have",
            "public", "private", "class", "void", "return", "string", "있습니다", "합니다"
    );

    private final AnswerQualityPolicy answerQualityPolicy;
    private final ExactFactVerifier exactFactVerifier;

    /**
     * Creates a strict standalone verifier backed by the current exact-fact compatibility
     * bridge. Runtime wiring that supports a configurable self-check should use
     * {@link #legacyCompatible(AnswerQualityPolicy)}.
     */
    public CodeAnswerVerifier() {
        this(CodeAnswerVerifier::strictQualityFailure, CodeEvidenceFactFidelityBridge::missingReason);
    }

    public CodeAnswerVerifier(
            AnswerQualityPolicy answerQualityPolicy,
            ExactFactVerifier exactFactVerifier
    ) {
        this.answerQualityPolicy = answerQualityPolicy == null ? AnswerQualityPolicy.accepting() : answerQualityPolicy;
        this.exactFactVerifier = exactFactVerifier == null ? ExactFactVerifier.accepting() : exactFactVerifier;
    }

    /**
     * Uses the caller's existing runtime quality policy and the legacy fact-fidelity bridge.
     */
    public static CodeAnswerVerifier legacyCompatible(AnswerQualityPolicy answerQualityPolicy) {
        return new CodeAnswerVerifier(answerQualityPolicy, CodeEvidenceFactFidelityBridge::missingReason);
    }

    public CodeAnswerVerification verify(
            String question,
            String answer,
            List<CodeSearchResult> evidence,
            String doneReason,
            boolean retryAvailable
    ) {
        return verify(question, answer, evidence, doneReason, retryAvailable, true);
    }

    public CodeAnswerVerification verify(
            String question,
            String answer,
            List<CodeSearchResult> evidence,
            String doneReason,
            boolean retryAvailable,
            boolean generationAllowed
    ) {
        List<CodeSearchResult> safeEvidence = evidence == null ? List.of() : List.copyOf(evidence);
        String safeAnswer = answer == null ? "" : answer;
        CodeAnswerVerification.CitationQuality citationQuality = citationQuality(safeAnswer, safeEvidence);

        if (!generationAllowed) {
            return failure(Disposition.BLOCK, FailureKind.GENERATION_BLOCKED,
                    "answer generation is blocked by the evidence integrity gate", citationQuality);
        }
        if (safeAnswer.isBlank()) {
            return repairable(FailureKind.BLANK, "blank", citationQuality, retryAvailable);
        }
        if (safeAnswer.trim().length() < MINIMUM_ANSWER_CHARS) {
            return repairable(FailureKind.TOO_SHORT, "too short", citationQuality, retryAvailable);
        }
        if (citationQuality.referencedCount() == 0) {
            return repairable(FailureKind.MISSING_CITATION, "missing citation", citationQuality, retryAvailable);
        }

        String baseReason;
        try {
            baseReason = normalizeReason(answerQualityPolicy.failureReason(
                    safeAnswer, safeEvidence.size(), doneReason));
        } catch (RuntimeException ex) {
            return repairable(FailureKind.VERIFIER_ERROR,
                    "answer quality verifier failed: " + ex.getClass().getSimpleName(),
                    citationQuality, retryAvailable);
        }
        if (!baseReason.isEmpty()) {
            return repairable(classifyBaseFailure(baseReason), baseReason, citationQuality, retryAvailable);
        }

        String exactFactReason;
        try {
            exactFactReason = normalizeReason(exactFactVerifier.missingReason(
                    question == null ? "" : question, safeAnswer, safeEvidence));
        } catch (RuntimeException ex) {
            return repairable(FailureKind.VERIFIER_ERROR,
                    "exact fact verifier failed: " + ex.getClass().getSimpleName(),
                    citationQuality, retryAvailable);
        }
        if (!exactFactReason.isEmpty()) {
            return repairable(FailureKind.EXACT_FACT, exactFactReason, citationQuality, retryAvailable);
        }

        return new CodeAnswerVerification(Disposition.ACCEPT, FailureKind.NONE, "", citationQuality);
    }

    private CodeAnswerVerification repairable(
            FailureKind failureKind,
            String reason,
            CodeAnswerVerification.CitationQuality citationQuality,
            boolean retryAvailable
    ) {
        return failure(retryAvailable ? Disposition.RETRY : Disposition.FALLBACK,
                failureKind, reason, citationQuality);
    }

    private CodeAnswerVerification failure(
            Disposition disposition,
            FailureKind failureKind,
            String reason,
            CodeAnswerVerification.CitationQuality citationQuality
    ) {
        return new CodeAnswerVerification(disposition, failureKind, reason, citationQuality);
    }

    private static String strictQualityFailure(String answer, int evidenceCount, String doneReason) {
        if ("length".equalsIgnoreCase(normalizeReason(doneReason))) {
            return "model stopped before finishing";
        }
        for (Integer citation : citationReferences(answer)) {
            if (citation < 1 || citation > evidenceCount) {
                return "citation out of range";
            }
        }
        return null;
    }

    private static FailureKind classifyBaseFailure(String reason) {
        String normalized = reason.toLowerCase(Locale.ROOT);
        if (normalized.contains("citation") && (normalized.contains("range") || normalized.contains("invalid"))) {
            return FailureKind.INVALID_CITATION;
        }
        if (normalized.contains("incomplete") || normalized.contains("stopped") || normalized.contains("length")) {
            return FailureKind.OUTPUT_INCOMPLETE;
        }
        return FailureKind.BASE_QUALITY;
    }

    private static CodeAnswerVerification.CitationQuality citationQuality(
            String answer,
            List<CodeSearchResult> evidence
    ) {
        Set<Integer> referenced = citationReferences(answer);
        long invalid = referenced.stream()
                .filter(index -> index < 1 || index > evidence.size())
                .count();
        List<String> claims = claimSegments(answer);
        long citedClaims = claims.stream().filter(CodeAnswerVerifier::containsCitation).count();
        long weakSupport = claims.stream()
                .filter(CodeAnswerVerifier::containsCitation)
                .filter(claim -> !citationClaimSupported(claim, evidence))
                .count();
        int coverage = claims.isEmpty()
                ? (referenced.isEmpty() ? 0 : 100)
                : (int) Math.round((100.0 * citedClaims) / claims.size());
        StringBuilder summary = new StringBuilder();
        if (invalid > 0) {
            summary.append(invalid).append(" citation reference(s) point outside returned evidence.");
        }
        if (weakSupport > 0) {
            if (!summary.isEmpty()) {
                summary.append(" ");
            }
            summary.append(weakSupport)
                    .append(" cited claim(s) have weak lexical support in their cited code evidence.");
        }
        if (summary.isEmpty() && !referenced.isEmpty()) {
            summary.append("All cited references point to returned evidence; weakSupport=")
                    .append(weakSupport).append(".");
        }
        return new CodeAnswerVerification.CitationQuality(
                referenced.size(), (int) invalid, coverage, (int) weakSupport, summary.toString());
    }

    private static Set<Integer> citationReferences(String answer) {
        Set<Integer> values = new HashSet<>();
        Matcher matcher = CITATION_PATTERN.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            try {
                values.add(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // The regex is numeric; retain defensive parsing for oversized values.
            }
        }
        return values;
    }

    private static List<String> claimSegments(String answer) {
        String normalized = (answer == null ? "" : answer).replace('\r', '\n');
        return CLAIM_SEPARATOR.splitAsStream(normalized)
                .map(String::trim)
                .filter(segment -> segment.length() >= 18)
                .filter(segment -> segment.matches("(?s).*[\\p{L}\\p{N}].*"))
                .limit(40)
                .toList();
    }

    private static boolean citationClaimSupported(String claim, List<CodeSearchResult> evidence) {
        Set<Integer> references = citationReferences(claim);
        if (references.isEmpty()) {
            return false;
        }
        Set<String> claimTerms = supportTerms(claim);
        if (claimTerms.isEmpty()) {
            return true;
        }
        for (Integer reference : references) {
            if (reference == null || reference < 1 || reference > evidence.size()) {
                return false;
            }
            CodeSearchResult result = evidence.get(reference - 1);
            Set<String> evidenceTerms = supportTerms(result == null ? "" : result.content());
            long overlap = claimTerms.stream().filter(evidenceTerms::contains).count();
            if (overlap >= Math.min(2, claimTerms.size())) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> supportTerms(String value) {
        Set<String> terms = new HashSet<>();
        String normalized = (value == null ? "" : value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\[\\d+]", " ");
        Matcher matcher = SUPPORT_TERM_PATTERN.matcher(normalized);
        while (matcher.find() && terms.size() < 32) {
            String term = matcher.group();
            if (!CITATION_STOP_WORDS.contains(term)) {
                terms.add(term);
            }
        }
        return terms;
    }

    private static boolean containsCitation(String answer) {
        return answer != null && CITATION_PATTERN.matcher(answer).find();
    }

    private static String normalizeReason(String reason) {
        return reason == null ? "" : reason.replaceAll("[\\r\\n]+", " ").trim();
    }

    @FunctionalInterface
    public interface AnswerQualityPolicy {
        String failureReason(String answer, int evidenceCount, String doneReason);

        static AnswerQualityPolicy accepting() {
            return (answer, evidenceCount, doneReason) -> null;
        }
    }

    @FunctionalInterface
    public interface ExactFactVerifier {
        String missingReason(String question, String answer, List<CodeSearchResult> evidence);

        static ExactFactVerifier accepting() {
            return (question, answer, evidence) -> null;
        }
    }
}
