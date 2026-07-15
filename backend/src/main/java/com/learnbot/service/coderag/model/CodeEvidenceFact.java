package com.learnbot.service.coderag.model;

import com.learnbot.service.CodeIntelligenceAuthority;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

public record CodeEvidenceFact(
        String factId,
        String sourceEvidenceId,
        String subject,
        String predicate,
        String value,
        Exactness exactness,
        double confidence,
        CodeIntelligenceAuthority authority
) {
    public enum Exactness {
        EXACT,
        NORMALIZED,
        INFERRED
    }

    public CodeEvidenceFact {
        sourceEvidenceId = required(sourceEvidenceId, "sourceEvidenceId");
        subject = required(subject, "subject");
        predicate = required(predicate, "predicate").toUpperCase(Locale.ROOT);
        value = required(value, "value");
        exactness = exactness == null ? Exactness.INFERRED : exactness;
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        authority = authority == null ? CodeIntelligenceAuthority.UNKNOWN : authority;
        factId = factId == null || factId.isBlank()
                ? stableId(sourceEvidenceId, subject, predicate, value)
                : factId.trim();
    }

    public static CodeEvidenceFact of(
            String sourceEvidenceId,
            String subject,
            String predicate,
            String value,
            Exactness exactness,
            double confidence,
            CodeIntelligenceAuthority authority
    ) {
        return new CodeEvidenceFact("", sourceEvidenceId, subject, predicate, value, exactness, confidence, authority);
    }

    public CodeEvidenceFact merge(CodeEvidenceFact other) {
        if (other == null) return this;
        if (!factId.equals(other.factId)) {
            throw new IllegalArgumentException("Cannot merge different fact identities");
        }
        CodeIntelligenceAuthority mergedAuthority = other.authority.rank() > authority.rank()
                ? other.authority : authority;
        Exactness mergedExactness = exactness.ordinal() <= other.exactness.ordinal() ? exactness : other.exactness;
        return new CodeEvidenceFact(factId, sourceEvidenceId, subject, predicate, value,
                mergedExactness, Math.max(confidence, other.confidence), mergedAuthority);
    }

    private static String stableId(String sourceEvidenceId, String subject, String predicate, String value) {
        String key = String.join("\u001f", sourceEvidenceId, subject, predicate, value);
        return "fact:" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private static String required(String value, String field) {
        String safe = value == null ? "" : value.trim();
        if (safe.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return safe;
    }
}
