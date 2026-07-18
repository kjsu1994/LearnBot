package com.learnbot.service.coderag.retrieval;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.ActiveCodeIndexIdentity;
import com.learnbot.service.RagPipelineService;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.CodeQuestionMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeGraphClosurePlannerTest {

    @Test
    void addsOneBoundedBidirectionalCallTraversalForAnExactFlowRead() {
        GraphFixture fixture = graphFixture();
        RagPipelineService.CodeEvidenceFollowUpPlan augmented = new CodeGraphClosurePlanner().augment(
                CodeQuestionMode.CALL_FLOW, plan(fixture, List.of(readOperation(fixture))),
                fixture.map(), Set.of());

        assertThat(augmented.operations()).hasSize(2);
        RagPipelineService.CodeSearchOperation closure = augmented.operations().get(1);
        assertThat(closure.type()).isEqualTo("traverse_graph");
        assertThat(closure.chunkId()).isEqualTo(fixture.seedChunkId().toString());
        assertThat(closure.relations()).containsExactly("CALLS");
        assertThat(closure.direction()).isEqualTo("BOTH");
        assertThat(closure.maxHops()).isEqualTo(2);
        assertThat(closure.claimIds()).containsExactly("claim-1");
        assertThat(closure.originEvidenceIds()).containsExactly(fixture.sourceEvidenceId());
        assertThat(new CodeRetrievalPlanValidator()
                .validate(augmented, fixture.map(), Set.of()).executableOperations())
                .hasSize(2);
    }

    @Test
    void leavesNonFlowModesUnchanged() {
        GraphFixture fixture = graphFixture();
        RagPipelineService.CodeEvidenceFollowUpPlan original = plan(
                fixture, List.of(readOperation(fixture)));

        RagPipelineService.CodeEvidenceFollowUpPlan augmented = new CodeGraphClosurePlanner().augment(
                CodeQuestionMode.EXPLAIN_METHOD, original, fixture.map(), Set.of());

        assertThat(augmented).isSameAs(original);
    }

    @Test
    void usesAUniqueObservedSymbolChunkBeforeItsRelationsEnterThePromptMap() {
        GraphFixture fixture = graphFixture();
        RepositoryQuestionMapBuilder.RepositoryQuestionMap relationless =
                new RepositoryQuestionMapBuilder.RepositoryQuestionMap(
                        fixture.map().schemaVersion(), fixture.map().revision(),
                        fixture.map().questionFingerprint(), fixture.map().identity(), fixture.map().manifest(),
                        fixture.map().symbolInventories(), fixture.map().evidence(),
                        fixture.map().codeIntelligenceIr(), List.of(), fixture.map().diagnostics(),
                        fixture.map().failures(), fixture.map().observations(), fixture.map().delta());

        RagPipelineService.CodeEvidenceFollowUpPlan augmented = new CodeGraphClosurePlanner().augment(
                CodeQuestionMode.CALL_FLOW, plan(fixture, List.of(readOperation(fixture))),
                relationless, Set.of());

        assertThat(augmented.operations()).hasSize(2);
        assertThat(augmented.operations().get(1).chunkId())
                .isEqualTo(fixture.seedChunkId().toString());
    }

    @Test
    void doesNotRepeatAnExecutedClosureForTheSameSeed() {
        GraphFixture fixture = graphFixture();
        RagPipelineService.CodeSearchOperation executed = new RagPipelineService.CodeSearchOperation(
                "traverse_graph", "", "behavior", "flow", "", "",
                fixture.seedChunkId().toString(), null, null, null,
                List.of("CALLS"), "BOTH", 2, "previous-closure",
                List.of("claim-1"), List.of(fixture.sourceEvidenceId()));

        RagPipelineService.CodeEvidenceFollowUpPlan augmented = new CodeGraphClosurePlanner().augment(
                CodeQuestionMode.CALL_FLOW, plan(fixture, List.of(readOperation(fixture))), fixture.map(),
                Set.of(CodeRetrievalCoordinator.operationKey(executed)));

        assertThat(augmented.operations()).hasSize(1);
    }

    private RagPipelineService.CodeEvidenceFollowUpPlan plan(
            GraphFixture fixture,
            List<RagPipelineService.CodeSearchOperation> operations
    ) {
        RagPipelineService.CodeEvidenceChecklistItem claim = new RagPipelineService.CodeEvidenceChecklistItem(
                "claim-1", "flow", "trace the call flow", List.of("trace the call flow"));
        return new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "read the selected implementation", List.of("claim-1"), List.of(), List.of(),
                List.of("flow"), List.of(claim), operations, List.of(), "hypothesis", 1,
                "UNRESOLVED", List.of(), "NONE");
    }

    private RagPipelineService.CodeSearchOperation readOperation(GraphFixture fixture) {
        return new RagPipelineService.CodeSearchOperation(
                "read_file_range", "", "behavior", "flow", fixture.sourcePath(), fixture.sourceSymbol(), "",
                10, 20, null, List.of(), "BOTH", null, "read-source",
                List.of("claim-1"), List.of(fixture.sourceEvidenceId()));
    }

    private GraphFixture graphFixture() {
        UUID repositoryId = UUID.randomUUID();
        UUID indexVersion = UUID.randomUUID();
        UUID seed = UUID.randomUUID();
        String sourcePath = "src/Pipeline.java";
        String sourceSymbol = "coordinate";
        String sourceEvidenceId = indexVersion + ":" + seed + ":10-20";
        CodeSearchResult source = new CodeSearchResult(
                seed, repositoryId, UUID.randomUUID(), "repository", sourcePath, "method",
                sourceSymbol, "Pipeline", sourceSymbol, "sample", null, null,
                0, 10, 20, "void coordinate() { store(); publish(); }", 0.9,
                Map.of("indexVersion", indexVersion.toString()));
        RepositoryQuestionMapBuilder.EvidenceEntry sourceEntry =
                new RepositoryQuestionMapBuilder.EvidenceEntry(
                        sourceEvidenceId, "IMPLEMENTATION_BODY", "DIRECT_SOURCE", sourcePath,
                        sourceSymbol, 10, 20, seed, 0.9, 1, "OPERATION", source.content(), source);
        List<RepositoryQuestionMapBuilder.RelationEvidence> relations = List.of(
                relation(indexVersion, sourceEvidenceId, seed, sourcePath, sourceSymbol,
                        "src/Store.java", "store"),
                relation(indexVersion, sourceEvidenceId, seed, sourcePath, sourceSymbol,
                        "src/Publisher.java", "publish"));
        ActiveCodeIndexIdentity identity = new ActiveCodeIndexIdentity(
                repositoryId, null, indexVersion, "fingerprint", "analyzer", "schema", "READY", "1", "1");
        RepositoryQuestionMapBuilder.RepositoryQuestionMap map =
                new RepositoryQuestionMapBuilder.RepositoryQuestionMap(
                        4, 2, "question", identity,
                        RepositoryQuestionMapBuilder.RepositoryManifest.empty(), Map.of(),
                        Map.of(sourceEvidenceId, sourceEntry), CodeEvidenceIr.empty(), relations,
                        List.of(), List.of(), List.of(),
                        new RepositoryQuestionMapBuilder.MapDelta(1, 2, List.of(), List.of(), false, true));
        return new GraphFixture(map, seed, sourcePath, sourceSymbol, sourceEvidenceId);
    }

    private RepositoryQuestionMapBuilder.RelationEvidence relation(
            UUID indexVersion,
            String sourceEvidenceId,
            UUID seed,
            String sourcePath,
            String sourceSymbol,
            String targetPath,
            String targetSymbol
    ) {
        return new RepositoryQuestionMapBuilder.RelationEvidence(
                indexVersion + ":graph-relation:" + UUID.randomUUID(), sourceSymbol, "CALLS", targetSymbol,
                "FORWARD", 0.99, "NAVIGATION_ONLY", sourceEvidenceId,
                sourcePath, seed, targetPath, UUID.randomUUID(), true, 0.9);
    }

    private record GraphFixture(
            RepositoryQuestionMapBuilder.RepositoryQuestionMap map,
            UUID seedChunkId,
            String sourcePath,
            String sourceSymbol,
            String sourceEvidenceId
    ) {
    }
}
