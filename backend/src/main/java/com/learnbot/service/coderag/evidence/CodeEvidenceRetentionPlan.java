package com.learnbot.service.coderag.evidence;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.CodeIntelligenceAuthority;
import com.learnbot.service.coderag.model.CodeEvidenceConstraint;
import com.learnbot.service.coderag.model.CodeEvidenceFact;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.CodeEvidenceItem;
import com.learnbot.service.coderag.model.CodeEvidenceOperationProvenance;
import com.learnbot.service.coderag.model.CodeEvidenceSignal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Typed, bounded-retention intent derived from Code Evidence IR.
 *
 * <p>The plan deliberately contains no question, language, framework, or
 * evidence-kind-specific priority. Selection consumes only a stable evidence
 * identity, a generic retention level, authority, and grouping.</p>
 */
public final class CodeEvidenceRetentionPlan {
    private static final double MIN_PREFERRED_SIGNAL_STRENGTH = 0.7;
    private static final CodeEvidenceRetentionPlan EMPTY = new CodeEvidenceRetentionPlan(Map.of());

    public enum Level {
        REQUIRED,
        PREFERRED
    }

    public enum Basis {
        CONSTRAINT,
        BOUNDED_GRAPH_PATH,
        SOURCE_BUNDLE,
        SIGNAL
    }

    public record Entry(
            Level level,
            CodeIntelligenceAuthority authority,
            Set<String> groups,
            Basis basis
    ) {
        public Entry(Level level, CodeIntelligenceAuthority authority, Set<String> groups) {
            this(level, authority, groups, Basis.SIGNAL);
        }

        public Entry {
            level = Objects.requireNonNull(level, "level");
            authority = authority == null ? CodeIntelligenceAuthority.UNKNOWN : authority;
            groups = immutableGroups(groups);
            basis = basis == null ? Basis.SIGNAL : basis;
        }

        private Entry merge(Entry other) {
            if (other == null) return this;
            Level mergedLevel = level == Level.REQUIRED || other.level == Level.REQUIRED
                    ? Level.REQUIRED : Level.PREFERRED;
            CodeIntelligenceAuthority mergedAuthority = other.authority.rank() > authority.rank()
                    ? other.authority : authority;
            LinkedHashSet<String> mergedGroups = new LinkedHashSet<>(groups);
            mergedGroups.addAll(other.groups);
            Basis mergedBasis = basis.ordinal() <= other.basis.ordinal() ? basis : other.basis;
            return new Entry(mergedLevel, mergedAuthority, mergedGroups, mergedBasis);
        }
    }

    private final Map<String, Entry> entries;

    private CodeEvidenceRetentionPlan(Map<String, Entry> entries) {
        LinkedHashMap<String, Entry> safe = new LinkedHashMap<>();
        if (entries != null) {
            entries.forEach((rawId, entry) -> {
                String evidenceId = normalizeEvidenceId(rawId);
                if (!evidenceId.isBlank() && entry != null) {
                    safe.merge(evidenceId, entry, Entry::merge);
                }
            });
        }
        this.entries = Collections.unmodifiableMap(safe);
    }

    public static CodeEvidenceRetentionPlan empty() {
        return EMPTY;
    }

    public static CodeEvidenceRetentionPlan of(Map<String, Entry> entries) {
        if (entries == null || entries.isEmpty()) return empty();
        CodeEvidenceRetentionPlan plan = new CodeEvidenceRetentionPlan(entries);
        return plan.isEmpty() ? empty() : plan;
    }

    /** Resolves only constraints and strong typed signals backed by an IR evidence item. */
    public static CodeEvidenceRetentionPlan from(CodeEvidenceIr ir) {
        CodeEvidenceIr safeIr = ir == null ? CodeEvidenceIr.empty() : ir;
        if (safeIr.isEmpty()) return empty();

        Map<String, CodeEvidenceItem> items = new LinkedHashMap<>();
        for (CodeEvidenceItem item : safeIr.evidenceItems()) {
            if (item == null || normalizeEvidenceId(item.evidenceId()).isBlank()) continue;
            items.merge(item.evidenceId(), item, CodeEvidenceItem::merge);
        }

        Map<String, CodeEvidenceFact> facts = new LinkedHashMap<>();
        for (CodeEvidenceFact fact : safeIr.facts()) {
            if (fact == null || !items.containsKey(fact.sourceEvidenceId())) continue;
            facts.merge(fact.factId(), fact, CodeEvidenceFact::merge);
        }

        Map<String, Entry> resolved = new LinkedHashMap<>();
        for (CodeEvidenceConstraint constraint : safeIr.constraints()) {
            if (constraint == null) continue;
            switch (constraint.type()) {
                case EXACT_FACT_REQUIRED -> requireExactFact(constraint.targetId(), facts, items, resolved);
                case DIRECT_PROOF_REQUIRED -> requireDirectProof(
                        constraint.targetId(), facts, items, resolved);
                case NAVIGATION_ONLY -> {
                    // A locator is not proof. Strong source-backed signals may still prefer it.
                }
            }
        }

        for (CodeEvidenceSignal signal : safeIr.signals()) {
            if (signal == null || signal.strength() < MIN_PREFERRED_SIGNAL_STRENGTH) continue;
            CodeEvidenceItem item = items.get(signal.sourceEvidenceId());
            if (item == null || item.authority().rank() < CodeIntelligenceAuthority.SYNTAX.rank()) continue;
            Basis basis = isBoundedGraphPath(item)
                    ? Basis.BOUNDED_GRAPH_PATH
                    : signal.type() == CodeEvidenceSignal.Type.SOURCE_BUNDLE_BOUNDARY
                    ? Basis.SOURCE_BUNDLE
                    : Basis.SIGNAL;
            merge(resolved, item.evidenceId(), new Entry(Level.PREFERRED, item.authority(),
                    signalGroups(item, signal), basis));
        }
        return of(resolved);
    }

    private static Set<String> signalGroups(CodeEvidenceItem item, CodeEvidenceSignal signal) {
        LinkedHashSet<String> groups = new LinkedHashSet<>();
        for (CodeEvidenceOperationProvenance provenance
                : CodeEvidenceOperationProvenance.from(item.source())) {
            if (!provenance.isDirectOperation() && !provenance.isSearchOperation()) continue;
            if (!provenance.operationId().isBlank()) {
                groups.add(group("operation", provenance.operationId()));
            }
            provenance.claimIds().forEach(claimId -> groups.add(group("claim", claimId)));
            if (!provenance.evidenceGroup().isBlank()) {
                groups.add(group("evidence", provenance.evidenceGroup()));
            }
            if ("traverse_graph".equals(provenance.operationType())) {
                String direction = metadata(item.source(), "graphDirection");
                String edgeType = metadata(item.source(), "graphEdgeType");
                if (!direction.isBlank() && !edgeType.isBlank()) {
                    groups.add(group("graph_branch", provenance.operationId()
                            + ":" + direction + ":" + edgeType));
                }
            }
            if ("read_source_boundary".equals(provenance.operationType())
                    && !provenance.operationId().isBlank()) {
                groups.add(group("source_bundle", provenance.operationId()));
            }
        }
        groups.removeIf(String::isBlank);
        if (groups.isEmpty()) groups.add(group("signal", signal.type().name()));
        return Set.copyOf(groups);
    }

    private static boolean isBoundedGraphPath(CodeEvidenceItem item) {
        return item != null
                && CodeEvidenceOperationProvenance.isBoundedGraphImplementation(item.source());
    }

    public static CodeEvidenceRetentionPlan resolve(CodeEvidenceIr ir) {
        return from(ir);
    }

    public Optional<Entry> lookup(String evidenceId) {
        String normalized = normalizeEvidenceId(evidenceId);
        return normalized.isBlank() ? Optional.empty() : Optional.ofNullable(entries.get(normalized));
    }

    public Map<String, Entry> entries() {
        return entries;
    }

    public Set<String> evidenceIds() {
        return entries.keySet();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public CodeEvidenceRetentionPlan merge(CodeEvidenceRetentionPlan other) {
        if (other == null || other.isEmpty()) return this;
        if (isEmpty()) return other;
        LinkedHashMap<String, Entry> merged = new LinkedHashMap<>(entries);
        other.entries.forEach((evidenceId, entry) -> merged.merge(evidenceId, entry, Entry::merge));
        return of(merged);
    }

    private static void requireExactFact(
            String targetId,
            Map<String, CodeEvidenceFact> facts,
            Map<String, CodeEvidenceItem> items,
            Map<String, Entry> resolved
    ) {
        CodeEvidenceFact fact = facts.get(targetId);
        if (fact == null || (fact.exactness() != CodeEvidenceFact.Exactness.EXACT
                && fact.exactness() != CodeEvidenceFact.Exactness.NORMALIZED)
                || fact.authority().rank() < CodeIntelligenceAuthority.SYNTAX.rank()) return;
        CodeEvidenceItem item = items.get(fact.sourceEvidenceId());
        if (item == null || item.authority().rank() < CodeIntelligenceAuthority.SYNTAX.rank()) return;
        merge(resolved, item.evidenceId(), new Entry(Level.REQUIRED, item.authority(),
                Set.of(group("fact", fact.factId())), Basis.CONSTRAINT));
    }

    private static void requireDirectProof(
            String targetId,
            Map<String, CodeEvidenceFact> facts,
            Map<String, CodeEvidenceItem> items,
            Map<String, Entry> resolved
    ) {
        CodeEvidenceFact fact = facts.get(targetId);
        String sourceEvidenceId = fact == null ? targetId : fact.sourceEvidenceId();
        CodeEvidenceItem item = items.get(sourceEvidenceId);
        if (item == null
                || !item.kinds().contains(CodeEvidenceItem.Kind.DIRECT_SOURCE)
                || item.authority().rank() < CodeIntelligenceAuthority.SYNTAX.rank()
                || (fact != null && fact.authority().rank() < CodeIntelligenceAuthority.SYNTAX.rank())) {
            return;
        }
        merge(resolved, item.evidenceId(), new Entry(Level.REQUIRED, item.authority(),
                Set.of(group("proof", targetId)), Basis.CONSTRAINT));
    }

    private static void merge(Map<String, Entry> entries, String evidenceId, Entry entry) {
        String normalized = normalizeEvidenceId(evidenceId);
        if (!normalized.isBlank() && entry != null) entries.merge(normalized, entry, Entry::merge);
    }

    private static String group(String namespace, String value) {
        String safeNamespace = normalizeGroup(namespace);
        String safeValue = normalizeGroup(value);
        return safeNamespace.isBlank() || safeValue.isBlank() ? "" : safeNamespace + ":" + safeValue;
    }

    private static Set<String> immutableGroups(Set<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        LinkedHashSet<String> safe = new LinkedHashSet<>();
        values.stream().filter(Objects::nonNull).map(CodeEvidenceRetentionPlan::normalizeGroup)
                .filter(value -> !value.isBlank() && !"unknown".equals(value))
                .forEach(safe::add);
        return Collections.unmodifiableSet(safe);
    }

    private static String metadata(CodeSearchResult source, String key) {
        Object value = source == null || source.metadata() == null ? null : source.metadata().get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String normalizeEvidenceId(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeGroup(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
    }
}
