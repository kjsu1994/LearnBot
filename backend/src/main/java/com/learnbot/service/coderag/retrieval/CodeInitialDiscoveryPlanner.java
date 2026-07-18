package com.learnbot.service.coderag.retrieval;

import com.learnbot.service.RagPipelineService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Keeps a direct-read-only initial plan from locking retrieval onto a plausible bootstrap candidate.
 * Synthesized searches are discovery operations only: their results must still pass the normal source-read
 * and claim-evidence validation path before they can support an answer.
 */
public final class CodeInitialDiscoveryPlanner {
    private static final int MAX_QUERY_CHARS = 480;

    public List<RagPipelineService.CodeSearchOperation> augmentDirectReadOnlyPlan(
            String question,
            List<RagPipelineService.CodeEvidenceChecklistItem> checklist,
            List<RagPipelineService.CodeSearchOperation> requestedOperations,
            int maxDiscoveryOperations
    ) {
        List<RagPipelineService.CodeSearchOperation> requested = requestedOperations == null
                ? List.of() : List.copyOf(requestedOperations);
        if (question == null || question.isBlank()
                || checklist == null || checklist.isEmpty()
                || requested.isEmpty()
                || requested.stream().anyMatch(RagPipelineService.CodeSearchOperation::isSearch)
                || maxDiscoveryOperations < 2) {
            return requested;
        }

        int boundedLimit = Math.max(2, Math.min(6, maxDiscoveryOperations));
        List<RagPipelineService.CodeSearchOperation> discovery = new ArrayList<>();
        Set<String> emittedQueries = new LinkedHashSet<>();
        int sequence = 1;
        for (RagPipelineService.CodeEvidenceChecklistItem claim : checklist) {
            if (claim == null || claim.claimId().isBlank() || discovery.size() + 2 > boundedLimit) continue;
            String sourceQuery = sourceVocabularyQuery(claim);
            if (sourceQuery.isBlank()) continue;

            String group = stableGroup(claim, sequence);
            String anchoredQuery = bounded(question.trim() + " " + group);
            if (!emittedQueries.add(normalize(anchoredQuery))) {
                anchoredQuery = bounded(question.trim() + " claim_anchor_" + String.format("%02d", sequence));
                if (!emittedQueries.add(normalize(anchoredQuery))) continue;
            }
            discovery.add(searchOperation(
                    anchoredQuery, group, "initial-claim-anchor-" + sequence, claim.claimId()));

            if (!emittedQueries.add(normalize(sourceQuery))) {
                discovery.remove(discovery.size() - 1);
                sequence++;
                continue;
            }
            discovery.add(searchOperation(
                    sourceQuery, group, "initial-source-discovery-" + sequence, claim.claimId()));
            sequence++;
        }
        if (discovery.isEmpty()) return requested;
        List<RagPipelineService.CodeSearchOperation> augmented = new ArrayList<>(discovery.size() + requested.size());
        augmented.addAll(discovery);
        augmented.addAll(requested);
        return List.copyOf(augmented);
    }

    private RagPipelineService.CodeSearchOperation searchOperation(
            String query,
            String evidenceGroup,
            String operationId,
            String claimId
    ) {
        return new RagPipelineService.CodeSearchOperation(
                "hybrid_search", query, "claim_discovery", evidenceGroup,
                "", "", "", null, null, null, List.of(), "BOTH", null,
                operationId, List.of(claimId), List.of());
    }

    private String sourceVocabularyQuery(RagPipelineService.CodeEvidenceChecklistItem claim) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        add(parts, claim.action());
        add(parts, claim.object());
        add(parts, claim.expectedOutcome());
        add(parts, claim.actor());
        if (parts.size() < 2) add(parts, claim.goal());
        if (parts.isEmpty()) {
            claim.queries().stream().filter(value -> value != null && !value.isBlank())
                    .findFirst().ifPresent(parts::add);
        }
        return bounded(String.join(" ", parts));
    }

    private String stableGroup(RagPipelineService.CodeEvidenceChecklistItem claim, int sequence) {
        String value = claim.evidenceGroup() == null ? "" : claim.evidenceGroup().trim();
        if (value.isBlank() || "unknown".equalsIgnoreCase(value)) value = claim.claimId();
        value = value.replaceAll("[^\\p{L}\\p{N}_]+", "_");
        return value.isBlank() ? "claim_anchor_" + String.format("%02d", sequence) : value;
    }

    private void add(Set<String> parts, String value) {
        if (value != null && !value.isBlank()) parts.add(value.trim());
    }

    private String bounded(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_QUERY_CHARS
                ? normalized : normalized.substring(0, MAX_QUERY_CHARS).trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
