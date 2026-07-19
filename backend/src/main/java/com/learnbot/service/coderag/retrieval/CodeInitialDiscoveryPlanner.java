package com.learnbot.service.coderag.retrieval;

import com.learnbot.service.RagPipelineService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    /**
     * Repairs only search operations rejected by the initial question-anchor contract. The original
     * searches remain hypotheses and must pass the normal companion-alignment check after a bounded
     * claim-specific question anchor is added; this method never makes a drifted query executable by
     * itself.
     */
    public List<RagPipelineService.CodeSearchOperation> repairRejectedSearchAnchors(
            String question,
            List<RagPipelineService.CodeEvidenceChecklistItem> checklist,
            List<RagPipelineService.CodeSearchOperation> requestedOperations,
            Set<String> rejectedOperationIds,
            int maxAnchorOperations
    ) {
        List<RagPipelineService.CodeSearchOperation> requested = requestedOperations == null
                ? List.of() : List.copyOf(requestedOperations);
        if (question == null || question.isBlank()
                || checklist == null || checklist.isEmpty()
                || requested.isEmpty()
                || rejectedOperationIds == null || rejectedOperationIds.isEmpty()
                || maxAnchorOperations <= 0) {
            return requested;
        }

        Set<String> rejected = rejectedOperationIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        LinkedHashSet<String> rejectedClaims = requested.stream()
                .filter(RagPipelineService.CodeSearchOperation::isSearch)
                .filter(operation -> rejected.contains(operation.operationId()))
                .flatMap(operation -> operation.claimIds().stream())
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (rejectedClaims.isEmpty()) return requested;

        Map<String, RagPipelineService.CodeEvidenceChecklistItem> claims = checklist.stream()
                .filter(java.util.Objects::nonNull)
                .filter(claim -> claim.claimId() != null && !claim.claimId().isBlank())
                .collect(Collectors.toMap(
                        RagPipelineService.CodeEvidenceChecklistItem::claimId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));
        Set<String> emittedQueries = requested.stream()
                .filter(RagPipelineService.CodeSearchOperation::isSearch)
                .map(RagPipelineService.CodeSearchOperation::query)
                .map(this::normalize)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> operationIds = requested.stream()
                .map(RagPipelineService.CodeSearchOperation::operationId)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<RagPipelineService.CodeSearchOperation> anchors = new ArrayList<>();
        int sequence = 1;
        for (String claimId : rejectedClaims) {
            if (anchors.size() >= Math.min(4, maxAnchorOperations)) break;
            RagPipelineService.CodeEvidenceChecklistItem claim = claims.get(claimId);
            if (claim == null) continue;
            String group = stableGroup(claim, sequence);
            String query = bounded(question.trim() + " " + group);
            if (!emittedQueries.add(normalize(query))) {
                query = bounded(question.trim() + " claim_anchor_repair_" + String.format("%02d", sequence));
                if (!emittedQueries.add(normalize(query))) {
                    sequence++;
                    continue;
                }
            }
            String operationId = uniqueOperationId(operationIds, "initial-repair-anchor-" + sequence);
            anchors.add(searchOperation(query, group, operationId, claimId));
            sequence++;
        }
        if (anchors.isEmpty()) return requested;
        List<RagPipelineService.CodeSearchOperation> repaired = new ArrayList<>(anchors.size() + requested.size());
        repaired.addAll(anchors);
        repaired.addAll(requested);
        return List.copyOf(repaired);
    }

    private String uniqueOperationId(Set<String> used, String base) {
        String candidate = base;
        int suffix = 2;
        while (!used.add(candidate)) candidate = base + "-" + suffix++;
        return candidate;
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
