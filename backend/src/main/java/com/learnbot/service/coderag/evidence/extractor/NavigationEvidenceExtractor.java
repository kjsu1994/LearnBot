package com.learnbot.service.coderag.evidence.extractor;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.coderag.evidence.CodeLexicalCalls;
import com.learnbot.service.coderag.model.CodeEvidenceConstraint;
import com.learnbot.service.coderag.model.CodeEvidenceExtractionContext;
import com.learnbot.service.coderag.model.CodeEvidenceFact;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.CodeEvidenceItem;
import com.learnbot.service.coderag.model.CodeEvidenceSignal;
import com.learnbot.service.coderag.model.CodeNavigationHandle;
import com.learnbot.service.coderag.model.EvidenceExtractionStage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class NavigationEvidenceExtractor implements EvidenceExtractor {
    /**
     * Keeps the planner map focused on the highest-ranked callable sources while leaving room for
     * their observed operands. The cap is a resource boundary only: source order still comes from
     * retrieval relevance and no language, framework, or question vocabulary is consulted.
     */
    private static final int MAX_NAVIGATION_SOURCE_BUCKETS = 8;
    private static final int MAX_NAVIGATION_HANDLES = 48;
    private static final int MAX_PRIMARY_SOURCE_CANDIDATES = 32;
    private static final Pattern CONSTRUCTED_TYPE = Pattern.compile(
            "\\bnew\\s+([A-Z][A-Za-z0-9_$]{1,})\\b");

    @Override
    public String id() {
        return "navigation";
    }

    @Override
    public Set<EvidenceExtractionStage> stages() {
        return Set.of(EvidenceExtractionStage.POST_SEED, EvidenceExtractionStage.POST_OPERATION);
    }

    @Override
    public int priority() {
        return 40;
    }

    @Override
    public boolean supports(CodeEvidenceExtractionContext context) {
        if (context == null) return false;
        return context.evidence().stream().anyMatch(result -> {
            String content = result.content() == null ? "" : result.content();
            String code = CodeLexicalCalls.mask(content);
            return isExpandableDefinition(result)
                    || !CodeLexicalCalls.scan(content, result.methodName()).isEmpty()
                    || CONSTRUCTED_TYPE.matcher(code).find()
                    || EvidenceExtractionSupport.metadataBoolean(result, "graphExpanded");
        });
    }

    @Override
    public CodeEvidenceIr extract(CodeEvidenceExtractionContext context) {
        int limit = context.maxItemsPerExtractor();
        int handleLimit = Math.min(MAX_NAVIGATION_HANDLES, Math.max(limit, limit * 4));
        Map<String, CodeEvidenceItem> items = new LinkedHashMap<>();
        Map<String, CodeNavigationHandle> handles = new LinkedHashMap<>();
        List<CodeEvidenceFact> facts = new ArrayList<>();
        List<CodeEvidenceConstraint> constraints = new ArrayList<>();
        List<CodeEvidenceSignal> signals = new ArrayList<>();
        Set<String> signaled = new java.util.LinkedHashSet<>();

        int sourceLimit = Math.min(
                MAX_NAVIGATION_SOURCE_BUCKETS,
                Math.max(1, (limit + 1) / 2));
        List<CodeSearchResult> sources = preferredSources(context.evidence()).stream()
                .limit(sourceLimit)
                .toList();
        for (CodeSearchResult result : sources) {
            if (handles.size() >= handleLimit) break;
            if (isExpandableDefinition(result)) {
                addDefinitionHandle(result, items, handles, constraints);
            }
        }

        int remaining = Math.max(0, handleLimit - handles.size());
        List<List<NavigationCandidate>> buckets = new ArrayList<>();
        for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
            CodeSearchResult result = sources.get(sourceIndex);
            String content = result.content() == null ? "" : result.content();
            String code = CodeLexicalCalls.mask(content);
            List<NavigationCandidate> natural = new ArrayList<>();
            Map<String, CodeLexicalCalls.CallSite> uniqueCalls = new LinkedHashMap<>();
            for (CodeLexicalCalls.CallSite call : CodeLexicalCalls.scan(content, result.methodName())) {
                uniqueCalls.putIfAbsent(call.symbol().toLowerCase(Locale.ROOT), call);
            }
            for (CodeLexicalCalls.CallSite call : uniqueCalls.values()) {
                natural.add(new NavigationCandidate(
                        sourceIndex, result, CodeNavigationHandle.Kind.CALL,
                        call.symbol(), call.offset()));
            }

            Map<String, NavigationCandidate> uniqueTypes = new LinkedHashMap<>();
            Matcher types = CONSTRUCTED_TYPE.matcher(code);
            while (types.find()) {
                NavigationCandidate candidate = new NavigationCandidate(
                        sourceIndex, result, CodeNavigationHandle.Kind.TYPE, types.group(1), types.start());
                uniqueTypes.putIfAbsent(types.group(1).toLowerCase(Locale.ROOT), candidate);
            }
            natural.addAll(uniqueTypes.values());
            if (EvidenceExtractionSupport.metadataBoolean(result, "graphExpanded")) {
                natural.add(new NavigationCandidate(sourceIndex, result,
                        CodeNavigationHandle.Kind.DEFINITION,
                        EvidenceExtractionSupport.subject(result), 0));
            }
            buckets.add(coverageOrdered(natural));
        }

        List<NavigationCandidate> selectedCandidates = selectPrimaryThenRoundRobin(buckets, remaining);
        selectedCandidates.stream()
                .sorted(Comparator.comparingInt(NavigationCandidate::sourceIndex)
                        .thenComparingInt(NavigationCandidate::offset)
                        .thenComparing(candidate -> candidate.kind().ordinal())
                        .thenComparing(NavigationCandidate::symbol))
                .forEach(candidate -> addHandle(
                        candidate.source(), candidate.kind(), candidate.symbol(), candidate.offset(),
                        items, handles, facts, constraints, signals, signaled));

        return new CodeEvidenceIr(List.copyOf(items.values()), facts, constraints, signals,
                List.copyOf(handles.values()), List.of());
    }

    private List<NavigationCandidate> coverageOrdered(List<NavigationCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        List<NavigationCandidate> ordered = candidates.stream()
                .sorted(Comparator.comparingInt(NavigationCandidate::offset)
                        .thenComparing(candidate -> candidate.kind().ordinal())
                        .thenComparing(NavigationCandidate::symbol))
                .toList();
        List<NavigationCandidate> output = new ArrayList<>();
        for (CodeNavigationHandle.Kind kind : List.of(
                CodeNavigationHandle.Kind.CALL,
                CodeNavigationHandle.Kind.TYPE,
                CodeNavigationHandle.Kind.DEFINITION)) {
            List<NavigationCandidate> sameKind = ordered.stream()
                    .filter(candidate -> candidate.kind() == kind)
                    .toList();
            for (int index : CodeLexicalCalls.coverageOrder(sameKind.size())) {
                output.add(sameKind.get(index));
            }
        }
        return List.copyOf(output);
    }

    private List<NavigationCandidate> selectRoundRobin(
            List<List<NavigationCandidate>> buckets,
            int limit
    ) {
        if (limit <= 0 || buckets == null || buckets.isEmpty()) return List.of();
        int total = buckets.stream().mapToInt(List::size).sum();
        if (total <= limit) {
            return buckets.stream().flatMap(List::stream).toList();
        }
        List<NavigationCandidate> selected = new ArrayList<>();
        for (int depth = 0; selected.size() < limit; depth++) {
            boolean added = false;
            for (List<NavigationCandidate> bucket : buckets) {
                if (depth >= bucket.size()) continue;
                selected.add(bucket.get(depth));
                added = true;
                if (selected.size() >= limit) break;
            }
            if (!added) break;
        }
        return List.copyOf(selected);
    }

    /**
     * A direct callable is the strongest source of its own execution operands. Reserve bounded
     * coverage for the highest-ranked source, then distribute the rest across the other sources.
     * The ordering remains positional and source-ranked; it does not consult project or question
     * vocabulary.
     */
    private List<NavigationCandidate> selectPrimaryThenRoundRobin(
            List<List<NavigationCandidate>> buckets,
            int limit
    ) {
        if (limit <= 0 || buckets == null || buckets.isEmpty()) return List.of();
        LinkedHashMap<String, NavigationCandidate> selected = new LinkedHashMap<>();
        int secondaryReserve = Math.min(
                limit / 3,
                Math.max(0, buckets.size() - 1) * 2);
        int primaryLimit = Math.max(0, limit - secondaryReserve);
        buckets.get(0).stream()
                .limit(Math.min(primaryLimit, MAX_PRIMARY_SOURCE_CANDIDATES))
                .forEach(candidate -> selected.putIfAbsent(candidateKey(candidate), candidate));
        if (selected.size() >= limit) return List.copyOf(selected.values());

        for (NavigationCandidate candidate : selectRoundRobin(buckets, limit)) {
            selected.putIfAbsent(candidateKey(candidate), candidate);
            if (selected.size() >= limit) break;
        }
        return List.copyOf(selected.values());
    }

    private String candidateKey(NavigationCandidate candidate) {
        return candidate.sourceIndex() + "\u001f" + candidate.kind() + "\u001f"
                + candidate.offset() + "\u001f" + candidate.symbol();
    }

    private List<CodeSearchResult> preferredSources(List<CodeSearchResult> evidence) {
        List<CodeSearchResult> safe = evidence == null ? List.of() : evidence;
        Set<String> implementationPaths = safe.stream()
                .filter(this::isExpandableDefinition)
                .map(CodeSearchResult::filePath)
                .filter(path -> path != null && !path.isBlank())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        List<CodeSearchResult> ordered = new ArrayList<>();
        safe.stream().filter(this::isExpandableDefinition).forEach(ordered::add);
        safe.stream()
                .filter(result -> !isExpandableDefinition(result))
                .filter(result -> result.filePath() == null
                        || !implementationPaths.contains(result.filePath()))
                .forEach(ordered::add);
        return List.copyOf(ordered);
    }

    private boolean isExpandableDefinition(CodeSearchResult result) {
        return result != null && result.chunkId() != null
                && result.methodName() != null && !result.methodName().isBlank()
                && result.content() != null && !result.content().isBlank();
    }

    private void addDefinitionHandle(
            CodeSearchResult result,
            Map<String, CodeEvidenceItem> items,
            Map<String, CodeNavigationHandle> handles,
            List<CodeEvidenceConstraint> constraints
    ) {
        CodeNavigationHandle handle = CodeNavigationHandle.of(
                CodeNavigationHandle.Kind.DEFINITION,
                result.filePath(), result.methodName(), result.chunkId(),
                result.lineStart(), result.lineEnd(), CodeEvidenceItem.evidenceId(result));
        if (handles.putIfAbsent(handle.handleId(), handle) != null) return;
        String evidenceId = CodeEvidenceItem.evidenceId(result);
        items.putIfAbsent(evidenceId, new CodeEvidenceItem(evidenceId, result,
                Set.of(CodeEvidenceItem.Kind.DIRECT_SOURCE, CodeEvidenceItem.Kind.NAVIGATION),
                EvidenceExtractionSupport.directSyntaxAuthority(result)));
        constraints.add(new CodeEvidenceConstraint(CodeEvidenceConstraint.Type.NAVIGATION_ONLY,
                handle.handleId(), "A definition chunk is an observed graph seed, not proof of its neighbors."));
    }

    private void addHandle(
            CodeSearchResult result,
            CodeNavigationHandle.Kind kind,
            String symbol,
            int offset,
            Map<String, CodeEvidenceItem> items,
            Map<String, CodeNavigationHandle> handles,
            List<CodeEvidenceFact> facts,
            List<CodeEvidenceConstraint> constraints,
            List<CodeEvidenceSignal> signals,
            Set<String> signaled
    ) {
        String evidenceId = CodeEvidenceItem.evidenceId(result);
        CodeNavigationHandle handle = CodeNavigationHandle.of(kind, result.filePath(), symbol,
                result.chunkId(), EvidenceExtractionSupport.lineAtOffset(result, offset),
                EvidenceExtractionSupport.lineAtOffset(result, offset), evidenceId);
        if (handles.putIfAbsent(handle.handleId(), handle) != null) return;
        items.putIfAbsent(evidenceId, new CodeEvidenceItem(evidenceId, result,
                Set.of(CodeEvidenceItem.Kind.DIRECT_SOURCE, CodeEvidenceItem.Kind.NAVIGATION),
                EvidenceExtractionSupport.directSyntaxAuthority(result)));
        if (kind == CodeNavigationHandle.Kind.CALL || kind == CodeNavigationHandle.Kind.TYPE) {
            String predicate = kind == CodeNavigationHandle.Kind.CALL ? "CALLS_SYMBOL" : "CONSTRUCTS_TYPE";
            facts.add(CodeEvidenceFact.of(
                    evidenceId,
                    EvidenceExtractionSupport.subject(result),
                    predicate,
                    symbol,
                    CodeEvidenceFact.Exactness.NORMALIZED,
                    1.0,
                    EvidenceExtractionSupport.directSyntaxAuthority(result)));
        }
        constraints.add(new CodeEvidenceConstraint(CodeEvidenceConstraint.Type.NAVIGATION_ONLY,
                handle.handleId(), "A navigation handle locates evidence but does not prove behavior by itself."));
        if (signaled.add(evidenceId)) {
            signals.add(new CodeEvidenceSignal(CodeEvidenceSignal.Type.OBSERVED_NAVIGATION,
                    evidenceId, 0.7, "A bounded navigation operand was observed directly in source."));
        }
    }

    private record NavigationCandidate(
            int sourceIndex,
            CodeSearchResult source,
            CodeNavigationHandle.Kind kind,
            String symbol,
            int offset
    ) {
    }
}
