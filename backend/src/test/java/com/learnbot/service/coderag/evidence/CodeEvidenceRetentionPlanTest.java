package com.learnbot.service.coderag.evidence;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.CodeIntelligenceAuthority;
import com.learnbot.service.coderag.model.CodeEvidenceConstraint;
import com.learnbot.service.coderag.model.CodeEvidenceFact;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.CodeEvidenceItem;
import com.learnbot.service.coderag.model.CodeEvidenceOperationProvenance;
import com.learnbot.service.coderag.model.CodeEvidenceSignal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeEvidenceRetentionPlanTest {
    @Test
    void resolvesExactFactAndDirectProofConstraintsToTheirSourceEvidence() {
        CodeEvidenceItem factSource = item(result("src/Fact.java", Map.of()), CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceItem proofSource = item(result("src/Proof.java", Map.of()), CodeIntelligenceAuthority.COMPILER_SEMANTIC);
        CodeEvidenceFact fact = CodeEvidenceFact.of(factSource.evidenceId(), "State", "VALUE", "READY",
                CodeEvidenceFact.Exactness.EXACT, 1.0, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceIr ir = new CodeEvidenceIr(
                List.of(factSource, proofSource),
                List.of(fact),
                List.of(
                        new CodeEvidenceConstraint(CodeEvidenceConstraint.Type.EXACT_FACT_REQUIRED,
                                fact.factId(), "exact fact"),
                        new CodeEvidenceConstraint(CodeEvidenceConstraint.Type.DIRECT_PROOF_REQUIRED,
                                proofSource.evidenceId(), "direct source")
                ),
                List.of(), List.of(), List.of());

        CodeEvidenceRetentionPlan plan = CodeEvidenceRetentionPlan.from(ir);

        assertThat(plan.lookup(factSource.evidenceId())).get()
                .extracting(CodeEvidenceRetentionPlan.Entry::level,
                        CodeEvidenceRetentionPlan.Entry::authority)
                .containsExactly(CodeEvidenceRetentionPlan.Level.REQUIRED, CodeIntelligenceAuthority.SYNTAX);
        assertThat(plan.lookup(proofSource.evidenceId())).get()
                .extracting(CodeEvidenceRetentionPlan.Entry::level,
                        CodeEvidenceRetentionPlan.Entry::authority)
                .containsExactly(CodeEvidenceRetentionPlan.Level.REQUIRED,
                        CodeIntelligenceAuthority.COMPILER_SEMANTIC);
    }

    @Test
    void navigationOnlyConstraintDoesNotCreateRequiredRetention() {
        CodeEvidenceItem item = item(result("src/Locator.java", Map.of()), CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceIr ir = new CodeEvidenceIr(
                List.of(item), List.of(),
                List.of(new CodeEvidenceConstraint(CodeEvidenceConstraint.Type.NAVIGATION_ONLY,
                        "handle:locator", "locator only")),
                List.of(), List.of(), List.of());

        assertThat(CodeEvidenceRetentionPlan.from(ir).lookup(item.evidenceId())).isEmpty();
    }

    @Test
    void onlyStrongSyntaxBackedSignalsBecomePreferred() {
        CodeEvidenceItem strong = item(result("src/Strong.java", Map.of()), CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceItem weak = item(result("src/Weak.java", Map.of()), CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceItem lexical = item(result("src/Lexical.java", Map.of()), CodeIntelligenceAuthority.LEXICAL);
        CodeEvidenceIr ir = new CodeEvidenceIr(
                List.of(strong, weak, lexical), List.of(), List.of(),
                List.of(
                        new CodeEvidenceSignal(CodeEvidenceSignal.Type.STATE_TRANSITION,
                                strong.evidenceId(), 0.7, "strong"),
                        new CodeEvidenceSignal(CodeEvidenceSignal.Type.STATE_TRANSITION,
                                weak.evidenceId(), 0.69, "weak"),
                        new CodeEvidenceSignal(CodeEvidenceSignal.Type.EXACT_LITERAL,
                                lexical.evidenceId(), 1.0, "low authority")
                ), List.of(), List.of());

        CodeEvidenceRetentionPlan plan = CodeEvidenceRetentionPlan.resolve(ir);

        assertThat(plan.lookup(strong.evidenceId())).get()
                .extracting(CodeEvidenceRetentionPlan.Entry::level)
                .isEqualTo(CodeEvidenceRetentionPlan.Level.PREFERRED);
        assertThat(plan.lookup(weak.evidenceId())).isEmpty();
        assertThat(plan.lookup(lexical.evidenceId())).isEmpty();
    }

    @Test
    void directOperationSignalsUseTypedOperationAndClaimGroups() {
        CodeEvidenceOperationProvenance provenance = new CodeEvidenceOperationProvenance(
                "list_file_symbols", "op-flow", List.of("claim-flow"), "lifecycle-flow",
                List.of("origin-evidence"), "", "src/Flow.java", "", "",
                null, null, null, List.of(), "", null);
        CodeEvidenceItem source = item(result("src/Flow.java", Map.of(
                CodeEvidenceOperationProvenance.METADATA_KEY, List.of(provenance))),
                CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceSignal signal = new CodeEvidenceSignal(
                CodeEvidenceSignal.Type.DIRECT_OBSERVATION,
                source.evidenceId(), 1.0, "typed observation");

        CodeEvidenceRetentionPlan plan = CodeEvidenceRetentionPlan.from(new CodeEvidenceIr(
                List.of(source), List.of(), List.of(), List.of(signal), List.of(), List.of()));

        assertThat(plan.lookup(source.evidenceId())).get().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(CodeEvidenceRetentionPlan.Level.PREFERRED);
            assertThat(entry.groups()).containsExactlyInAnyOrder(
                    "operation:op_flow", "claim:claim_flow", "evidence:lifecycle_flow");
        });
    }

    @Test
    void plannedSearchSignalsRemainPreferredRatherThanRequired() {
        CodeEvidenceOperationProvenance provenance = new CodeEvidenceOperationProvenance(
                "keyword_search", "op-discover", List.of("claim-discover"), "discovery-flow",
                List.of(), "lifecycle transition", "", "", "",
                null, null, null, List.of(), "", null);
        CodeEvidenceItem source = item(result("src/Discovered.java", Map.of(
                CodeEvidenceOperationProvenance.METADATA_KEY, List.of(provenance))),
                CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceSignal signal = new CodeEvidenceSignal(
                CodeEvidenceSignal.Type.OBSERVED_NAVIGATION,
                source.evidenceId(), 1.0, "source-backed search result");

        CodeEvidenceRetentionPlan plan = CodeEvidenceRetentionPlan.from(new CodeEvidenceIr(
                List.of(source), List.of(), List.of(), List.of(signal), List.of(), List.of()));

        assertThat(plan.lookup(source.evidenceId())).get().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(CodeEvidenceRetentionPlan.Level.PREFERRED);
            assertThat(entry.groups()).containsExactlyInAnyOrder(
                    "operation:op_discover", "claim:claim_discover", "evidence:discovery_flow");
        });
    }

    @Test
    void directOneHopGraphImplementationUsesTypedNeighborBasisAndBranchGroup() {
        CodeEvidenceOperationProvenance provenance = new CodeEvidenceOperationProvenance(
                "traverse_graph", "op-graph", List.of("claim-flow"), "call-flow",
                List.of("origin-evidence"), "", "", "", UUID.randomUUID().toString(),
                null, null, null, List.of("CALLS"), "BOTH", 1);
        CodeEvidenceItem neighbor = item(result("src/Store.java", Map.of(
                "graphExpanded", true,
                "graphDepth", 1,
                "graphDirection", "FORWARD",
                "graphEdgeType", "CALLS",
                CodeEvidenceOperationProvenance.METADATA_KEY, List.of(provenance))),
                CodeIntelligenceAuthority.COMPILER_SEMANTIC);
        CodeEvidenceSignal signal = new CodeEvidenceSignal(
                CodeEvidenceSignal.Type.DIRECT_OBSERVATION,
                neighbor.evidenceId(), 1.0, "direct graph body");

        CodeEvidenceRetentionPlan plan = CodeEvidenceRetentionPlan.from(new CodeEvidenceIr(
                List.of(neighbor), List.of(), List.of(), List.of(signal), List.of(), List.of()));

        assertThat(plan.lookup(neighbor.evidenceId())).get().satisfies(entry -> {
            assertThat(entry.basis()).isEqualTo(CodeEvidenceRetentionPlan.Basis.BOUNDED_GRAPH_PATH);
            assertThat(entry.groups()).contains(
                    "graph_branch:op_graph:forward:calls",
                    "operation:op_graph", "claim:claim_flow", "evidence:call_flow");
        });
    }

    @Test
    void boundedTwoHopGraphImplementationKeepsTypedPathBasis() {
        CodeEvidenceOperationProvenance provenance = new CodeEvidenceOperationProvenance(
                "traverse_graph", "op-two-hop", List.of("claim-flow"), "call-flow",
                List.of("origin-evidence"), "", "", "", UUID.randomUUID().toString(),
                null, null, null, List.of("CALLS"), "BOTH", 2);
        CodeEvidenceItem implementation = item(result("src/Leaf.java", Map.of(
                "graphExpanded", true,
                "graphDepth", 2,
                "graphDirection", "FORWARD",
                "graphEdgeType", "CALLS",
                CodeEvidenceOperationProvenance.METADATA_KEY, List.of(provenance))),
                CodeIntelligenceAuthority.COMPILER_SEMANTIC);
        CodeEvidenceSignal signal = new CodeEvidenceSignal(
                CodeEvidenceSignal.Type.DIRECT_OBSERVATION,
                implementation.evidenceId(), 1.0, "bounded graph body");

        CodeEvidenceRetentionPlan plan = CodeEvidenceRetentionPlan.from(new CodeEvidenceIr(
                List.of(implementation), List.of(), List.of(), List.of(signal), List.of(), List.of()));

        assertThat(plan.lookup(implementation.evidenceId())).get().satisfies(entry ->
                assertThat(entry.basis()).isEqualTo(CodeEvidenceRetentionPlan.Basis.BOUNDED_GRAPH_PATH));
    }

    @Test
    void unresolvedOrNonDirectConstraintsFailClosed() {
        CodeEvidenceItem relation = new CodeEvidenceItem(
                CodeEvidenceItem.evidenceId(result("src/Relation.java", Map.of())),
                result("src/Other.java", Map.of()),
                Set.of(CodeEvidenceItem.Kind.GRAPH_RELATION),
                CodeIntelligenceAuthority.COMPILER_SEMANTIC);
        CodeEvidenceIr ir = new CodeEvidenceIr(
                List.of(relation), List.of(),
                List.of(
                        new CodeEvidenceConstraint(CodeEvidenceConstraint.Type.EXACT_FACT_REQUIRED,
                                "missing-fact", "missing"),
                        new CodeEvidenceConstraint(CodeEvidenceConstraint.Type.DIRECT_PROOF_REQUIRED,
                                relation.evidenceId(), "not direct")
                ), List.of(), List.of(), List.of());

        assertThat(CodeEvidenceRetentionPlan.from(ir)).matches(CodeEvidenceRetentionPlan::isEmpty);
        assertThat(CodeEvidenceRetentionPlan.empty().lookup("  ")).isEmpty();
    }

    @Test
    void exactConstraintWithInferredAuthorityCannotSpoofRequiredRetention() {
        CodeEvidenceItem lowAuthorityItem = item(result("src/LowItem.java", Map.of()),
                CodeIntelligenceAuthority.LLM_INFERRED);
        CodeEvidenceFact highAuthorityFact = CodeEvidenceFact.of(lowAuthorityItem.evidenceId(),
                "State", "VALUE", "READY",
                CodeEvidenceFact.Exactness.EXACT, 1.0, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceItem highAuthorityItem = item(result("src/LowFact.java", Map.of()),
                CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceFact lowAuthorityFact = CodeEvidenceFact.of(highAuthorityItem.evidenceId(),
                "State", "VALUE", "DONE",
                CodeEvidenceFact.Exactness.EXACT, 1.0, CodeIntelligenceAuthority.LLM_INFERRED);
        CodeEvidenceIr ir = new CodeEvidenceIr(
                List.of(lowAuthorityItem, highAuthorityItem),
                List.of(highAuthorityFact, lowAuthorityFact),
                List.of(
                        new CodeEvidenceConstraint(CodeEvidenceConstraint.Type.EXACT_FACT_REQUIRED,
                                highAuthorityFact.factId(), "untrusted source authority"),
                        new CodeEvidenceConstraint(CodeEvidenceConstraint.Type.EXACT_FACT_REQUIRED,
                                lowAuthorityFact.factId(), "untrusted fact authority")
                ),
                List.of(), List.of(), List.of());

        assertThat(CodeEvidenceRetentionPlan.from(ir)).matches(CodeEvidenceRetentionPlan::isEmpty);
    }

    @Test
    void mergeIsImmutableAndRequiredDominatesPreferred() {
        String evidenceId = "index:chunk:1-2";
        CodeEvidenceRetentionPlan preferred = CodeEvidenceRetentionPlan.of(Map.of(
                evidenceId, new CodeEvidenceRetentionPlan.Entry(
                        CodeEvidenceRetentionPlan.Level.PREFERRED,
                        CodeIntelligenceAuthority.SYNTAX,
                        Set.of("flow-a"))));
        CodeEvidenceRetentionPlan required = CodeEvidenceRetentionPlan.of(Map.of(
                evidenceId, new CodeEvidenceRetentionPlan.Entry(
                        CodeEvidenceRetentionPlan.Level.REQUIRED,
                        CodeIntelligenceAuthority.COMPILER_SEMANTIC,
                        Set.of("flow-b"))));

        CodeEvidenceRetentionPlan merged = preferred.merge(required);

        assertThat(preferred.lookup(evidenceId)).get()
                .extracting(CodeEvidenceRetentionPlan.Entry::level)
                .isEqualTo(CodeEvidenceRetentionPlan.Level.PREFERRED);
        assertThat(merged.lookup(evidenceId)).get().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(CodeEvidenceRetentionPlan.Level.REQUIRED);
            assertThat(entry.authority()).isEqualTo(CodeIntelligenceAuthority.COMPILER_SEMANTIC);
            assertThat(entry.groups()).containsExactlyInAnyOrder("flow_a", "flow_b");
        });
        assertThat(merged.entries()).isUnmodifiable();
    }

    private CodeEvidenceItem item(CodeSearchResult result, CodeIntelligenceAuthority authority) {
        return new CodeEvidenceItem(CodeEvidenceItem.evidenceId(result), result,
                Set.of(CodeEvidenceItem.Kind.DIRECT_SOURCE), authority);
    }

    private CodeSearchResult result(String path, Map<String, Object> metadata) {
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", path,
                "method", "test", "Test", "test", "app", null, null, 1,
                1, 20, "void test() {}", 0.8, metadata);
    }
}
