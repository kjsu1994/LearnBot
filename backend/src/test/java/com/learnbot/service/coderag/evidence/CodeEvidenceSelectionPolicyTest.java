package com.learnbot.service.coderag.evidence;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.CodeIntelligenceAuthority;
import com.learnbot.service.coderag.model.CodeEvidenceConstraint;
import com.learnbot.service.coderag.model.CodeEvidenceFact;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.CodeEvidenceItem;
import com.learnbot.service.coderag.model.CodeEvidenceSignal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeEvidenceSelectionPolicyTest {
    @Test
    void defaultSelectionIgnoresSpoofedDeterministicMetadata() {
        List<CodeSearchResult> ranked = new ArrayList<>();
        ranked.add(result("src/First.java", Map.of()));
        ranked.add(result("src/Second.java", Map.of()));
        CodeSearchResult spoofed = result("src/Spoofed.java", Map.of(
                "deterministicEndpointEvidence", true,
                "deterministicEndpointBestMatch", true,
                "deterministicNavigationBestMatch", true,
                "deterministicLexicalCandidate", true));
        ranked.add(spoofed);

        List<CodeSearchResult> selected = CodeEvidenceSelectionPolicy.select(ranked, 2);

        assertThat(selected).containsExactly(ranked.get(0), ranked.get(1)).doesNotContain(spoofed);
    }

    @Test
    void validatedEvidenceOutranksSpoofedDeterministicMetadata() {
        CodeSearchResult endpoint = result("src/ApiController.java", Map.of("deterministicEndpointEvidence", true));
        CodeSearchResult validated = result("src/Service.java", Map.of("llmValidatedEvidence", true));

        List<CodeSearchResult> selected = CodeEvidenceSelectionPolicy.select(List.of(endpoint, validated), 1);

        assertThat(selected).containsExactly(validated);
    }

    @Test
    void returnsNoEvidenceWhenTheRequestedLimitIsNotPositive() {
        CodeSearchResult required = result("src/Service.java", Map.of("llmValidatedEvidence", true));

        assertThat(CodeEvidenceSelectionPolicy.select(List.of(required), 0)).isEmpty();
    }

    @Test
    void fulfilledDirectReadReplacesBroadRequiredCandidateFromTheSameGroup() {
        List<CodeSearchResult> ranked = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            ranked.add(result(
                    "src/Broad" + index + ".java",
                    1.0 - (index * 0.01),
                    Map.of(
                            "llmChecklistGroupRequired", true,
                            "llmChecklistGroup", "state_transition"
                    )
            ));
        }
        CodeSearchResult exactRead = result(
                "src/Exact.java",
                0.1,
                Map.of(
                        "llmDirectRead", true,
                        "llmReadFulfilled", true,
                        "llmReadEvidenceGroup", "state_transition",
                        "llmChecklistGroupRequired", true
                )
        );
        ranked.add(exactRead);

        List<CodeSearchResult> selected = CodeEvidenceSelectionPolicy.select(ranked, 10);

        assertThat(selected).hasSize(10).contains(exactRead).doesNotContain(ranked.get(9));
    }

    @Test
    void resolvesScalarAndCollectionGroupMetadataByStrongestProvenance() {
        CodeSearchResult broad = result(
                "src/Broad.java",
                Map.of(
                        "llmChecklistGroupRequired", true,
                        "llmChecklistGroup", "graph-persistence"
                )
        );
        CodeSearchResult navigation = result(
                "src/Navigation.java",
                Map.of(
                        "deterministicNavigationBestMatch", true,
                        "llmEvidenceCoverageGroup", List.of("graph persistence", "secondary")
                )
        );
        CodeSearchResult validated = result(
                "src/Validated.java",
                Map.of(
                        "llmValidatedEvidence", true,
                        "llmValidatedEvidenceGroup", List.of("graph_persistence")
                )
        );

        List<CodeSearchResult> selected = CodeEvidenceSelectionPolicy.select(
                List.of(broad, navigation, validated), 1);

        assertThat(selected).containsExactly(validated);
    }

    @Test
    void finalEvidenceKeepsCompletedExactReadAgainstBroadChecklistCandidates() {
        List<CodeSearchResult> ranked = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            ranked.add(result(
                    "src/Broad" + index + ".java",
                    1.0 - (index * 0.01),
                    Map.of(
                            "llmChecklistGroupRequired", true,
                            "llmChecklistGroup", "activation_flow"
                    )
            ));
        }
        List<CodeSearchResult> initiallySelected = List.copyOf(ranked);
        CodeSearchResult exactRead = result(
                "src/Repository.java",
                0.05,
                Map.of(
                        "llmDirectRead", true,
                        "llmReadFulfilled", true,
                        "llmReadEvidenceGroup", "activation_flow",
                        "llmChecklistGroupRequired", true
                )
        );
        ranked.add(exactRead);

        List<CodeSearchResult> selected = CodeEvidenceSelectionPolicy.selectFinalEvidence(
                ranked, initiallySelected, 10, ignored -> false);

        assertThat(selected).hasSize(10).contains(exactRead);
        assertThat(selected.stream().filter(result -> result.filePath().startsWith("src/Broad")))
                .hasSize(9);
    }

    @Test
    void finalEvidenceProtectsTwoComplementaryReadsPerEvidenceGroup() {
        List<CodeSearchResult> broad = List.of(
                result("src/Controller.java", 0.9, Map.of()),
                result("src/Service.java", 0.8, Map.of()),
                result("src/Repository.java", 0.7, Map.of())
        );
        List<CodeSearchResult> ranked = new ArrayList<>(broad);
        CodeSearchResult caller = result("src/Caller.java", 0.2, Map.of(
                "llmDirectRead", true,
                "llmReadFulfilled", true,
                "llmReadEvidenceGroup", "same_flow"
        ));
        CodeSearchResult callee = result("src/Callee.java", 0.19, Map.of(
                "llmDirectRead", true,
                "llmReadFulfilled", true,
                "llmReadEvidenceGroup", "same_flow"
        ));
        CodeSearchResult duplicateProof = result("src/Extra.java", 0.18, Map.of(
                "llmDirectRead", true,
                "llmReadFulfilled", true,
                "llmReadEvidenceGroup", "same_flow"
        ));
        ranked.addAll(List.of(caller, callee, duplicateProof));

        List<CodeSearchResult> selected = CodeEvidenceSelectionPolicy.selectFinalEvidence(
                ranked, broad, 3, ignored -> false);

        assertThat(selected).hasSize(3);
        assertThat(selected.stream()
                .filter(result -> Boolean.TRUE.equals(result.metadata().get("llmDirectRead"))))
                .containsExactly(caller, callee);
        assertThat(selected).doesNotContain(duplicateProof);
    }

    @Test
    void finalEvidenceProtectsAtMostOneUngroupedExternalRequiredRepresentative() {
        List<CodeSearchResult> ranked = new ArrayList<>();
        List<CodeSearchResult> semanticTop = List.of(
                result("src/Top0.java", 1.0, Map.of()),
                result("src/Top1.java", 0.99, Map.of()),
                result("src/Top2.java", 0.98, Map.of()),
                result("src/Top3.java", 0.97, Map.of())
        );
        ranked.addAll(semanticTop);
        List<CodeSearchResult> navigation = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            CodeSearchResult candidate = result(
                    "src/Nav" + index + ".java",
                    0.5 - (index * 0.01),
                    Map.of()
            );
            navigation.add(candidate);
            ranked.add(candidate);
        }

        Set<UUID> externallyRequired = Set.copyOf(navigation.stream()
                .map(CodeSearchResult::chunkId)
                .toList());
        List<CodeSearchResult> selected = CodeEvidenceSelectionPolicy.selectFinalEvidence(
                ranked,
                semanticTop,
                4,
                result -> externallyRequired.contains(result.chunkId()));

        assertThat(selected).hasSize(4).contains(navigation.get(0));
        assertThat(selected.stream()
                .filter(result -> externallyRequired.contains(result.chunkId())))
                .hasSize(1);
    }

    @Test
    void finalEvidenceRestoresSemanticRankAfterMembershipChangesAndKeepsLimit() {
        CodeSearchResult first = result("src/First.java", 1.0, Map.of());
        CodeSearchResult second = result("src/Second.java", 0.9, Map.of());
        CodeSearchResult exactRead = result("src/Read.java", 0.1, Map.of(
                "llmDirectRead", true,
                "llmReadFulfilled", true
        ));

        List<CodeSearchResult> selected = CodeEvidenceSelectionPolicy.selectFinalEvidence(
                List.of(first, second, exactRead), List.of(first, second), 2, ignored -> false);

        assertThat(selected).containsExactly(first, exactRead);
    }

    @Test
    void finalEvidenceProtectsAtMostThreeTypedFactSourcesAndRestoresSemanticOrder() {
        CodeSearchResult first = result("src/First.java", 1.0, Map.of());
        CodeSearchResult second = result("src/Second.java", 0.9, Map.of());
        CodeSearchResult third = result("src/Third.java", 0.8, Map.of());
        CodeSearchResult fourth = result("src/Fourth.java", 0.7, Map.of());
        CodeSearchResult typedOne = result("src/TypedOne.java", 0.4, Map.of());
        CodeSearchResult typedTwo = result("src/TypedTwo.java", 0.3, Map.of());
        CodeSearchResult typedThree = result("src/TypedThree.java", 0.2, Map.of());
        CodeSearchResult typedFour = result("src/TypedFour.java", 0.1, Map.of());
        List<CodeSearchResult> ranked = List.of(
                first, second, third, fourth, typedOne, typedTwo, typedThree, typedFour);
        Set<String> typedSources = Set.of(
                CodeEvidenceId.from(typedOne), CodeEvidenceId.from(typedTwo),
                CodeEvidenceId.from(typedThree), CodeEvidenceId.from(typedFour));

        List<CodeSearchResult> selected = CodeEvidenceSelectionPolicy.selectFinalEvidence(
                ranked, List.of(first, second, third, fourth), 4, ignored -> false, typedSources);

        assertThat(selected)
                .hasSize(4)
                .containsExactly(first, typedOne, typedTwo, typedThree)
                .doesNotContain(typedFour);
    }

    @Test
    void finalEvidenceCapsTypedFactProtectionAtTwoSourcesPerEvidenceGroup() {
        CodeSearchResult first = result("src/First.java", 1.0, Map.of());
        CodeSearchResult second = result("src/Second.java", 0.9, Map.of());
        CodeSearchResult sameGroupOne = result("src/StateOne.java", 0.4,
                Map.of("llmEvidenceCoverageGroup", "state_change"));
        CodeSearchResult sameGroupTwo = result("src/StateTwo.java", 0.3,
                Map.of("llmEvidenceCoverageGroup", "state_change"));
        CodeSearchResult sameGroupThree = result("src/StateThree.java", 0.2,
                Map.of("llmEvidenceCoverageGroup", "state_change"));
        CodeSearchResult otherGroup = result("src/Endpoint.java", 0.1,
                Map.of("llmEvidenceCoverageGroup", "endpoint"));
        List<CodeSearchResult> ranked = List.of(
                first, second, sameGroupOne, sameGroupTwo, sameGroupThree, otherGroup);
        Set<String> typedSources = Set.of(
                CodeEvidenceId.from(sameGroupOne), CodeEvidenceId.from(sameGroupTwo),
                CodeEvidenceId.from(sameGroupThree), CodeEvidenceId.from(otherGroup));

        List<CodeSearchResult> selected = CodeEvidenceSelectionPolicy.selectFinalEvidence(
                ranked, List.of(first, second), 4, ignored -> false, typedSources);

        assertThat(selected)
                .hasSize(4)
                .containsExactly(first, sameGroupOne, sameGroupTwo, otherGroup)
                .doesNotContain(sameGroupThree);
    }

    @Test
    void typedPlanRetainsAConstrainedFactSourceOutsideTheSemanticCut() {
        CodeSearchResult first = result("src/First.java", 1.0, Map.of());
        CodeSearchResult second = result("src/Second.java", 0.9, Map.of());
        CodeSearchResult factSource = result("src/Fact.java", 0.1, Map.of());
        CodeEvidenceItem item = item(factSource, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceFact fact = CodeEvidenceFact.of(item.evidenceId(), "State", "VALUE", "READY",
                CodeEvidenceFact.Exactness.EXACT, 1.0, CodeIntelligenceAuthority.SYNTAX);
        CodeEvidenceIr ir = new CodeEvidenceIr(
                List.of(item), List.of(fact),
                List.of(new CodeEvidenceConstraint(CodeEvidenceConstraint.Type.EXACT_FACT_REQUIRED,
                        fact.factId(), "preserve exact fact")),
                List.of(), List.of(), List.of());

        List<CodeSearchResult> selected = CodeEvidenceSelectionPolicy.select(
                List.of(first, second, factSource), 2, CodeEvidenceRetentionPlan.from(ir));

        assertThat(selected).containsExactly(first, factSource);
    }

    @Test
    void incidentalTypedFactWithoutAConstraintDoesNotReceiveRetention() {
        CodeSearchResult first = result("src/First.java", 1.0, Map.of());
        CodeSearchResult second = result("src/Second.java", 0.9, Map.of());
        CodeSearchResult incidental = result("src/Incidental.java", 0.1, Map.of());
        CodeEvidenceItem item = item(incidental, CodeIntelligenceAuthority.COMPILER_SEMANTIC);
        CodeEvidenceFact fact = CodeEvidenceFact.of(item.evidenceId(), "State", "VALUE", "READY",
                CodeEvidenceFact.Exactness.EXACT, 1.0, CodeIntelligenceAuthority.COMPILER_SEMANTIC);
        CodeEvidenceRetentionPlan plan = CodeEvidenceRetentionPlan.from(new CodeEvidenceIr(
                List.of(item), List.of(fact), List.of(), List.of(), List.of(), List.of()));

        assertThat(CodeEvidenceSelectionPolicy.select(
                List.of(first, second, incidental), 2, plan)).containsExactly(first, second);
    }

    @Test
    void strongSignalsArePreferredButBoundedToPreserveSemanticEvidence() {
        List<CodeSearchResult> semantic = List.of(
                result("src/Top0.java", 1.0, Map.of()),
                result("src/Top1.java", 0.99, Map.of()),
                result("src/Top2.java", 0.98, Map.of()),
                result("src/Top3.java", 0.97, Map.of()));
        List<CodeSearchResult> signaled = List.of(
                result("src/Signal0.java", 0.4, Map.of()),
                result("src/Signal1.java", 0.3, Map.of()),
                result("src/Signal2.java", 0.2, Map.of()),
                result("src/Signal3.java", 0.1, Map.of()));
        List<CodeEvidenceItem> items = signaled.stream()
                .map(result -> item(result, CodeIntelligenceAuthority.SYNTAX))
                .toList();
        List<CodeEvidenceSignal.Type> types = List.of(
                CodeEvidenceSignal.Type.EXACT_LITERAL,
                CodeEvidenceSignal.Type.STATE_TRANSITION,
                CodeEvidenceSignal.Type.TRANSACTION_BOUNDARY,
                CodeEvidenceSignal.Type.PERSISTENCE_RELATION);
        List<CodeEvidenceSignal> signals = java.util.stream.IntStream.range(0, items.size())
                .mapToObj(index -> new CodeEvidenceSignal(types.get(index),
                        items.get(index).evidenceId(), 0.9, "strong signal"))
                .toList();
        List<CodeSearchResult> ranked = new ArrayList<>(semantic);
        ranked.addAll(signaled);

        List<CodeSearchResult> selected = CodeEvidenceSelectionPolicy.select(
                ranked, 4, CodeEvidenceRetentionPlan.from(new CodeEvidenceIr(
                        items, List.of(), List.of(), signals, List.of(), List.of())));

        assertThat(selected).containsExactly(
                semantic.get(0), signaled.get(0), signaled.get(1), signaled.get(2));
        assertThat(selected).doesNotContain(signaled.get(3));
    }

    @Test
    void typedOperationGroupCanRetainThreeWorkflowStages() {
        CodeSearchResult semantic = result("src/Semantic.java", 1.0, Map.of());
        CodeSearchResult entry = result("src/Entry.java", 0.4, Map.of());
        CodeSearchResult transition = result("src/Transition.java", 0.3, Map.of());
        CodeSearchResult terminal = result("src/Terminal.java", 0.2, Map.of());
        Map<String, CodeEvidenceRetentionPlan.Entry> entries = new java.util.LinkedHashMap<>();
        for (CodeSearchResult stage : List.of(entry, transition, terminal)) {
            entries.put(CodeEvidenceId.from(stage), new CodeEvidenceRetentionPlan.Entry(
                    CodeEvidenceRetentionPlan.Level.PREFERRED,
                    CodeIntelligenceAuthority.SYNTAX,
                    Set.of("operation:workflow")));
        }

        List<CodeSearchResult> selected = CodeEvidenceSelectionPolicy.selectFinalEvidence(
                List.of(semantic, entry, transition, terminal),
                List.of(semantic),
                CodeEvidenceRetentionPlan.of(entries),
                4);

        assertThat(selected).containsExactly(semantic, entry, transition, terminal);
    }

    @Test
    void typedGraphRetentionKeepsThreeSiblingCallBodiesAndBoundsEachBranch() {
        CodeSearchResult semanticFirst = result("src/Overview.java", 1.0, Map.of());
        CodeSearchResult semanticSecond = result("src/Architecture.java", 0.99, Map.of());
        CodeSearchResult firstCall = result("src/Store.java", 0.40, Map.of());
        CodeSearchResult secondCall = result("src/Store.java", 0.30, Map.of());
        CodeSearchResult thirdCall = result("src/Store.java", 0.20, Map.of());
        CodeSearchResult fourthCall = result("src/Store.java", 0.10, Map.of());
        CodeSearchResult reverseCaller = result("src/Entry.java", 0.05, Map.of());
        List<CodeSearchResult> ranked = List.of(
                semanticFirst, semanticSecond, firstCall, secondCall,
                thirdCall, fourthCall, reverseCaller);
        Map<String, CodeEvidenceRetentionPlan.Entry> entries = new java.util.LinkedHashMap<>();
        for (CodeSearchResult target : List.of(firstCall, secondCall, thirdCall, fourthCall)) {
            entries.put(CodeEvidenceId.from(target), new CodeEvidenceRetentionPlan.Entry(
                    CodeEvidenceRetentionPlan.Level.PREFERRED,
                    CodeIntelligenceAuthority.COMPILER_SEMANTIC,
                    Set.of("graph_branch:op_flow:forward:calls"),
                    CodeEvidenceRetentionPlan.Basis.BOUNDED_GRAPH_PATH));
        }
        entries.put(CodeEvidenceId.from(reverseCaller), new CodeEvidenceRetentionPlan.Entry(
                CodeEvidenceRetentionPlan.Level.PREFERRED,
                CodeIntelligenceAuthority.COMPILER_SEMANTIC,
                Set.of("graph_branch:op_flow:reverse:calls"),
                CodeEvidenceRetentionPlan.Basis.BOUNDED_GRAPH_PATH));

        List<CodeSearchResult> selected = CodeEvidenceSelectionPolicy.selectFinalEvidence(
                ranked, List.of(semanticFirst, semanticSecond),
                CodeEvidenceRetentionPlan.of(entries), 5);

        assertThat(selected).containsExactly(
                semanticFirst, firstCall, secondCall, thirdCall, reverseCaller);
        assertThat(selected).doesNotContain(semanticSecond, fourthCall);
    }

    @Test
    void typedPlanChangesMembershipButPreservesPresentationRankAndHardLimit() {
        CodeSearchResult semantic = result("src/Semantic.java", 1.0, Map.of());
        CodeSearchResult preferred = result("src/Preferred.java", 0.5, Map.of());
        CodeSearchResult required = result("src/Required.java", 0.1, Map.of());
        CodeEvidenceRetentionPlan plan = CodeEvidenceRetentionPlan.of(Map.of(
                CodeEvidenceId.from(preferred), new CodeEvidenceRetentionPlan.Entry(
                        CodeEvidenceRetentionPlan.Level.PREFERRED, CodeIntelligenceAuthority.SYNTAX,
                        Set.of("signal:preferred")),
                CodeEvidenceId.from(required), new CodeEvidenceRetentionPlan.Entry(
                        CodeEvidenceRetentionPlan.Level.REQUIRED, CodeIntelligenceAuthority.SYNTAX,
                        Set.of("fact:required"))));

        List<CodeSearchResult> selected = CodeEvidenceSelectionPolicy.selectFinalEvidence(
                List.of(semantic, preferred, required), List.of(semantic), plan, 2);

        assertThat(selected).hasSize(2).containsExactly(preferred, required);
    }

    @Test
    void typedPlanPathIgnoresSpoofedLegacyMetadata() {
        CodeSearchResult first = result("src/First.java", 1.0, Map.of());
        CodeSearchResult second = result("src/Second.java", 0.9, Map.of());
        CodeSearchResult spoofed = result("src/Spoofed.java", 0.1, Map.of(
                "deterministicEndpointEvidence", true,
                "deterministicNavigationBestMatch", true,
                "llmEvidenceSlateMustUse", true,
                "codeIntelligenceAuthority", "COMPILER_SEMANTIC"));

        List<CodeSearchResult> selected = CodeEvidenceSelectionPolicy.select(
                List.of(first, second, spoofed), 2, CodeEvidenceRetentionPlan.empty());

        assertThat(selected).containsExactly(first, second).doesNotContain(spoofed);
    }

    @Test
    void ignoresNullEntriesAndEvidenceWithoutAStableChunkIdentity() {
        CodeSearchResult valid = result("src/Valid.java", Map.of());
        CodeSearchResult missingIdentity = new CodeSearchResult(
                null, UUID.randomUUID(), UUID.randomUUID(), "repo", "src/Unknown.java",
                "method", "test", "Test", "test", "app", null, null, 1,
                1, 20, "void test() {}", 0.9, null);
        List<CodeSearchResult> ranked = new ArrayList<>();
        ranked.add(null);
        ranked.add(missingIdentity);
        ranked.add(valid);

        assertThat(CodeEvidenceSelectionPolicy.select(ranked, 4)).containsExactly(valid);
        assertThat(CodeEvidenceSelectionPolicy.selectFinalEvidence(
                ranked, List.of(missingIdentity), 4, null)).containsExactly(valid);
        assertThat(CodeEvidenceSelectionPolicy.selectFinalEvidence(null, null, 4, null)).isEmpty();
    }

    private CodeSearchResult result(String path, Map<String, Object> metadata) {
        return result(path, 0.8, metadata);
    }

    private CodeSearchResult result(String path, double score, Map<String, Object> metadata) {
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", path,
                "method", "test", "Test", "test", "app", null, null, 1,
                1, 20, "void test() {}", score, metadata);
    }

    private CodeEvidenceItem item(CodeSearchResult result, CodeIntelligenceAuthority authority) {
        return new CodeEvidenceItem(CodeEvidenceItem.evidenceId(result), result,
                Set.of(CodeEvidenceItem.Kind.DIRECT_SOURCE), authority);
    }
}
