package com.learnbot.service.coderag.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record CodeEvidenceIr(
        List<CodeEvidenceItem> evidenceItems,
        List<CodeEvidenceFact> facts,
        List<CodeEvidenceConstraint> constraints,
        List<CodeEvidenceSignal> signals,
        List<CodeNavigationHandle> navigationHandles,
        List<Diagnostic> diagnostics
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public enum DiagnosticStatus {
        SUCCESS,
        SKIPPED,
        FAILED
    }

    public record Diagnostic(String extractorId, DiagnosticStatus status, String message) {
        public Diagnostic {
            extractorId = extractorId == null ? "" : extractorId.trim();
            if (extractorId.isBlank()) throw new IllegalArgumentException("extractorId must not be blank");
            status = Objects.requireNonNull(status, "status");
            message = message == null ? "" : message.trim();
        }
    }

    public CodeEvidenceIr {
        evidenceItems = immutable(evidenceItems);
        facts = immutable(facts);
        constraints = immutable(constraints);
        signals = immutable(signals);
        navigationHandles = immutable(navigationHandles);
        diagnostics = immutable(diagnostics);
    }

    public static CodeEvidenceIr empty() {
        return new CodeEvidenceIr(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return evidenceItems.isEmpty() && facts.isEmpty() && constraints.isEmpty()
                && signals.isEmpty() && navigationHandles.isEmpty();
    }

    public CodeEvidenceIr merge(CodeEvidenceIr other) {
        if (other == null) return this;
        Map<String, CodeEvidenceItem> mergedItems = new LinkedHashMap<>();
        evidenceItems.forEach(item -> mergedItems.put(item.evidenceId(), item));
        other.evidenceItems.forEach(item -> mergedItems.merge(item.evidenceId(), item, CodeEvidenceItem::merge));

        Map<String, CodeEvidenceFact> mergedFacts = new LinkedHashMap<>();
        facts.forEach(fact -> mergedFacts.put(fact.factId(), fact));
        other.facts.forEach(fact -> mergedFacts.merge(fact.factId(), fact, CodeEvidenceFact::merge));

        Map<String, CodeEvidenceConstraint> mergedConstraints = new LinkedHashMap<>();
        constraints.forEach(value -> mergedConstraints.put(constraintKey(value), value));
        other.constraints.forEach(value -> mergedConstraints.putIfAbsent(constraintKey(value), value));

        Map<String, CodeEvidenceSignal> mergedSignals = new LinkedHashMap<>();
        signals.forEach(value -> mergedSignals.put(signalKey(value), value));
        other.signals.forEach(value -> mergedSignals.merge(signalKey(value), value,
                (left, right) -> right.strength() > left.strength() ? right : left));

        Map<String, CodeNavigationHandle> mergedHandles = new LinkedHashMap<>();
        navigationHandles.forEach(value -> mergedHandles.put(value.handleId(), value));
        other.navigationHandles.forEach(value -> mergedHandles.putIfAbsent(value.handleId(), value));

        List<Diagnostic> mergedDiagnostics = new ArrayList<>(diagnostics);
        other.diagnostics.forEach(value -> {
            if (!mergedDiagnostics.contains(value)) mergedDiagnostics.add(value);
        });
        return new CodeEvidenceIr(List.copyOf(mergedItems.values()), List.copyOf(mergedFacts.values()),
                List.copyOf(mergedConstraints.values()), List.copyOf(mergedSignals.values()),
                List.copyOf(mergedHandles.values()), List.copyOf(mergedDiagnostics));
    }

    /**
     * Keeps typed intelligence only for evidence that survived final context selection. This lets
     * full-source facts and navigation handles remain available after excerpt compression without
     * reintroducing facts from candidates that were not shown to the answer model.
     */
    public CodeEvidenceIr retainEvidence(Set<String> evidenceIds) {
        Set<String> retainedIds = evidenceIds == null ? Set.of() : evidenceIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        if (retainedIds.isEmpty()) return empty();

        List<CodeEvidenceItem> retainedItems = evidenceItems.stream()
                .filter(item -> retainedIds.contains(item.evidenceId()))
                .toList();
        List<CodeEvidenceFact> retainedFacts = facts.stream()
                .filter(fact -> retainedIds.contains(fact.sourceEvidenceId()))
                .toList();
        List<CodeEvidenceSignal> retainedSignals = signals.stream()
                .filter(signal -> retainedIds.contains(signal.sourceEvidenceId()))
                .toList();
        List<CodeNavigationHandle> retainedHandles = navigationHandles.stream()
                .filter(handle -> retainedIds.contains(handle.sourceEvidenceId()))
                .toList();
        Set<String> retainedTargets = java.util.stream.Stream.of(
                        retainedIds.stream(),
                        retainedFacts.stream().map(CodeEvidenceFact::factId),
                        retainedHandles.stream().map(CodeNavigationHandle::handleId))
                .flatMap(java.util.function.Function.identity())
                .collect(Collectors.toUnmodifiableSet());
        List<CodeEvidenceConstraint> retainedConstraints = constraints.stream()
                .filter(constraint -> retainedTargets.contains(constraint.targetId()))
                .toList();
        return new CodeEvidenceIr(retainedItems, retainedFacts, retainedConstraints,
                retainedSignals, retainedHandles, diagnostics);
    }

    /**
     * Keeps only the navigation closure for selected evidence. This is intentionally narrower
     * than {@link #retainEvidence(Set)}: excerpt compression may hide an otherwise useful call
     * site, but it must not restore unrelated assignments or exact facts omitted from the final
     * answer context.
     */
    public CodeEvidenceIr retainNavigationEvidence(Set<String> evidenceIds) {
        Set<String> retainedIds = evidenceIds == null ? Set.of() : evidenceIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        if (retainedIds.isEmpty()) return empty();

        List<CodeEvidenceItem> retainedItems = evidenceItems.stream()
                .filter(item -> retainedIds.contains(item.evidenceId()))
                .toList();
        List<CodeNavigationHandle> retainedHandles = navigationHandles.stream()
                .filter(handle -> retainedIds.contains(handle.sourceEvidenceId()))
                .toList();
        Set<String> retainedHandleIds = retainedHandles.stream()
                .map(CodeNavigationHandle::handleId)
                .collect(Collectors.toUnmodifiableSet());
        List<CodeEvidenceConstraint> retainedConstraints = constraints.stream()
                .filter(constraint -> retainedHandleIds.contains(constraint.targetId()))
                .toList();
        return new CodeEvidenceIr(retainedItems, List.of(), retainedConstraints,
                List.of(), retainedHandles, diagnostics);
    }

    public CodeEvidenceIr withDiagnostic(Diagnostic diagnostic) {
        if (diagnostic == null || diagnostics.contains(diagnostic)) return this;
        List<Diagnostic> values = new ArrayList<>(diagnostics);
        values.add(diagnostic);
        return new CodeEvidenceIr(evidenceItems, facts, constraints, signals, navigationHandles, values);
    }

    private static String constraintKey(CodeEvidenceConstraint value) {
        return value.type() + "\u001f" + value.targetId();
    }

    private static String signalKey(CodeEvidenceSignal value) {
        return value.type() + "\u001f" + value.sourceEvidenceId();
    }

    private static <T> List<T> immutable(List<T> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().filter(Objects::nonNull).toList();
    }
}
