package com.learnbot.service.coderag.retrieval;

import com.learnbot.service.RagPipelineService;
import com.learnbot.service.coderag.model.CodeQuestionMode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Adds one bounded semantic-graph closure to an exact read selected for a call-flow claim.
 *
 * <p>The policy is deliberately structural: it uses only the routed question mode, typed read
 * operands, active-index symbol identities, and relation handles. It does not inspect repository names, paths,
 * frameworks, benchmark cases, or question vocabulary. One bidirectional {@code CALLS}
 * traversal at at most two hops exposes a short caller/callee chain without turning every
 * direct read into an unbounded graph walk.</p>
 */
public final class CodeGraphClosurePlanner {
    private static final String CALLS = "CALLS";
    private static final String BOTH = "BOTH";

    public RagPipelineService.CodeEvidenceFollowUpPlan augment(
            CodeQuestionMode questionMode,
            RagPipelineService.CodeEvidenceFollowUpPlan plan,
            RepositoryQuestionMapBuilder.RepositoryQuestionMap repositoryMap,
            Set<String> executedOperationKeys
    ) {
        if (questionMode != CodeQuestionMode.CALL_FLOW || plan == null || plan.enough()
                || repositoryMap == null || plan.operations().isEmpty()) {
            return plan;
        }

        Set<String> attempted = executedOperationKeys == null ? Set.of() : executedOperationKeys;
        LinkedHashSet<String> scheduled = plan.operations().stream()
                .map(CodeRetrievalCoordinator::operationKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<SeedCandidate> candidates = new ArrayList<>();
        for (int operationIndex = 0; operationIndex < plan.operations().size(); operationIndex++) {
            RagPipelineService.CodeSearchOperation operation = plan.operations().get(operationIndex);
            if (!isExactCallableRead(operation)) continue;
            Map<UUID, List<RepositoryQuestionMapBuilder.RelationEvidence>> bySeed = new LinkedHashMap<>();
            repositoryMap.relations().stream()
                    .filter(this::isReadableCall)
                    .filter(relation -> matchesRead(operation, relation))
                    .forEach(relation -> bySeed.computeIfAbsent(
                            relation.fromChunkId(), ignored -> new ArrayList<>()).add(relation));
            int sourceOrder = operationIndex;
            bySeed.forEach((seed, relations) -> candidates.add(
                    new SeedCandidate(sourceOrder, operation, seed, List.copyOf(relations),
                            relations.get(0).sourceEvidenceId())));
            observedSeed(operation, repositoryMap).ifPresent(seed -> {
                if (!bySeed.containsKey(seed)) {
                    observedOrigin(operation, repositoryMap).ifPresent(origin -> candidates.add(
                            new SeedCandidate(sourceOrder, operation, seed, List.of(), origin)));
                }
            });
        }

        SeedCandidate selected = candidates.stream()
                .sorted(Comparator.comparingInt((SeedCandidate candidate) -> candidate.relations().size()).reversed()
                        .thenComparingInt(SeedCandidate::sourceOrder)
                        .thenComparing(candidate -> candidate.seedChunkId().toString()))
                .filter(candidate -> !alreadyScheduled(candidate.seedChunkId(), attempted, scheduled))
                .findFirst()
                .orElse(null);
        if (selected == null) return plan;

        RagPipelineService.CodeSearchOperation source = selected.sourceOperation();
        String operationId = source.operationId().isBlank()
                ? "graph-closure-1" : source.operationId() + "-graph-closure";
        RagPipelineService.CodeSearchOperation closure = new RagPipelineService.CodeSearchOperation(
                "traverse_graph", "", source.area(), source.evidenceGroup(),
                "", "", selected.seedChunkId().toString(), null, null, null,
                List.of(CALLS), BOTH, 2, operationId,
                source.claimIds(), List.of(selected.originEvidenceId()));

        List<RagPipelineService.CodeSearchOperation> operations = new ArrayList<>(plan.operations());
        operations.add(closure);
        return new RagPipelineService.CodeEvidenceFollowUpPlan(
                plan.attempted(), false, appendReason(plan.reason()), plan.missingAreas(),
                plan.followUpQueries(), plan.queryAreas(), plan.requiredEvidenceGroups(), plan.checklist(),
                List.copyOf(operations), plan.coverageSelections(), plan.hypothesis(),
                plan.hypothesisVersion(), plan.premiseDisposition(), plan.claimResults(),
                plan.terminationRequest());
    }

    private boolean isExactCallableRead(RagPipelineService.CodeSearchOperation operation) {
        if (operation == null) return false;
        return "read_symbol".equals(operation.type())
                || "read_chunk".equals(operation.type())
                || "read_file_range".equals(operation.type()) && !operation.symbol().isBlank();
    }

    private boolean isReadableCall(RepositoryQuestionMapBuilder.RelationEvidence relation) {
        return relation != null && CALLS.equals(normalize(relation.type()))
                && Set.of("FORWARD", "REVERSE", BOTH).contains(normalize(relation.direction()))
                && relation.fromChunkId() != null && relation.toChunkId() != null
                && !relation.sourceEvidenceId().isBlank();
    }

    private java.util.Optional<UUID> observedSeed(
            RagPipelineService.CodeSearchOperation operation,
            RepositoryQuestionMapBuilder.RepositoryQuestionMap repositoryMap
    ) {
        if ("read_chunk".equals(operation.type())) {
            try {
                UUID chunkId = UUID.fromString(operation.chunkId());
                return repositoryMap.observesChunk(operation.chunkId())
                        ? java.util.Optional.of(chunkId) : java.util.Optional.empty();
            } catch (IllegalArgumentException ignored) {
                return java.util.Optional.empty();
            }
        }
        return repositoryMap.uniqueObservedChunkForSymbol(operation.path(), operation.symbol());
    }

    private java.util.Optional<String> observedOrigin(
            RagPipelineService.CodeSearchOperation operation,
            RepositoryQuestionMapBuilder.RepositoryQuestionMap repositoryMap
    ) {
        return operation.originEvidenceIds().stream()
                .filter(repositoryMap::containsEvidenceId)
                .filter(origin -> operation.path().isBlank()
                        || repositoryMap.originSupportsPath(origin, operation.path()))
                .findFirst();
    }

    private boolean matchesRead(
            RagPipelineService.CodeSearchOperation operation,
            RepositoryQuestionMapBuilder.RelationEvidence relation
    ) {
        if ("read_chunk".equals(operation.type())) {
            return operation.chunkId().equals(relation.fromChunkId().toString());
        }
        if (!operation.path().isBlank() && !operation.path().equals(relation.fromPath())) return false;
        return sameSymbol(operation.symbol(), relation.from())
                || operation.originEvidenceIds().contains(relation.sourceEvidenceId());
    }

    private boolean alreadyScheduled(
            UUID seedChunkId,
            Set<String> attempted,
            Set<String> scheduled
    ) {
        String prefix = "traverse_graph|" + seedChunkId + "|" + CALLS + "|" + BOTH + "|";
        return attempted.stream().anyMatch(key -> key.startsWith(prefix))
                || scheduled.stream().anyMatch(key -> key.startsWith(prefix));
    }

    private boolean sameSymbol(String left, String right) {
        String leftCanonical = canonicalSymbol(left);
        String rightCanonical = canonicalSymbol(right);
        return !leftCanonical.isBlank() && leftCanonical.equalsIgnoreCase(rightCanonical);
    }

    private String canonicalSymbol(String value) {
        String symbol = value == null ? "" : value.trim();
        int parameters = symbol.indexOf('(');
        if (parameters >= 0) symbol = symbol.substring(0, parameters);
        symbol = symbol.replace("::", ".").replace('#', '.');
        int separator = symbol.lastIndexOf('.');
        return separator >= 0 ? symbol.substring(separator + 1) : symbol;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String appendReason(String reason) {
        String base = reason == null ? "" : reason.trim();
        String suffix = "active call graph closure added for an exact flow read";
        return base.isBlank() ? suffix : base + "; " + suffix;
    }

    private record SeedCandidate(
            int sourceOrder,
            RagPipelineService.CodeSearchOperation sourceOperation,
            UUID seedChunkId,
            List<RepositoryQuestionMapBuilder.RelationEvidence> relations,
            String originEvidenceId
    ) {
    }
}
