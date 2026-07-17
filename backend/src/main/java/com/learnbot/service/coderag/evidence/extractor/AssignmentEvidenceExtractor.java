package com.learnbot.service.coderag.evidence.extractor;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.CodeIntelligenceAuthority;
import com.learnbot.service.coderag.model.CodeEvidenceConstraint;
import com.learnbot.service.coderag.model.CodeEvidenceExtractionContext;
import com.learnbot.service.coderag.model.CodeEvidenceFact;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.CodeEvidenceItem;
import com.learnbot.service.coderag.model.CodeEvidenceSignal;
import com.learnbot.service.coderag.model.EvidenceExtractionStage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AssignmentEvidenceExtractor implements EvidenceExtractor {
    private static final String IDENTIFIER = "[A-Za-z_$][A-Za-z0-9_$]*";
    private static final Pattern LITERAL_ASSIGNMENT = Pattern.compile(
            "(?m)^\\s*(?:\\d+:\\s*)?([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)"
                    + "\\s*=(?!=)\\s*(true|false|null|-?\\d+(?:\\.\\d+)?|"
                    + "\"(?:\\\\.|[^\"\\\\\\r\\n]){0,160}\"|'(?:\\\\.|[^'\\\\\\r\\n]){0,80}')\\s*;");
    private static final String QUOTED_EXPRESSION =
            "\"(?:\\\\.|[^\"\\\\\\r\\n]){0,160}\"|'(?:\\\\.|[^'\\\\\\r\\n]){0,80}'"
                    + "|`(?:\\\\.|[^`\\\\]){0,160}`";
    private static final Pattern EXPRESSION_ASSIGNMENT = Pattern.compile(
            "(?m)^\\s*(?:\\d+:\\s*)?(" + IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER
                    + ")*)\\s*=(?!=|>)\\s*((?:" + QUOTED_EXPRESSION + "|[^;\"'`]){1,360})\\s*;");
    private static final Pattern QUOTED_FRAGMENT = Pattern.compile(QUOTED_EXPRESSION);
    private static final Pattern LITERAL_VALUE = Pattern.compile(
            "^(?:true|false|null|-?\\d+(?:\\.\\d+)?|"
                    + "\"(?:\\\\.|[^\"\\\\\\r\\n]){0,160}\"|'(?:\\\\.|[^'\\\\\\r\\n]){0,80}')$");
    private static final int MAX_EXPRESSION_CHARS = 360;
    private static final int MAX_EXPRESSION_LINES = 8;
    private static final int MAX_EXPRESSIONS_PER_EVIDENCE = 12;

    @Override
    public String id() {
        return "assignment";
    }

    @Override
    public Set<EvidenceExtractionStage> stages() {
        return Set.of(EvidenceExtractionStage.POST_OPERATION, EvidenceExtractionStage.PRE_ANSWER);
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public boolean supports(CodeEvidenceExtractionContext context) {
        if (context == null) return false;
        return context.evidence().stream().anyMatch(result -> {
            String content = result.content() == null ? "" : result.content();
            return LITERAL_ASSIGNMENT.matcher(content).find() || !expressionAssignments(content).isEmpty();
        });
    }

    @Override
    public CodeEvidenceIr extract(CodeEvidenceExtractionContext context) {
        int limit = context.maxItemsPerExtractor();
        Map<String, CodeEvidenceItem> items = new LinkedHashMap<>();
        List<CodeEvidenceFact> facts = new ArrayList<>();
        List<CodeEvidenceConstraint> constraints = new ArrayList<>();
        List<CodeEvidenceSignal> signals = new ArrayList<>();
        Map<String, List<Observation>> byTarget = new LinkedHashMap<>();
        Set<String> signaledEvidence = new LinkedHashSet<>();
        int encounterOrder = 0;

        for (CodeSearchResult result : context.evidence()) {
            if (facts.size() >= limit) break;
            String content = result.content() == null ? "" : result.content();
            Matcher matcher = LITERAL_ASSIGNMENT.matcher(content);
            while (matcher.find() && facts.size() < limit) {
                String left = matcher.group(1).trim();
                String value = matcher.group(2).trim();
                String evidenceId = CodeEvidenceItem.evidenceId(result);
                CodeIntelligenceAuthority authority = EvidenceExtractionSupport.directSyntaxAuthority(result);
                items.putIfAbsent(evidenceId, new CodeEvidenceItem(evidenceId, result,
                        Set.of(CodeEvidenceItem.Kind.DIRECT_SOURCE, CodeEvidenceItem.Kind.ASSIGNMENT), authority));
                CodeEvidenceFact fact = CodeEvidenceFact.of(evidenceId, left, "ASSIGNS_LITERAL", value,
                        CodeEvidenceFact.Exactness.EXACT, 1.0, authority);
                facts.add(fact);
                String key = (result.filePath() == null ? "" : result.filePath()).toLowerCase(Locale.ROOT)
                        + "\u001f" + left.toLowerCase(Locale.ROOT);
                byTarget.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Observation(
                        result, fact, left, value, EvidenceExtractionSupport.lineAtOffset(result, matcher.start()),
                        encounterOrder++));
                if (signaledEvidence.add(evidenceId)) {
                    signals.add(new CodeEvidenceSignal(CodeEvidenceSignal.Type.EXACT_LITERAL, evidenceId, 0.8,
                            "A bounded literal assignment was observed directly in source."));
                }
            }
            for (ExpressionAssignment assignment : expressionAssignments(content)) {
                if (facts.size() >= limit) break;
                String evidenceId = CodeEvidenceItem.evidenceId(result);
                CodeIntelligenceAuthority authority = EvidenceExtractionSupport.directSyntaxAuthority(result);
                items.putIfAbsent(evidenceId, new CodeEvidenceItem(evidenceId, result,
                        Set.of(CodeEvidenceItem.Kind.DIRECT_SOURCE, CodeEvidenceItem.Kind.ASSIGNMENT), authority));
                CodeEvidenceFact fact = CodeEvidenceFact.of(
                        evidenceId,
                        assignment.left(),
                        "ASSIGNS_EXPRESSION",
                        assignment.value(),
                        CodeEvidenceFact.Exactness.EXACT,
                        1.0,
                        authority
                );
                facts.add(fact);
            }
        }

        for (List<Observation> observations : byTarget.values()) {
            observations.sort(Comparator.comparingInt(Observation::line).thenComparingInt(Observation::order));
            LinkedHashMap<String, Observation> distinctValues = new LinkedHashMap<>();
            observations.forEach(value -> distinctValues.putIfAbsent(value.value(), value));
            if (distinctValues.size() < 2) continue;
            Observation first = observations.get(0);
            if (facts.size() < limit) {
                String transition = String.join(" -> ", distinctValues.keySet());
                facts.add(CodeEvidenceFact.of(first.fact().sourceEvidenceId(), first.left(),
                        "STATE_TRANSITION_CANDIDATE", transition, CodeEvidenceFact.Exactness.INFERRED, 0.75,
                        first.fact().authority()));
            }
            distinctValues.values().forEach(value -> constraints.add(new CodeEvidenceConstraint(
                    CodeEvidenceConstraint.Type.EXACT_FACT_REQUIRED, value.fact().factId(),
                    "Preserve each directly observed literal participating in the state transition.")));
            signals.add(new CodeEvidenceSignal(CodeEvidenceSignal.Type.STATE_TRANSITION,
                    first.fact().sourceEvidenceId(), 0.9,
                    "Multiple literal values for the same target form a state-transition candidate."));
        }
        return new CodeEvidenceIr(List.copyOf(items.values()), facts, constraints, signals, List.of(), List.of());
    }

    private static List<ExpressionAssignment> expressionAssignments(String content) {
        if (content == null || content.isBlank()) return List.of();
        List<ExpressionAssignment> assignments = new ArrayList<>();
        Matcher matcher = EXPRESSION_ASSIGNMENT.matcher(content);
        while (matcher.find() && assignments.size() < MAX_EXPRESSIONS_PER_EVIDENCE) {
            String value = cleanExpression(matcher.group(2));
            if (value.isBlank() || value.length() > MAX_EXPRESSION_CHARS
                    || value.lines().count() > MAX_EXPRESSION_LINES
                    || LITERAL_VALUE.matcher(value).matches()
                    || !balancedDelimiters(value)) {
                continue;
            }
            String left = matcher.group(1).replaceAll("\\s*\\.\\s*", ".").trim();
            assignments.add(new ExpressionAssignment(left, value));
        }
        return List.copyOf(assignments);
    }

    private static boolean balancedDelimiters(String value) {
        String structural = QUOTED_FRAGMENT.matcher(value).replaceAll("");
        int round = 0;
        int square = 0;
        int curly = 0;
        for (int index = 0; index < structural.length(); index++) {
            switch (structural.charAt(index)) {
                case '(' -> round++;
                case ')' -> round--;
                case '[' -> square++;
                case ']' -> square--;
                case '{' -> curly++;
                case '}' -> curly--;
                default -> { }
            }
            if (round < 0 || square < 0 || curly < 0) return false;
        }
        return round == 0 && square == 0 && curly == 0;
    }

    private static String cleanExpression(String value) {
        String normalized = value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = normalized.lines()
                .map(line -> line.replaceFirst("^\\s*\\d+:\\s*", "").stripTrailing())
                .toList();
        return String.join("\n", lines).trim();
    }

    private record Observation(
            CodeSearchResult result,
            CodeEvidenceFact fact,
            String left,
            String value,
            int line,
            int order
    ) {
    }

    private record ExpressionAssignment(String left, String value) {
    }
}
