package com.learnbot.service.coderag.evidence;

import com.learnbot.service.CodeIntelligenceAuthority;
import com.learnbot.service.coderag.model.CodeEvidenceConstraint;
import com.learnbot.service.coderag.model.CodeEvidenceFact;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.CodeEvidenceItem;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Applies generic proof constraints to the shared Code Evidence IR.
 *
 * <p>This component does not rank or rewrite {@code CodeSearchResult}. It only
 * determines which typed evidence may be treated as proof, keeping navigation
 * handles and inferred facts from silently becoming factual answer support.</p>
 */
public final class CodeEvidenceAdjudicator {

    public Adjudication adjudicate(CodeEvidenceIr ir) {
        CodeEvidenceIr safeIr = ir == null ? CodeEvidenceIr.empty() : ir;
        Set<String> evidenceIds = new LinkedHashSet<>();
        Set<String> directProofIds = new LinkedHashSet<>();
        for (CodeEvidenceItem item : safeIr.evidenceItems()) {
            evidenceIds.add(item.evidenceId());
            if (item.kinds().contains(CodeEvidenceItem.Kind.DIRECT_SOURCE)
                    && item.authority().rank() >= CodeIntelligenceAuthority.SYNTAX.rank()) {
                directProofIds.add(item.evidenceId());
            }
        }

        Set<String> factIds = new LinkedHashSet<>();
        Set<String> exactFactIds = new LinkedHashSet<>();
        for (CodeEvidenceFact fact : safeIr.facts()) {
            factIds.add(fact.factId());
            if (fact.exactness() == CodeEvidenceFact.Exactness.EXACT
                    || fact.exactness() == CodeEvidenceFact.Exactness.NORMALIZED) {
                exactFactIds.add(fact.factId());
            }
        }

        Set<String> navigationOnlyIds = new LinkedHashSet<>();
        List<String> violations = new java.util.ArrayList<>();
        for (CodeEvidenceConstraint constraint : safeIr.constraints()) {
            switch (constraint.type()) {
                case NAVIGATION_ONLY -> navigationOnlyIds.add(constraint.targetId());
                case EXACT_FACT_REQUIRED -> {
                    if (!exactFactIds.contains(constraint.targetId())) {
                        violations.add("exact fact is unavailable: " + constraint.targetId());
                    }
                }
                case DIRECT_PROOF_REQUIRED -> {
                    boolean directEvidence = directProofIds.contains(constraint.targetId());
                    boolean directFact = factIds.contains(constraint.targetId())
                            && safeIr.facts().stream()
                            .filter(fact -> fact.factId().equals(constraint.targetId()))
                            .anyMatch(fact -> fact.authority().rank() >= CodeIntelligenceAuthority.SYNTAX.rank()
                                    && directProofIds.contains(fact.sourceEvidenceId()));
                    if (!directEvidence && !directFact) {
                        violations.add("direct proof is unavailable: " + constraint.targetId());
                    }
                }
            }
        }
        return new Adjudication(
                safeIr,
                violations.isEmpty(),
                List.copyOf(violations),
                Set.copyOf(directProofIds),
                Set.copyOf(exactFactIds),
                Set.copyOf(navigationOnlyIds)
        );
    }

    public record Adjudication(
            CodeEvidenceIr evidenceIr,
            boolean constraintsSatisfied,
            List<String> violations,
            Set<String> directProofEvidenceIds,
            Set<String> exactFactIds,
            Set<String> navigationOnlyHandleIds
    ) {
        public Adjudication {
            evidenceIr = evidenceIr == null ? CodeEvidenceIr.empty() : evidenceIr;
            violations = violations == null ? List.of() : List.copyOf(violations);
            directProofEvidenceIds = immutable(directProofEvidenceIds);
            exactFactIds = immutable(exactFactIds);
            navigationOnlyHandleIds = immutable(navigationOnlyHandleIds);
        }

        private static Set<String> immutable(Set<String> values) {
            return values == null ? Set.of() : Set.copyOf(values);
        }
    }
}
