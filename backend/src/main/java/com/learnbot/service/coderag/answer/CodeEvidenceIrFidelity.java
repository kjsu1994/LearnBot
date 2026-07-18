package com.learnbot.service.coderag.answer;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.CodeIntelligenceAuthority;
import com.learnbot.service.coderag.evidence.CodeLexicalCalls;
import com.learnbot.service.coderag.model.CodeEvidenceConstraint;
import com.learnbot.service.coderag.model.CodeEvidenceFact;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.CodeEvidenceItem;
import com.learnbot.service.coderag.model.CodeNavigationHandle;

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
import java.util.stream.Collectors;

/** Predicate-agnostic prompting and verification over trusted typed evidence. */
public final class CodeEvidenceIrFidelity {
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*");
    private static final Pattern CITATION = Pattern.compile("\\[\\d+]");
    private static final Pattern ATOM_CHARACTER = Pattern.compile("[\\p{L}\\p{N}_$]");
    private static final Pattern RELEVANCE_TERM = Pattern.compile("[\\p{L}\\p{N}_$-]{2,}");
    private static final List<String> CLAIM_METADATA_KEYS = List.of(
            "llmSupportedClaims", "llmChecklistGoal", "llmSearchPlanQuery",
            "llmFollowUpQuery", "llmReadArea");
    private static final List<String> DIRECT_READ_METADATA_KEYS = List.of(
            "llmRequestedPath", "llmRequestedSymbol", "llmRequestedChunkId");
    private static final int MAX_PROMPT_FACTS = 16;
    private static final int MAX_NAVIGATION_OUTLINE_SOURCES = 6;
    private static final int MAX_NAVIGATION_HANDLES_PER_SOURCE = 32;
    private static final int MAX_PROMPT_CHARS = 3_200;
    private static final int MIN_FACT_RELEVANCE_SCORE = 6;

    private CodeEvidenceIrFidelity() {
    }

    public static int promptCharLimit() {
        return MAX_PROMPT_CHARS;
    }

    public static String promptFacts(CodeEvidenceIr ir) {
        List<CodeSearchResult> evidence = ir == null ? List.of() : ir.evidenceItems().stream()
                .map(CodeEvidenceItem::source)
                .toList();
        return promptFacts("", ir, evidence);
    }

    /**
     * Renders trusted required facts first, followed by trusted facts relevant to the
     * question. A fact is rendered only when its source survived final evidence selection.
     */
    public static String promptFacts(
            String question,
            CodeEvidenceIr ir,
            List<CodeSearchResult> finalEvidence
    ) {
        Map<String, Integer> citations = citationNumbers(finalEvidence);
        List<TrustedFact> selected = selectedFacts(question, ir).stream()
                .filter(value -> citations.containsKey(value.fact().sourceEvidenceId()))
                .toList();
        StringBuilder prompt = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        int rendered = 0;
        for (TrustedFact value : selected) {
            if (prompt.isEmpty()) {
                prompt.append("\n\nTrusted typed facts from selected source evidence. "
                        + "Preserve required values exactly:\n");
            }
            CodeEvidenceFact fact = value.fact();
            String statement = inline(fact.subject()) + ": "
                    + inline(fact.predicate()) + "=" + inline(fact.value());
            if (!seen.add(statement)) continue;
            String line = "- `" + statement.replace("`", "\\`") + "`"
                    + citationLabel(citations, fact.sourceEvidenceId()) + "\n";
            if (rendered >= MAX_PROMPT_FACTS || prompt.length() + line.length() > MAX_PROMPT_CHARS) break;
            prompt.append(line);
            rendered++;
        }
        appendNavigationOutlines(prompt, ir, citations);
        return prompt.isEmpty() ? "" : prompt.toString().stripTrailing();
    }

    private static void appendNavigationOutlines(
            StringBuilder prompt,
            CodeEvidenceIr ir,
            Map<String, Integer> citations
    ) {
        if (ir == null || citations.isEmpty() || prompt.length() >= MAX_PROMPT_CHARS) return;
        Map<String, CodeEvidenceItem> items = ir.evidenceItems().stream()
                .collect(Collectors.toMap(
                        CodeEvidenceItem::evidenceId,
                        item -> item,
                        CodeEvidenceItem::merge,
                        LinkedHashMap::new));
        Map<String, List<CodeNavigationHandle>> bySource = ir.navigationHandles().stream()
                .filter(handle -> handle.kind() == CodeNavigationHandle.Kind.CALL)
                .filter(handle -> citations.containsKey(handle.sourceEvidenceId()))
                .filter(handle -> trustedNavigationSource(items.get(handle.sourceEvidenceId())))
                .collect(Collectors.groupingBy(
                        CodeNavigationHandle::sourceEvidenceId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        if (bySource.isEmpty()) return;

        List<NavigationOutline> outlines = bySource.entrySet().stream()
                .map(entry -> navigationOutline(
                        items.get(entry.getKey()),
                        citations.get(entry.getKey()),
                        entry.getValue()))
                .filter(value -> !value.symbols().isEmpty())
                .sorted(Comparator
                        .comparing((NavigationOutline value) -> isDirectRead(value.source()) ? 0 : 1)
                        .thenComparing(Comparator.comparingInt(
                                (NavigationOutline value) -> value.symbols().size()).reversed())
                        .thenComparingInt(NavigationOutline::citation))
                .limit(MAX_NAVIGATION_OUTLINE_SOURCES)
                .toList();
        if (outlines.isEmpty()) return;

        String header = "\n\nObserved lexical call sites from selected direct source evidence "
                + "(source order only; branch execution and dynamic dispatch are not inferred):\n";
        StringBuilder block = new StringBuilder(header);
        int lines = 0;
        for (NavigationOutline outline : outlines) {
            String prefix = "- `" + inline(sourceIdentity(outline.source())).replace("`", "\\`")
                    + "`: calls=[";
            String suffix = "] [" + outline.citation() + "]\n";
            StringBuilder line = new StringBuilder(prefix);
            int added = 0;
            for (String symbol : outline.symbols()) {
                String separator = added == 0 ? "" : ", ";
                if (prompt.length() + block.length() + line.length() + separator.length()
                        + symbol.length() + suffix.length() > MAX_PROMPT_CHARS) break;
                line.append(separator).append(inline(symbol));
                added++;
            }
            if (added == 0) continue;
            line.append(suffix);
            block.append(line);
            lines++;
        }
        if (lines > 0) prompt.append(block);
    }

    private static boolean trustedNavigationSource(CodeEvidenceItem item) {
        return item != null
                && item.kinds().contains(CodeEvidenceItem.Kind.DIRECT_SOURCE)
                && item.authority().rank() >= CodeIntelligenceAuthority.SYNTAX.rank();
    }

    private static NavigationOutline navigationOutline(
            CodeEvidenceItem item,
            Integer citation,
            List<CodeNavigationHandle> handles
    ) {
        List<CodeNavigationHandle> ordered = handles == null ? List.of() : handles.stream()
                .sorted(Comparator.comparingInt(CodeNavigationHandle::lineStart)
                        .thenComparingInt(CodeNavigationHandle::lineEnd)
                        .thenComparing(CodeNavigationHandle::symbol))
                .toList();
        LinkedHashMap<String, CodeNavigationHandle> unique = new LinkedHashMap<>();
        ordered.forEach(handle -> unique.putIfAbsent(
                handle.symbol().toLowerCase(Locale.ROOT), handle));
        List<CodeNavigationHandle> distinct = List.copyOf(unique.values());
        List<CodeNavigationHandle> selected;
        if (distinct.size() <= MAX_NAVIGATION_HANDLES_PER_SOURCE) {
            selected = distinct;
        } else {
            selected = CodeLexicalCalls.coverageOrder(distinct.size()).stream()
                    .limit(MAX_NAVIGATION_HANDLES_PER_SOURCE)
                    .map(distinct::get)
                    .sorted(Comparator.comparingInt(CodeNavigationHandle::lineStart)
                            .thenComparing(CodeNavigationHandle::symbol))
                    .toList();
        }
        return new NavigationOutline(
                item == null ? null : item.source(),
                citation == null ? 0 : citation,
                selected.stream().map(CodeNavigationHandle::symbol).toList());
    }

    private static String sourceIdentity(CodeSearchResult source) {
        if (source == null) return "source";
        String callable = firstNonBlank(
                source.methodName(), source.symbolName(), source.className(),
                source.controlName(), source.eventName());
        String path = safe(source.filePath());
        if (path.isBlank()) return callable.isBlank() ? "source" : callable;
        return callable.isBlank() ? path : path + "#" + callable;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    /** Source identities that should receive bounded protection during final selection. */
    public static Set<String> relevantEvidenceIds(String question, CodeEvidenceIr ir) {
        LinkedHashSet<String> evidenceIds = selectedFacts(question, ir).stream()
                .map(value -> value.fact().sourceEvidenceId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return Set.copyOf(evidenceIds);
    }

    /** Verifies every trusted fact named by an exact-fact constraint without predicate knowledge. */
    public static String missingReason(String answer, CodeEvidenceIr ir) {
        List<TrustedFact> required = trustedFacts(ir).stream()
                .filter(TrustedFact::required)
                .toList();
        if (required.isEmpty()) return null;

        String safeAnswer = answer == null ? "" : answer;
        Map<String, List<CodeEvidenceFact>> bySubject = new LinkedHashMap<>();
        for (TrustedFact value : required) {
            bySubject.computeIfAbsent(normalize(value.fact().subject()), ignored -> new ArrayList<>())
                    .add(value.fact());
        }
        for (List<CodeEvidenceFact> facts : bySubject.values()) {
            String subject = facts.get(0).subject();
            if (!mentionsSubject(safeAnswer, subject)) {
                return "missing required fact subject identity: " + subject;
            }
            List<String> missingValues = facts.stream()
                    .map(CodeEvidenceFact::value)
                    .distinct()
                    .filter(value -> !containsValue(safeAnswer, value))
                    .toList();
            if (!missingValues.isEmpty()) {
                return "missing required fact value(s) for " + subject + ": "
                        + String.join(", ", missingValues);
            }
        }
        return null;
    }

    private static List<TrustedFact> selectedFacts(String question, CodeEvidenceIr ir) {
        List<TrustedFact> trusted = trustedFacts(ir);
        if (trusted.isEmpty()) return List.of();
        String safeQuestion = question == null ? "" : question.trim();
        Set<String> questionTerms = relevanceTerms(safeQuestion);
        List<TrustedFact> selected = new ArrayList<>();
        trusted.stream().filter(TrustedFact::required).forEach(selected::add);
        if (!safeQuestion.isBlank()) {
            trusted.stream()
                    .filter(value -> !value.required())
                    .filter(value -> relevanceScore(value, questionTerms, safeQuestion)
                            >= MIN_FACT_RELEVANCE_SCORE)
                    .sorted(Comparator
                            .comparingInt((TrustedFact value) ->
                                    relevanceScore(value, questionTerms, safeQuestion)).reversed()
                            .thenComparing(value -> value.fact().factId()))
                    .forEach(selected::add);
        }
        return List.copyOf(selected);
    }

    private static List<TrustedFact> trustedFacts(CodeEvidenceIr ir) {
        if (ir == null) return List.of();
        Map<String, CodeEvidenceItem> items = ir.evidenceItems().stream()
                .collect(Collectors.toMap(
                        CodeEvidenceItem::evidenceId,
                        item -> item,
                        (left, right) -> left,
                        LinkedHashMap::new));
        Set<String> requiredIds = requiredFactIds(ir);
        return ir.facts().stream()
                .map(fact -> new TrustedFact(fact, items.get(fact.sourceEvidenceId()),
                        requiredIds.contains(fact.factId())))
                .filter(CodeEvidenceIrFidelity::trusted)
                .sorted(Comparator.comparing(TrustedFact::required).reversed()
                        .thenComparing(value -> value.fact().factId()))
                .toList();
    }

    private static boolean trusted(TrustedFact value) {
        if (value == null || value.fact() == null || value.item() == null) return false;
        CodeEvidenceFact fact = value.fact();
        boolean trustedExactness = fact.exactness() == CodeEvidenceFact.Exactness.EXACT
                || fact.exactness() == CodeEvidenceFact.Exactness.NORMALIZED;
        return trustedExactness
                && value.item().authority().rank() >= CodeIntelligenceAuthority.SYNTAX.rank()
                && fact.authority().rank() >= CodeIntelligenceAuthority.SYNTAX.rank();
    }

    private static int relevanceScore(
            TrustedFact value,
            Set<String> questionTerms,
            String question
    ) {
        CodeEvidenceFact fact = value.fact();
        int score = 4 * overlap(questionTerms,
                relevanceTerms(fact.subject() + " " + fact.predicate() + " " + fact.value()));
        if (mentionsSubject(question, fact.subject())) score += 8;

        CodeSearchResult source = value.item().source();
        score += 3 * overlap(questionTerms, relevanceTerms(String.join(" ",
                safe(source.filePath()), safe(source.chunkType()), safe(source.className()),
                safe(source.methodName()), safe(source.symbolName()), safe(source.controlName()),
                safe(source.eventName()), source.chunkId() == null ? "" : source.chunkId().toString())));

        StringBuilder metadataContext = new StringBuilder();
        List<String> metadataKeys = isDirectRead(source)
                ? DIRECT_READ_METADATA_KEYS
                : CLAIM_METADATA_KEYS;
        for (String key : metadataKeys) {
            appendMetadata(metadataContext, source.metadata() == null ? null : source.metadata().get(key));
        }
        return score + (3 * overlap(questionTerms, relevanceTerms(metadataContext.toString())));
    }

    private static boolean isDirectRead(CodeSearchResult source) {
        return source != null
                && source.metadata() != null
                && Boolean.parseBoolean(String.valueOf(
                source.metadata().getOrDefault("llmDirectRead", false)));
    }

    private static boolean mentionsSubject(String text, String subject) {
        List<String> subjectIdentifiers = identifiers(subject).stream()
                .filter(value -> value.codePointCount(0, value.length()) >= 2)
                .distinct()
                .toList();
        if (subjectIdentifiers.isEmpty()) {
            String normalizedSubject = normalize(subject);
            return !normalizedSubject.isBlank() && normalize(text).contains(normalizedSubject);
        }
        return subjectIdentifiers.stream().allMatch(value -> containsValue(text, value));
    }

    private static List<String> identifiers(String value) {
        List<String> output = new ArrayList<>();
        Matcher matcher = IDENTIFIER.matcher(safe(value));
        while (matcher.find()) {
            String identifier = matcher.group().toLowerCase(Locale.ROOT).replaceFirst("^[_$]+", "");
            if (!identifier.isBlank()) output.add(identifier);
        }
        return List.copyOf(output);
    }

    private static int overlap(Set<String> left, Set<String> right) {
        int overlap = 0;
        for (String value : left) {
            if (right.contains(value)) overlap++;
        }
        return overlap;
    }

    private static Set<String> relevanceTerms(String value) {
        String separated = safe(value).replaceAll("([\\p{Ll}\\p{Nd}])([\\p{Lu}])", "$1 $2")
                .toLowerCase(Locale.ROOT);
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher matcher = RELEVANCE_TERM.matcher(separated);
        while (matcher.find() && terms.size() < 96) {
            String term = matcher.group();
            if (term.codePointCount(0, term.length()) >= 2) terms.add(term);
        }
        return Set.copyOf(terms);
    }

    private static void appendMetadata(StringBuilder output, Object raw) {
        if (raw instanceof Iterable<?> values) {
            values.forEach(value -> appendMetadata(output, value));
            return;
        }
        if (raw != null && !String.valueOf(raw).isBlank()) {
            if (!output.isEmpty()) output.append(' ');
            output.append(raw);
        }
    }

    private static Set<String> requiredFactIds(CodeEvidenceIr ir) {
        return ir.constraints().stream()
                .filter(value -> value.type() == CodeEvidenceConstraint.Type.EXACT_FACT_REQUIRED)
                .map(CodeEvidenceConstraint::targetId)
                .collect(Collectors.toSet());
    }

    private static Map<String, Integer> citationNumbers(List<CodeSearchResult> finalEvidence) {
        Map<String, Integer> citations = new LinkedHashMap<>();
        List<CodeSearchResult> evidence = finalEvidence == null ? List.of() : finalEvidence;
        for (int index = 0; index < evidence.size(); index++) {
            CodeSearchResult result = evidence.get(index);
            if (result != null) citations.putIfAbsent(CodeEvidenceItem.evidenceId(result), index + 1);
        }
        return Map.copyOf(citations);
    }

    private static String citationLabel(Map<String, Integer> citations, String evidenceId) {
        Integer citation = citations.get(evidenceId);
        return citation == null ? "" : " [" + citation + "]";
    }

    private static boolean containsValue(String answer, String value) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.length() >= 2 && ((candidate.startsWith("\"") && candidate.endsWith("\""))
                || (candidate.startsWith("'") && candidate.endsWith("'")))) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        if (candidate.isBlank()) return false;
        String citationless = CITATION.matcher(answer == null ? "" : answer).replaceAll(" ");
        StringBuilder expression = new StringBuilder();
        for (String part : candidate.split("\\s+")) {
            if (!expression.isEmpty()) expression.append("\\s+");
            expression.append(Pattern.quote(part));
        }
        boolean startsWithAtom = ATOM_CHARACTER.matcher(candidate.substring(0, 1)).matches();
        boolean endsWithAtom = ATOM_CHARACTER.matcher(candidate.substring(candidate.length() - 1)).matches();
        Pattern exactAtom = Pattern.compile(
                (startsWithAtom ? "(?<![\\p{L}\\p{N}_$])" : "")
                        + expression
                        + (endsWithAtom ? "(?![\\p{L}\\p{N}_$])" : ""),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return exactAtom.matcher(citationless).find();
    }

    private static String inline(String value) {
        return safe(value).replaceAll("\\s+", " ").trim();
    }

    private static String normalize(String value) {
        return safe(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record TrustedFact(CodeEvidenceFact fact, CodeEvidenceItem item, boolean required) {
    }

    private record NavigationOutline(
            CodeSearchResult source,
            int citation,
            List<String> symbols
    ) {
    }
}
