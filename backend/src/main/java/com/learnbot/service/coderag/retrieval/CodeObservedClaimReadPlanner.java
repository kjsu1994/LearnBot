package com.learnbot.service.coderag.retrieval;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.RagPipelineService;
import com.learnbot.service.coderag.model.CodeEvidenceOperationProvenance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Produces a final bounded exact-read lane from implementation candidates already observed for
 * unresolved claims. It cannot widen retrieval: only active-map chunks exposed by a claim-linked
 * search are eligible, and every result is still validated by the normal retrieval contract.
 */
public final class CodeObservedClaimReadPlanner {
    static final int MAX_READS = 2;

    public List<RagPipelineService.CodeSearchOperation> select(
            RagPipelineService.CodeEvidenceFollowUpPlan plan,
            RepositoryQuestionMapBuilder.RepositoryQuestionMap repositoryMap,
            Set<String> executedOperationKeys
    ) {
        if (plan == null || repositoryMap == null || plan.enough()) return List.of();
        Set<String> resolvedClaims = plan.claimResults().stream()
                .filter(RagPipelineService.CodeClaimResult::terminalWithEvidence)
                .map(RagPipelineService.CodeClaimResult::claimId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> executed = executedOperationKeys == null ? Set.of() : Set.copyOf(executedOperationKeys);
        LinkedHashSet<UUID> selectedChunks = new LinkedHashSet<>();
        List<RagPipelineService.CodeSearchOperation> selected = new ArrayList<>();

        for (RagPipelineService.CodeEvidenceChecklistItem claim : plan.checklist()) {
            if (selected.size() >= MAX_READS) break;
            if (claim.claimId().isBlank() || resolvedClaims.contains(claim.claimId())) continue;
            Candidate candidate = candidatesFor(claim, repositoryMap.evidence()).stream()
                    .filter(value -> !selectedChunks.contains(value.entry().chunkId()))
                    .filter(value -> isUntried(value, claim, repositoryMap, executed))
                    .findFirst()
                    .orElse(null);
            if (candidate == null) continue;
            RagPipelineService.CodeSearchOperation operation = operation(
                    candidate, claim, selected.size() + 1);
            selected.add(operation);
            selectedChunks.add(candidate.entry().chunkId());
        }
        return List.copyOf(selected);
    }

    private List<Candidate> candidatesFor(
            RagPipelineService.CodeEvidenceChecklistItem claim,
            Map<String, RepositoryQuestionMapBuilder.EvidenceEntry> evidence
    ) {
        Set<String> intent = claimIntent(claim);
        if (intent.isEmpty() || evidence == null || evidence.isEmpty()) return List.of();
        return evidence.values().stream()
                .filter(this::isConcreteSearchImplementation)
                .map(entry -> candidate(entry, claim.claimId(), intent))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator
                        .comparingInt(Candidate::tokenMatches).reversed()
                        .thenComparingInt(Candidate::searchRank)
                        .thenComparing(Comparator.comparingDouble(
                                (Candidate value) -> value.entry().score()).reversed())
                        .thenComparing(value -> value.entry().path())
                        .thenComparingInt(value -> value.entry().lineStart())
                        .thenComparing(value -> value.entry().evidenceId()))
                .toList();
    }

    private Candidate candidate(
            RepositoryQuestionMapBuilder.EvidenceEntry entry,
            String claimId,
            Set<String> intent
    ) {
        List<CodeEvidenceOperationProvenance> linkedSearches =
                CodeEvidenceOperationProvenance.from(entry.source()).stream()
                        .filter(CodeEvidenceOperationProvenance::isSearchOperation)
                        .filter(provenance -> provenance.claimIds().contains(claimId))
                        .toList();
        if (linkedSearches.isEmpty()) return null;
        Set<String> identity = CodeRetrievalPlanValidator.distinctiveTokens(String.join(" ",
                safe(entry.symbol()), safe(entry.source().className()),
                safe(entry.source().methodName()), safe(entry.source().symbolName())));
        int matches = CodeRetrievalPlanValidator.matchedTargetTokenCount(identity, intent);
        if (matches <= 0) return null;
        int searchRank = linkedSearches.stream()
                .map(CodeEvidenceOperationProvenance::resultRank)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .min()
                .orElse(Integer.MAX_VALUE);
        return new Candidate(entry, matches, searchRank);
    }

    private Set<String> claimIntent(RagPipelineService.CodeEvidenceChecklistItem claim) {
        LinkedHashSet<String> intent = new LinkedHashSet<>(
                CodeRetrievalPlanValidator.claimBehaviorTokens(claim));
        claim.queries().forEach(query -> intent.addAll(
                CodeRetrievalPlanValidator.distinctiveTokens(query)));
        return Set.copyOf(intent);
    }

    private boolean isConcreteSearchImplementation(RepositoryQuestionMapBuilder.EvidenceEntry entry) {
        if (entry == null || entry.chunkId() == null || entry.source() == null
                || !"IMPLEMENTATION_BODY".equals(entry.kind())
                || !"DIRECT_SOURCE".equals(entry.authority())) return false;
        CodeSearchResult source = entry.source();
        String callable = firstNonBlank(source.methodName(), source.symbolName());
        if (callable.isBlank()) return false;
        Object declaredBody = source.metadata() == null
                ? null : source.metadata().get("callableBodyPresent");
        if (declaredBody != null) return Boolean.parseBoolean(String.valueOf(declaredBody));
        String content = source.content() == null ? "" : source.content();
        return content.contains("{") || content.contains("=>");
    }

    private boolean isUntried(
            Candidate candidate,
            RagPipelineService.CodeEvidenceChecklistItem claim,
            RepositoryQuestionMapBuilder.RepositoryQuestionMap repositoryMap,
            Set<String> executed
    ) {
        RagPipelineService.CodeSearchOperation operation = operation(candidate, claim, 1);
        String key = CodeRetrievalCoordinator.operationKey(operation);
        return !executed.contains(key)
                && !repositoryMap.hasExecutedEquivalentRead(operation, executed);
    }

    private RagPipelineService.CodeSearchOperation operation(
            Candidate candidate,
            RagPipelineService.CodeEvidenceChecklistItem claim,
            int ordinal
    ) {
        return new RagPipelineService.CodeSearchOperation(
                "read_chunk", "", claim.goal(), claim.evidenceGroup(),
                "", "", candidate.entry().chunkId().toString(),
                null, null, null, List.of(), "BOTH", null,
                "observed-claim-read-" + ordinal,
                List.of(claim.claimId()), List.of(candidate.entry().evidenceId()));
    }

    private String firstNonBlank(String... values) {
        for (String value : values == null ? new String[0] : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record Candidate(
            RepositoryQuestionMapBuilder.EvidenceEntry entry,
            int tokenMatches,
            int searchRank
    ) {
    }
}
