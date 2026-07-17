package com.learnbot.service.coderag.evidence;

import com.learnbot.dto.CodeSearchResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Keeps stronger provenance from being discarded by a purely score-based top-N cut. */
public final class CodeEvidenceSelectionPolicy {
    private static final List<String> EVIDENCE_GROUP_KEYS = List.of(
            "llmValidatedEvidenceGroup",
            "llmReadEvidenceGroup",
            "llmEvidenceCoverageGroup",
            "llmChecklistGroup"
    );
    private static final int UNPRIORITIZED = 4;
    private static final int MAX_GROUP_REPRESENTATIVES = 2;
    private static final int MAX_GROUPLESS_REPRESENTATIVES_PER_TYPE = 1;
    private static final int MAX_PREFERRED_REPRESENTATIVES = 3;
    // Typed facts must not crowd out the semantic slate; three slots still allow a
    // transition split across two chunks plus one independently constrained fact.
    private static final int MAX_TYPED_FACT_SOURCE_REPRESENTATIVES = 3;

    private CodeEvidenceSelectionPolicy() {
    }

    public static List<CodeSearchResult> select(List<CodeSearchResult> ranked, int requestedLimit) {
        return select(ranked, requestedLimit, CodeEvidenceRetentionPlan.empty(), true);
    }

    /**
     * Selects bounded membership from typed retention intent. This path does not
     * infer retention from mutable result metadata.
     */
    public static List<CodeSearchResult> select(
            List<CodeSearchResult> ranked,
            int requestedLimit,
            CodeEvidenceRetentionPlan retentionPlan
    ) {
        return select(ranked, requestedLimit, retentionPlan, false);
    }

    private static List<CodeSearchResult> select(
            List<CodeSearchResult> ranked,
            int requestedLimit,
            CodeEvidenceRetentionPlan retentionPlan,
            boolean legacyProvenance
    ) {
        if (ranked == null || ranked.isEmpty() || requestedLimit <= 0) return List.of();

        List<Candidate> candidates = uniqueCandidates(ranked, legacyProvenance);
        if (candidates.isEmpty()) return List.of();

        int limit = Math.min(requestedLimit, candidates.size());
        List<Candidate> provenanceBacked = legacyProvenance
                ? protectedCandidates(candidates, limit, ignored -> false, Set.of())
                : retainedCandidates(candidates, limit, retentionPlan);

        Set<UUID> selected = new LinkedHashSet<>();
        for (Candidate candidate : provenanceBacked) {
            if (selected.size() >= limit) break;
            selected.add(candidate.result().chunkId());
        }
        for (Candidate candidate : candidates) {
            if (selected.size() >= limit) break;
            selected.add(candidate.result().chunkId());
        }

        // Preserve the ranker's order; provenance changes membership, not presentation order.
        return candidates.stream()
                .filter(candidate -> selected.contains(candidate.result().chunkId()))
                .map(Candidate::result)
                .limit(limit)
                .toList();
    }

    /**
     * Applies provenance membership and file diversity at the final answer boundary.
     * Provenance changes membership without replacing the ranker's semantic order.
     * Grouped evidence keeps at most two complementary chunks per group, while
     * groupless evidence keeps one representative per provenance type.
     */
    public static List<CodeSearchResult> selectFinalEvidence(
            List<CodeSearchResult> ranked,
            List<CodeSearchResult> selected,
            int requestedLimit,
            Predicate<CodeSearchResult> required
    ) {
        return selectFinalEvidence(ranked, selected, requestedLimit, required, Set.of());
    }

    /**
     * Final selection with bounded protection for sources of question-relevant typed facts.
     * Membership may change, but the ranker's semantic order and the requested hard limit do not.
     */
    public static List<CodeSearchResult> selectFinalEvidence(
            List<CodeSearchResult> ranked,
            List<CodeSearchResult> selected,
            int requestedLimit,
            Predicate<CodeSearchResult> required,
            Set<String> typedFactSourceEvidenceIds
    ) {
        return selectFinalEvidence(ranked, selected, requestedLimit, required,
                typedFactSourceEvidenceIds, CodeEvidenceRetentionPlan.empty(), true);
    }

    /**
     * Final evidence selection driven exclusively by a typed retention plan.
     * The plan is placed before the limit to keep this overload unambiguous with
     * the legacy predicate signature when callers pass {@code null}.
     */
    public static List<CodeSearchResult> selectFinalEvidence(
            List<CodeSearchResult> ranked,
            List<CodeSearchResult> selected,
            CodeEvidenceRetentionPlan retentionPlan,
            int requestedLimit
    ) {
        return selectFinalEvidence(ranked, selected, requestedLimit, ignored -> false, Set.of(),
                retentionPlan, false);
    }

    public static List<CodeSearchResult> selectFinalEvidenceWithRetention(
            List<CodeSearchResult> ranked,
            List<CodeSearchResult> selected,
            int requestedLimit,
            CodeEvidenceRetentionPlan retentionPlan
    ) {
        return selectFinalEvidence(ranked, selected, retentionPlan, requestedLimit);
    }

    private static List<CodeSearchResult> selectFinalEvidence(
            List<CodeSearchResult> ranked,
            List<CodeSearchResult> selected,
            int requestedLimit,
            Predicate<CodeSearchResult> required,
            Set<String> typedFactSourceEvidenceIds,
            CodeEvidenceRetentionPlan retentionPlan,
            boolean legacyProvenance
    ) {
        if (requestedLimit <= 0) return List.of();

        List<CodeSearchResult> pool = uniqueResults(selected, ranked);
        if (pool.isEmpty()) return List.of();

        int limit = Math.min(requestedLimit, pool.size());
        List<CodeSearchResult> semanticOrder = uniqueResults(ranked, selected);
        List<CodeSearchResult> semanticPool = restoreSemanticOrder(semanticOrder, pool, pool.size());
        List<Candidate> candidates = uniqueCandidates(semanticPool, legacyProvenance);
        Predicate<CodeSearchResult> externallyRequired = required == null ? ignored -> false : required;
        Set<String> typedSources = typedFactSourceEvidenceIds == null
                ? Set.of() : Set.copyOf(typedFactSourceEvidenceIds);
        List<Candidate> retained = legacyProvenance
                ? protectedCandidates(candidates, limit, externallyRequired, typedSources)
                : retainedCandidates(candidates, limit, retentionPlan);
        List<CodeSearchResult> protectedEvidence = retained.stream()
                .map(Candidate::result)
                .toList();
        Set<UUID> protectedIds = protectedEvidence.stream()
                .map(CodeSearchResult::chunkId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        List<CodeSearchResult> ordered = new ArrayList<>();
        addUnique(ordered, protectedEvidence);
        addUnique(ordered, selected);
        addUnique(ordered, select(semanticPool, limit,
                legacyProvenance ? CodeEvidenceRetentionPlan.empty() : retentionPlan,
                legacyProvenance));

        List<CodeSearchResult> membership = CodeEvidenceFileDiversity.select(
                semanticPool,
                ordered,
                limit,
                result -> protectedIds.contains(result.chunkId())
        );
        return restoreSemanticOrder(semanticOrder, membership, limit);
    }

    private static List<Candidate> retainedCandidates(
            List<Candidate> candidates,
            int limit,
            CodeEvidenceRetentionPlan retentionPlan
    ) {
        CodeEvidenceRetentionPlan safePlan = retentionPlan == null
                ? CodeEvidenceRetentionPlan.empty() : retentionPlan;
        if (safePlan.isEmpty() || candidates == null || candidates.isEmpty() || limit <= 0) return List.of();

        List<RetainedCandidate> prioritized = candidates.stream()
                .map(candidate -> safePlan.lookup(CodeEvidenceId.from(candidate.result()))
                        .map(entry -> new RetainedCandidate(candidate, entry))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator
                        .comparingInt((RetainedCandidate value) -> retentionRank(value.entry().level()))
                        .thenComparing(Comparator.comparingInt(
                                (RetainedCandidate value) -> value.entry().authority().rank()).reversed())
                        .thenComparingInt(value -> value.candidate().rank()))
                .toList();

        Map<String, Integer> perGroup = new LinkedHashMap<>();
        Map<CodeEvidenceRetentionPlan.Level, Integer> grouplessByLevel = new LinkedHashMap<>();
        List<Candidate> retained = new ArrayList<>();
        int preferred = 0;
        for (RetainedCandidate value : prioritized) {
            if (retained.size() >= limit) break;
            CodeEvidenceRetentionPlan.Entry entry = value.entry();
            if (entry.level() == CodeEvidenceRetentionPlan.Level.PREFERRED
                    && preferred >= MAX_PREFERRED_REPRESENTATIVES) continue;
            if (entry.groups().isEmpty()) {
                int count = grouplessByLevel.getOrDefault(entry.level(), 0);
                if (count >= MAX_GROUPLESS_REPRESENTATIVES_PER_TYPE) continue;
                grouplessByLevel.put(entry.level(), count + 1);
            } else {
                if (entry.groups().stream()
                        .anyMatch(group -> perGroup.getOrDefault(group, 0) >= MAX_GROUP_REPRESENTATIVES)) {
                    continue;
                }
                entry.groups().forEach(group -> perGroup.merge(group, 1, Integer::sum));
            }
            retained.add(value.candidate());
            if (entry.level() == CodeEvidenceRetentionPlan.Level.PREFERRED) preferred++;
        }
        return List.copyOf(retained);
    }

    private static int retentionRank(CodeEvidenceRetentionPlan.Level level) {
        return level == CodeEvidenceRetentionPlan.Level.REQUIRED ? 0 : 1;
    }

    private static List<Candidate> protectedCandidates(
            List<Candidate> candidates,
            int limit,
            Predicate<CodeSearchResult> externallyRequired,
            Set<String> typedFactSourceEvidenceIds
    ) {
        Predicate<CodeSearchResult> safeRequired = externallyRequired == null ? ignored -> false : externallyRequired;
        Set<String> typedSources = typedFactSourceEvidenceIds == null
                ? Set.of() : typedFactSourceEvidenceIds;
        List<Candidate> prioritized = candidates.stream()
                .filter(candidate -> safeRequired.test(candidate.result())
                        || isTypedFactSource(candidate.result(), typedSources)
                        || candidate.provenanceRank() < UNPRIORITIZED)
                .sorted(Comparator
                        .comparingInt((Candidate candidate) -> safeRequired.test(candidate.result()) ? 0 : 1)
                        .thenComparingInt(candidate -> isTypedFactSource(candidate.result(), typedSources) ? 0 : 1)
                        .thenComparingInt(Candidate::provenanceRank)
                        .thenComparingInt(Candidate::rank))
                .toList();
        Map<String, Integer> perGroup = new LinkedHashMap<>();
        Map<String, Integer> grouplessByType = new LinkedHashMap<>();
        List<Candidate> protectedEvidence = new ArrayList<>();
        int typedFactSources = 0;
        for (Candidate candidate : prioritized) {
            if (protectedEvidence.size() >= limit) break;
            boolean externallyRequiredCandidate = safeRequired.test(candidate.result());
            boolean typedFactSource = isTypedFactSource(candidate.result(), typedSources);
            if (typedFactSource && !externallyRequiredCandidate) {
                if (typedFactSources >= MAX_TYPED_FACT_SOURCE_REPRESENTATIVES) continue;
                if (!candidate.groups().isEmpty() && candidate.groups().stream()
                        .allMatch(group -> perGroup.getOrDefault(group, 0) >= MAX_GROUP_REPRESENTATIVES)) {
                    continue;
                }
                candidate.groups().forEach(group -> perGroup.merge(group, 1, Integer::sum));
                protectedEvidence.add(candidate);
                typedFactSources++;
                continue;
            }
            if (candidate.groups().isEmpty()) {
                String bucket = candidate.provenanceType() == ProvenanceType.NONE
                        ? "externally_required"
                        : candidate.provenanceType().name();
                int count = grouplessByType.getOrDefault(bucket, 0);
                if (count >= MAX_GROUPLESS_REPRESENTATIVES_PER_TYPE) continue;
                grouplessByType.put(bucket, count + 1);
                protectedEvidence.add(candidate);
                continue;
            }
            if (candidate.groups().stream()
                    .anyMatch(group -> perGroup.getOrDefault(group, 0) >= MAX_GROUP_REPRESENTATIVES)) {
                continue;
            }
            candidate.groups().forEach(group -> perGroup.merge(group, 1, Integer::sum));
            protectedEvidence.add(candidate);
        }
        return List.copyOf(protectedEvidence);
    }

    private static boolean isTypedFactSource(CodeSearchResult result, Set<String> evidenceIds) {
        return result != null && evidenceIds != null && !evidenceIds.isEmpty()
                && evidenceIds.contains(CodeEvidenceId.from(result));
    }

    @SafeVarargs
    private static List<CodeSearchResult> uniqueResults(List<CodeSearchResult>... sources) {
        Map<UUID, CodeSearchResult> unique = new LinkedHashMap<>();
        for (List<CodeSearchResult> source : sources) {
            for (CodeSearchResult result : source == null ? List.<CodeSearchResult>of() : source) {
                if (result != null && result.chunkId() != null) unique.putIfAbsent(result.chunkId(), result);
            }
        }
        return List.copyOf(unique.values());
    }

    private static void addUnique(List<CodeSearchResult> target, List<CodeSearchResult> additions) {
        Set<UUID> existing = target.stream()
                .map(CodeSearchResult::chunkId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (CodeSearchResult result : additions == null ? List.<CodeSearchResult>of() : additions) {
            if (result != null && result.chunkId() != null && existing.add(result.chunkId())) target.add(result);
        }
    }

    private static List<CodeSearchResult> restoreSemanticOrder(
            List<CodeSearchResult> semanticOrder,
            List<CodeSearchResult> membership,
            int limit
    ) {
        if (membership == null || membership.isEmpty() || limit <= 0) return List.of();
        Map<UUID, Integer> rankById = new LinkedHashMap<>();
        List<CodeSearchResult> safeSemanticOrder = semanticOrder == null ? List.of() : semanticOrder;
        for (int index = 0; index < safeSemanticOrder.size(); index++) {
            CodeSearchResult result = safeSemanticOrder.get(index);
            if (result != null && result.chunkId() != null) rankById.putIfAbsent(result.chunkId(), index);
        }
        Map<UUID, Integer> membershipOrder = new LinkedHashMap<>();
        for (int index = 0; index < membership.size(); index++) {
            CodeSearchResult result = membership.get(index);
            if (result != null && result.chunkId() != null) membershipOrder.putIfAbsent(result.chunkId(), index);
        }
        return membership.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator
                        .comparingInt((CodeSearchResult result) -> rankById.getOrDefault(
                                result.chunkId(), Integer.MAX_VALUE))
                        .thenComparingInt(result -> membershipOrder.getOrDefault(
                                result.chunkId(), Integer.MAX_VALUE)))
                .limit(limit)
                .toList();
    }

    private static List<Candidate> uniqueCandidates(List<CodeSearchResult> ranked, boolean legacyProvenance) {
        List<Candidate> candidates = new ArrayList<>();
        Set<UUID> seenChunkIds = new HashSet<>();
        for (int index = 0; index < ranked.size(); index++) {
            CodeSearchResult result = ranked.get(index);
            if (result == null || result.chunkId() == null || !seenChunkIds.add(result.chunkId())) continue;
            ProvenanceType provenanceType = legacyProvenance ? provenanceType(result) : ProvenanceType.NONE;
            candidates.add(new Candidate(
                    result, index, provenanceType.rank(), provenanceType,
                    legacyProvenance ? evidenceGroups(result) : Set.of()));
        }
        return List.copyOf(candidates);
    }

    private static ProvenanceType provenanceType(CodeSearchResult result) {
        if (flag(result, "llmEvidenceSlateMustUse")) return ProvenanceType.SLATE_MUST_USE;
        if (flag(result, "llmValidatedEvidence")) return ProvenanceType.VALIDATED;
        if (flag(result, "llmDirectRead") && flag(result, "llmReadFulfilled")) {
            return ProvenanceType.DIRECT_READ;
        }
        if (flag(result, "llmChecklistGroupRequired")) return ProvenanceType.CHECKLIST;
        return ProvenanceType.NONE;
    }

    private static Set<String> evidenceGroups(CodeSearchResult result) {
        Map<String, Object> metadata = metadata(result);
        Set<String> groups = new LinkedHashSet<>();
        for (String key : EVIDENCE_GROUP_KEYS) addGroupValues(groups, metadata.get(key));
        return Set.copyOf(groups);
    }

    private static void addGroupValues(Set<String> groups, Object raw) {
        if (raw instanceof Collection<?> collection) {
            collection.forEach(value -> addGroupValues(groups, value));
            return;
        }
        String normalized = normalizeGroup(raw);
        if (!normalized.isBlank() && !"unknown".equals(normalized)) groups.add(normalized);
    }

    private static String normalizeGroup(Object raw) {
        return raw == null
                ? ""
                : String.valueOf(raw).trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private static boolean flag(CodeSearchResult result, String key) {
        return Boolean.TRUE.equals(metadata(result).get(key));
    }

    private static Map<String, Object> metadata(CodeSearchResult result) {
        return result == null || result.metadata() == null ? Map.of() : result.metadata();
    }

    private record Candidate(
            CodeSearchResult result,
            int rank,
            int provenanceRank,
            ProvenanceType provenanceType,
            Set<String> groups
    ) {
    }

    private record RetainedCandidate(Candidate candidate, CodeEvidenceRetentionPlan.Entry entry) {
    }

    private enum ProvenanceType {
        SLATE_MUST_USE(0),
        VALIDATED(0),
        DIRECT_READ(1),
        CHECKLIST(3),
        NONE(UNPRIORITIZED);

        private final int rank;

        ProvenanceType(int rank) {
            this.rank = rank;
        }

        int rank() {
            return rank;
        }
    }
}
