package com.learnbot.service.coderag.answer;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.CodeIntelligenceAuthority;
import com.learnbot.service.coderag.evidence.CodeEvidenceRetentionPlan;
import com.learnbot.service.coderag.model.CodeEvidenceFact;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.CodeEvidenceItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a last-resort answer from literal selected evidence instead of a repository overview. */
public final class CodeEvidenceFidelityFallback {
    private static final int MAX_ITEMS = 10;
    private static final int MAX_FACTS_PER_ITEM = 8;
    private static final int MAX_EXCERPT_CHARS = 560;
    private static final int MAX_FACT_CHARS = 280;

    private CodeEvidenceFidelityFallback() {
    }

    public static String answer(List<CodeSearchResult> evidence, String failureReason) {
        return answer(evidence, failureReason, CodeEvidenceIr.empty(), CodeEvidenceRetentionPlan.empty());
    }

    public static String answer(
            List<CodeSearchResult> evidence,
            String failureReason,
            CodeEvidenceIr ir
    ) {
        CodeEvidenceIr safeIr = ir == null ? CodeEvidenceIr.empty() : ir;
        return answer(evidence, failureReason, safeIr, CodeEvidenceRetentionPlan.from(safeIr));
    }

    public static String answer(
            List<CodeSearchResult> evidence,
            String failureReason,
            CodeEvidenceIr ir,
            CodeEvidenceRetentionPlan retentionPlan
    ) {
        List<CodeSearchResult> available = evidence == null
                ? List.of()
                : evidence.stream().filter(java.util.Objects::nonNull).toList();
        if (available.isEmpty()) {
            return "The generated answer did not pass evidence-fidelity checks, and no code evidence was available.";
        }
        CodeEvidenceIr safeIr = ir == null ? CodeEvidenceIr.empty() : ir;
        CodeEvidenceRetentionPlan safePlan = retentionPlan == null
                ? CodeEvidenceRetentionPlan.from(safeIr)
                : retentionPlan;
        IrView irView = IrView.from(safeIr);
        List<IndexedEvidence> ranked = new ArrayList<>();
        for (int index = 0; index < evidence.size(); index++) {
            CodeSearchResult result = evidence.get(index);
            if (result != null) ranked.add(new IndexedEvidence(index, result));
        }
        ranked.sort(Comparator
                .comparingInt((IndexedEvidence item) -> retentionRank(item.result(), safePlan, irView))
                .thenComparingInt(item -> implementationRank(item.result()))
                .thenComparing((IndexedEvidence item) -> -item.result().score())
                .thenComparingInt(IndexedEvidence::index));

        StringBuilder answer = new StringBuilder();
        answer.append("The generated answer did not pass evidence-fidelity checks");
        if (failureReason != null && !failureReason.isBlank()) answer.append(" (").append(failureReason).append(")");
        answer.append(". The retained code evidence and typed exact facts are:\n\n");
        for (IndexedEvidence item : ranked.stream().limit(MAX_ITEMS).toList()) {
            CodeSearchResult result = item.result();
            int citation = item.index() + 1;
            String evidenceId = irView.evidenceId(result);
            answer.append("- source `").append(inline(result.filePath(), MAX_FACT_CHARS)).append("`")
                    .append(" lines ").append(result.lineStart()).append("-").append(result.lineEnd());
            String symbol = firstNonBlank(result.methodName(), result.symbolName(), result.className());
            if (!symbol.isBlank()) answer.append(" symbol `").append(inline(symbol, MAX_FACT_CHARS)).append("`");
            answer.append(" [").append(citation).append("]\n");
            for (CodeEvidenceFact fact : irView.facts(evidenceId).stream()
                    .limit(MAX_FACTS_PER_ITEM).toList()) {
                answer.append("  - ")
                        .append(fact.exactness() == CodeEvidenceFact.Exactness.EXACT ? "exact" : "normalized")
                        .append(" fact `").append(inline(fact.subject(), MAX_FACT_CHARS)).append("`: `")
                        .append(inline(fact.predicate(), MAX_FACT_CHARS)).append("=")
                        .append(inline(fact.value(), MAX_FACT_CHARS)).append("` [")
                        .append(citation).append("]\n");
            }
            String excerpt = excerpt(result.content());
            if (!excerpt.isBlank()) {
                answer.append("  - source excerpt: `").append(excerpt).append("` [")
                        .append(citation).append("]\n");
            }
        }
        return answer.toString().trim();
    }

    private static int retentionRank(
            CodeSearchResult result,
            CodeEvidenceRetentionPlan plan,
            IrView irView
    ) {
        return plan.lookup(irView.evidenceId(result))
                .map(entry -> entry.level() == CodeEvidenceRetentionPlan.Level.REQUIRED ? 0 : 1)
                .orElse(2);
    }

    private static int implementationRank(CodeSearchResult result) {
        String symbol = firstNonBlank(result == null ? null : result.methodName(), result == null ? null : result.symbolName());
        int span = result == null ? 0 : Math.max(0, result.lineEnd() - result.lineStart() + 1);
        return !symbol.isBlank() && span >= 3 ? 0 : 1;
    }

    private static String excerpt(String content) {
        String compact = safe(content).replaceAll("(?m)^\\s*\\d+:\\s*", "")
                .replace('`', '\'')
                .replaceAll("\\s+", " ")
                .trim();
        if (compact.length() <= MAX_EXCERPT_CHARS) return compact;
        return compact.substring(0, MAX_EXCERPT_CHARS - 3).trim() + "...";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }

    private static String inline(String value, int maxChars) {
        String compact = safe(value).replace('`', '\'').replaceAll("\\s+", " ").trim();
        int limit = Math.max(1, maxChars);
        if (compact.length() <= limit) return compact;
        return compact.substring(0, Math.max(1, limit - 3)).trim() + "...";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record IrView(
            Map<String, String> evidenceIdBySourceIdentity,
            Map<String, List<CodeEvidenceFact>> factsByEvidenceId
    ) {
        private static IrView from(CodeEvidenceIr ir) {
            Map<String, String> ids = new LinkedHashMap<>();
            Map<String, CodeEvidenceItem> items = new LinkedHashMap<>();
            for (CodeEvidenceItem item : ir.evidenceItems()) {
                ids.putIfAbsent(CodeEvidenceItem.evidenceId(item.source()), item.evidenceId());
                items.merge(item.evidenceId(), item, CodeEvidenceItem::merge);
            }
            Map<String, List<CodeEvidenceFact>> facts = new LinkedHashMap<>();
            for (CodeEvidenceFact fact : ir.facts()) {
                if (fact.exactness() == CodeEvidenceFact.Exactness.INFERRED) continue;
                CodeEvidenceItem item = items.get(fact.sourceEvidenceId());
                if (item == null
                        || item.authority().rank() < CodeIntelligenceAuthority.SYNTAX.rank()
                        || fact.authority().rank() < CodeIntelligenceAuthority.SYNTAX.rank()) {
                    continue;
                }
                facts.computeIfAbsent(fact.sourceEvidenceId(), ignored -> new ArrayList<>()).add(fact);
            }
            facts.replaceAll((ignored, values) -> List.copyOf(values));
            return new IrView(Map.copyOf(ids), Map.copyOf(facts));
        }

        private String evidenceId(CodeSearchResult result) {
            String sourceIdentity = CodeEvidenceItem.evidenceId(result);
            return evidenceIdBySourceIdentity.getOrDefault(sourceIdentity, sourceIdentity);
        }

        private List<CodeEvidenceFact> facts(String evidenceId) {
            return factsByEvidenceId.getOrDefault(evidenceId, List.of());
        }
    }

    private record IndexedEvidence(int index, CodeSearchResult result) {
    }
}
