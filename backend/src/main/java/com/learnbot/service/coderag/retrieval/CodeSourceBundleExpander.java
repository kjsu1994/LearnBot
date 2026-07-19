package com.learnbot.service.coderag.retrieval;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.repository.CodeRepository;
import com.learnbot.service.GraphSearchIntent;
import com.learnbot.service.RagPipelineService;
import com.learnbot.service.coderag.evidence.CodeLexicalEvidenceSelector;
import com.learnbot.service.coderag.model.CodeEvidenceItem;
import com.learnbot.service.coderag.model.CodeEvidenceOperationProvenance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Adds a bounded, intent-ranked callable view for a structurally anchored search source. */
final class CodeSourceBundleExpander {
    private static final int SEARCH_HEAD_SIZE = 3;
    private static final int MAX_MEMBERS = 2;
    private static final int MAX_BOUNDARIES = 2;
    private static final int SYMBOL_INVENTORY_LIMIT = 80;

    private final CodeRepository repository;

    CodeSourceBundleExpander(CodeRepository repository) {
        this.repository = repository;
    }

    List<CodeSearchResult> expand(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            RagPipelineService.CodeSearchOperation operation,
            GraphSearchIntent graphIntent,
            List<CodeSearchResult> searchResults,
            String retrievalIntent
    ) {
        if (repository == null || operation == null || !operation.isSearch()
                || !hasTypedClaimBinding(operation)
                || searchResults == null || searchResults.isEmpty()) {
            return List.of();
        }
        boolean canRankMembers = retrievalIntent != null && !retrievalIntent.isBlank();
        if (!canRankMembers && !supportsSourceBoundary(graphIntent)) return List.of();

        List<RankedSource> head = java.util.stream.IntStream.range(
                        0, Math.min(SEARCH_HEAD_SIZE, searchResults.size()))
                .mapToObj(index -> new RankedSource(index, searchResults.get(index)))
                .filter(value -> isSourceAnchor(value.result()))
                .toList();
        return expandAnchors(
                repositoryId, selectedSpaceId, spaceIds, operation, graphIntent,
                searchResults, retrievalIntent, head, true);
    }

    /** Exposes only lexically supported members from a directly read class/constructor container. */
    List<CodeSearchResult> expandDirectContainer(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            RagPipelineService.CodeSearchOperation operation,
            GraphSearchIntent graphIntent,
            List<CodeSearchResult> directResults,
            String retrievalIntent
    ) {
        if (repository == null || operation == null || !isContainerReadOperation(operation)
                || !hasTypedClaimBinding(operation)
                || retrievalIntent == null || retrievalIntent.isBlank()
                || directResults == null || directResults.isEmpty()) {
            return List.of();
        }
        List<RankedSource> containers = java.util.stream.IntStream.range(
                        0, Math.min(SEARCH_HEAD_SIZE, directResults.size()))
                .mapToObj(index -> new RankedSource(index, directResults.get(index)))
                .filter(value -> isContainerAnchor(value.result()))
                .toList();
        return expandAnchors(
                repositoryId, selectedSpaceId, spaceIds, operation, graphIntent,
                directResults, retrievalIntent, containers, false);
    }

    private List<CodeSearchResult> expandAnchors(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            RagPipelineService.CodeSearchOperation operation,
            GraphSearchIntent graphIntent,
            List<CodeSearchResult> observedResults,
            String retrievalIntent,
            List<RankedSource> head,
            boolean includeBoundaries
    ) {
        if (head == null || head.isEmpty()) return List.of();
        boolean canRankMembers = retrievalIntent != null && !retrievalIntent.isBlank();
        SourceFamily family = sourceFamily(head);
        if (family == null) return List.of();

        List<CodeSearchResult> inventory = repository.listActiveSymbolsByPath(
                repositoryId, family.path(), SYMBOL_INVENTORY_LIMIT, spaceIds, selectedSpaceId);
        if (inventory == null || inventory.isEmpty()) return List.of();

        List<CodeSearchResult> siblings = inventory.stream()
                .filter(CodeSourceBundleExpander::isCallable)
                .filter(result -> sameType(family.className(), result.className()))
                .filter(result -> !isConstructor(result))
                .toList();
        if (siblings.isEmpty()) return List.of();

        List<String> origins = family.sources().stream()
                .map(RankedSource::result)
                .map(CodeEvidenceItem::evidenceId)
                .distinct()
                .toList();
        List<CodeSearchResult> output = new ArrayList<>();
        if (canRankMembers) {
            List<CodeSearchResult> matchedSiblings = siblings.stream()
                    .filter(result -> CodeLexicalEvidenceSelector.hasCallableLexicalMatch(
                            retrievalIntent, operation.query(), result))
                    .toList();
            List<CodeSearchResult> members = CodeLexicalEvidenceSelector.rank(
                    retrievalIntent, operation.query(), matchedSiblings, MAX_MEMBERS);
            for (int index = 0; index < members.size(); index++) {
                output.add(mark(members.get(index), operation, origins,
                        "read_source_member", index + 1));
            }
        }

        if (includeBoundaries && family.cohesive() && supportsSourceBoundary(graphIntent)) {
            // Mark the actual callable boundaries even when search already observed them. The executor
            // merges provenance by chunk identity, so an observed first/last member can gain the typed
            // boundary signal instead of disappearing merely because it was found early.
            List<CodeSearchResult> ordered = siblings.stream()
                    .sorted(Comparator.comparingInt(CodeSearchResult::lineStart)
                            .thenComparingInt(CodeSearchResult::lineEnd)
                            .thenComparing(CodeSearchResult::methodName))
                    .toList();
            if (!ordered.isEmpty()) {
                output.add(mark(ordered.get(0), operation, origins,
                        "read_source_boundary", 1));
                if (ordered.size() > 1 && MAX_BOUNDARIES > 1) {
                    output.add(mark(ordered.get(ordered.size() - 1), operation, origins,
                            "read_source_boundary", 2));
                }
            }
        }
        return List.copyOf(output);
    }

    private SourceFamily sourceFamily(List<RankedSource> head) {
        Map<String, List<RankedSource>> groups = new LinkedHashMap<>();
        for (RankedSource value : head) {
            if (!isCallable(value.result())) continue;
            String key = normalizePath(value.result().filePath()) + "\u001f"
                    + normalize(value.result().className());
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
        SourceFamily cohesive = groups.values().stream()
                .filter(values -> values.size() >= 2)
                .filter(values -> values.stream().map(value -> normalize(value.result().methodName()))
                        .filter(method -> !method.isBlank()).distinct().count() >= 2)
                .min(Comparator.comparingInt(values -> values.get(0).rank()))
                .map(values -> new SourceFamily(
                        canonicalPath(values.get(0).result().filePath()),
                        values.get(0).result().className(), List.copyOf(values), true))
                .orElse(null);
        if (cohesive != null) return cohesive;

        return head.stream().findFirst()
                .map(value -> new SourceFamily(
                        canonicalPath(value.result().filePath()),
                        value.result().className(), List.of(value), false))
                .orElse(null);
    }

    private CodeSearchResult mark(
            CodeSearchResult result,
            RagPipelineService.CodeSearchOperation operation,
            List<String> origins,
            String operationType,
            int sourceRank
    ) {
        CodeEvidenceOperationProvenance provenance = new CodeEvidenceOperationProvenance(
                operationType,
                operation.operationId(),
                operation.claimIds(), operation.evidenceGroup(), origins, "",
                result.filePath(), result.methodName(),
                result.chunkId() == null ? "" : result.chunkId().toString(),
                result.lineStart(), result.lineEnd(), null, List.of(), "BOTH", null,
                sourceRank);
        Map<String, Object> metadata = new LinkedHashMap<>(
                result.metadata() == null ? Map.of() : result.metadata());
        metadata.put(CodeEvidenceOperationProvenance.METADATA_KEY,
                CodeEvidenceOperationProvenance.merge(
                        metadata.get(CodeEvidenceOperationProvenance.METADATA_KEY), provenance));
        return new CodeSearchResult(
                result.chunkId(), result.repositoryId(), result.fileId(), result.repositoryName(),
                result.filePath(), result.chunkType(), result.symbolName(), result.className(),
                result.methodName(), result.namespaceName(), result.controlName(), result.eventName(),
                result.chunkIndex(), result.lineStart(), result.lineEnd(), result.content(),
                result.score(), Map.copyOf(metadata));
    }

    private static boolean supportsSourceBoundary(GraphSearchIntent intent) {
        return intent == GraphSearchIntent.FLOW
                || intent == GraphSearchIntent.IMPACT
                || intent == GraphSearchIntent.UI_EVENT;
    }

    private static boolean hasTypedClaimBinding(RagPipelineService.CodeSearchOperation operation) {
        return operation != null
                && operation.operationId() != null && !operation.operationId().isBlank()
                && operation.claimIds() != null && !operation.claimIds().isEmpty()
                && operation.evidenceGroup() != null && !operation.evidenceGroup().isBlank();
    }

    private static boolean isContainerReadOperation(RagPipelineService.CodeSearchOperation operation) {
        if (operation == null) return false;
        return switch (operation.type()) {
            case "read_chunk", "read_symbol", "read_file_range" -> true;
            default -> false;
        };
    }

    private static boolean isSourceAnchor(CodeSearchResult result) {
        return result != null && result.chunkId() != null
                && result.filePath() != null && !result.filePath().isBlank()
                && result.className() != null && !result.className().isBlank()
                && result.content() != null && !result.content().isBlank();
    }

    private static boolean isContainerAnchor(CodeSearchResult result) {
        if (!isSourceAnchor(result)) return false;
        return "class".equalsIgnoreCase(result.chunkType()) || isConstructor(result);
    }

    private static boolean isCallable(CodeSearchResult result) {
        return isSourceAnchor(result)
                && result.methodName() != null && !result.methodName().isBlank();
    }

    private static boolean sameType(String expected, String actual) {
        String normalizedExpected = normalize(expected);
        return normalizedExpected.isBlank() || normalizedExpected.equals(normalize(actual));
    }

    private static boolean isConstructor(CodeSearchResult result) {
        String method = normalize(result.methodName());
        String type = normalize(result.className());
        return !method.isBlank() && method.equals(type);
    }

    private static String normalizePath(String value) {
        return canonicalPath(value).toLowerCase(Locale.ROOT);
    }

    private static String canonicalPath(String value) {
        String canonical = value == null ? "" : value.trim().replace('\\', '/');
        while (canonical.startsWith("./")) canonical = canonical.substring(2);
        return canonical;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record RankedSource(int rank, CodeSearchResult result) {
    }

    private record SourceFamily(
            String path,
            String className,
            List<RankedSource> sources,
            boolean cohesive
    ) {
    }
}
