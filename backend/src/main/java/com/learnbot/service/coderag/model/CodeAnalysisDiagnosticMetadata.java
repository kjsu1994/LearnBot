package com.learnbot.service.coderag.model;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.CodeIntelligenceAuthority;

import java.util.Locale;
import java.util.Map;

/**
 * Typed, metadata-only view of code-analysis diagnostics attached to retrieved evidence.
 *
 * <p>This reader deliberately does not inspect source content, file paths, extensions, or
 * framework names. Producers own diagnostic identity; consumers only validate and bound the
 * explicit metadata supplied by those producers.</p>
 */
public record CodeAnalysisDiagnosticMetadata(
        String status,
        String scope,
        String stage,
        String language,
        String analyzer,
        CodeIntelligenceAuthority authority
) {
    private static final int TOKEN_LIMIT = 64;
    private static final int LANGUAGE_LIMIT = 64;
    private static final int ANALYZER_LIMIT = 160;

    public CodeAnalysisDiagnosticMetadata {
        status = canonicalStatus(status);
        scope = canonicalToken(scope, TOKEN_LIMIT);
        stage = canonicalToken(stage, TOKEN_LIMIT);
        language = canonicalLanguage(language);
        analyzer = boundedLabel(analyzer, ANALYZER_LIMIT);
        authority = authority == null ? CodeIntelligenceAuthority.UNKNOWN : authority;
        if (status.isBlank()) {
            scope = "";
            stage = "";
            language = "";
            analyzer = "";
            authority = CodeIntelligenceAuthority.UNKNOWN;
        } else if (scope.isBlank()) {
            scope = "GRAPH_ANALYSIS";
        }
    }

    public static CodeAnalysisDiagnosticMetadata from(CodeSearchResult result) {
        Map<String, Object> metadata = result == null || result.metadata() == null
                ? Map.of()
                : result.metadata();
        String status = first(metadata,
                "analysisDiagnosticStatus",
                "codeIntelligenceDiagnosticStatus", "codeIntelligenceStatus",
                "diagnosticStatus", "analysisStatus");
        String scope = first(metadata,
                "analysisDiagnosticScope",
                "codeIntelligenceDiagnosticScope", "codeIntelligenceScope",
                "diagnosticScope");
        String stage = first(metadata,
                "analysisDiagnosticStage",
                "codeIntelligenceDiagnosticStage", "codeIntelligenceStage",
                "diagnosticStage", "stage");
        String language = first(metadata,
                "analysisDiagnosticLanguage",
                "codeIntelligenceLanguage",
                "diagnosticLanguage", "language");
        String analyzer = first(metadata,
                "analysisDiagnosticAnalyzer",
                "codeIntelligenceAnalyzer",
                "diagnosticAnalyzer", "analyzer", "parser");
        String authority = first(metadata,
                "analysisDiagnosticAuthority",
                "codeIntelligenceAuthority",
                "diagnosticAuthority", "authority");
        return new CodeAnalysisDiagnosticMetadata(
                status, scope, stage, language, analyzer,
                CodeIntelligenceAuthority.from(canonicalToken(authority, TOKEN_LIMIT)));
    }

    public boolean present() {
        return !status.isBlank();
    }

    private static String first(Map<String, Object> metadata, String... keys) {
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value == null) continue;
            String text = boundedLabel(String.valueOf(value), ANALYZER_LIMIT);
            if (!text.isBlank()) return text;
        }
        return "";
    }

    private static String canonicalToken(String value, int limit) {
        String bounded = boundedLabel(value, limit);
        if (bounded.isBlank()) return "";
        return bounded.toUpperCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    private static String canonicalStatus(String value) {
        String status = canonicalToken(value, TOKEN_LIMIT);
        return switch (status) {
            case "SUCCESS", "FAILED", "PARTIAL", "SKIPPED" -> status;
            default -> "";
        };
    }

    private static String canonicalLanguage(String value) {
        String bounded = boundedLabel(value, LANGUAGE_LIMIT).toLowerCase(Locale.ROOT);
        if (bounded.isBlank()) return "";
        return bounded
                .replaceAll("\\s+", "-")
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}._+#-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    private static String boundedLabel(String value, int limit) {
        if (value == null || value.isBlank()) return "";
        String normalized = value
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .replaceAll("[\\p{Cc}\\p{Cf}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit).stripTrailing();
    }
}
