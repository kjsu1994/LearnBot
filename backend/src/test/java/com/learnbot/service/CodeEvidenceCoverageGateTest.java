package com.learnbot.service;

import com.learnbot.dto.CodeSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeEvidenceCoverageGateTest {
    private final CodeEvidenceCoverageGate gate = new CodeEvidenceCoverageGate();

    @Test
    void blocksWhenPlannerStillReportsMissingEvidence() {
        var plan = plan(false, List.of("response storage"), List.of("persistence_update"));

        var outcome = gate.evaluate(plan, List.of(evidence("persistence_update")));

        assertThat(outcome.sufficient()).isFalse();
        assertThat(outcome.missingReasons()).containsExactly("response storage");
    }

    @Test
    void blocksWhenDeclaredGroupHasNoExecutedEvidence() {
        var plan = plan(true, List.of(), List.of("queue_claim", "response_intake"));

        var outcome = gate.evaluate(plan, List.of(evidence("queue_claim")));

        assertThat(outcome.sufficient()).isFalse();
        assertThat(outcome.missingReasons()).containsExactly("missing evidence group: response_intake");
    }

    @Test
    void passesOnlyTheGroupsDeclaredByThePlanner() {
        var plan = plan(true, List.of(), List.of("queue_claim", "response_intake"));

        var outcome = gate.evaluate(plan, List.of(evidence("queue_claim"), evidence("response_intake")));

        assertThat(outcome.sufficient()).isTrue();
        assertThat(outcome.requiredGroups()).containsExactly("queue_claim", "response_intake");
    }

    @Test
    void doesNotTurnTheInitialChecklistIntoARequiredServerGate() {
        var checklist = List.of(new RagPipelineService.CodeEvidenceChecklistItem(
                "optional-transport", "async_transport", "Check transport only when relevant", List.of("transport")));
        var plan = new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, true, "transport is irrelevant", List.of(), List.of(), List.of(),
                List.of("queue_claim"), checklist, List.of());

        var outcome = gate.evaluate(plan, List.of(evidence("queue_claim")));

        assertThat(outcome.sufficient()).isTrue();
        assertThat(outcome.requiredGroups()).containsExactly("queue_claim");
    }

    @Test
    void recognizesMergedCoverageGroupCollections() {
        var plan = plan(true, List.of(), List.of("queue_claim", "response_intake"));
        CodeSearchResult mergedEvidence = evidence(List.of("queue_claim", "response_intake"));

        var outcome = gate.evaluate(plan, List.of(mergedEvidence));

        assertThat(outcome.sufficient()).isTrue();
    }

    @Test
    void rejectsProvisionalSearchPlanCoverageWithoutValidatorSelection() {
        var plan = plan(true, List.of(), List.of("queue_claim"));
        CodeSearchResult provisional = new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", "src/Worker.java",
                "method", "work", "Worker", "work", "app", null, null, 1,
                1, 20, "void work() {}", 0.8, Map.of("llmEvidenceCoverageGroup", "queue_claim"));

        var outcome = gate.evaluate(plan, List.of(provisional));

        assertThat(outcome.sufficient()).isFalse();
        assertThat(outcome.missingReasons()).containsExactly("missing evidence group: queue_claim");
    }

    @Test
    void rejectsValidatedGroupWithoutDirectClaim() {
        var plan = plan(true, List.of(), List.of("graph_failure"));
        CodeSearchResult evidence = new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", "src/Graph.java",
                "method", "build", "Graph", "build", "app", null, null, 1,
                1, 20, "void build() {}", 0.8, Map.of("llmValidatedEvidenceGroup", "graph_failure"));

        var outcome = gate.evaluate(plan, List.of(evidence));

        assertThat(outcome.sufficient()).isFalse();
        assertThat(outcome.missingReasons()).containsExactly("validated evidence has no directly supported claim");
    }

    @Test
    void rejectsEvidenceMixedAcrossIndexIdentities() {
        var plan = plan(true, List.of(), List.of("graph_failure", "base_graph"));
        CodeSearchResult first = evidenceWithIdentity("graph_failure", "index-a");
        CodeSearchResult second = evidenceWithIdentity("base_graph", "index-b");

        var outcome = gate.evaluate(plan, List.of(first, second));

        assertThat(outcome.sufficient()).isFalse();
        assertThat(outcome.missingReasons()).containsExactly("evidence comes from multiple index identities");
    }

    private RagPipelineService.CodeEvidenceFollowUpPlan plan(
            boolean enough,
            List<String> missing,
            List<String> groups
    ) {
        return new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, enough, "test", missing, List.of(), List.of(), groups, List.of(), List.of());
    }

    private CodeSearchResult evidence(String group) {
        return evidence((Object) group);
    }

    private CodeSearchResult evidence(Object group) {
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", "src/Worker.java",
                "method", "work", "Worker", "work", "app", null, null, 1,
                1, 20, "void work() {}", 0.8, Map.of(
                        "llmValidatedEvidenceGroup", group,
                        "llmSupportedClaims", List.of("work is directly implemented")));
    }

    private CodeSearchResult evidenceWithIdentity(String group, String indexVersion) {
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", "src/Worker.java",
                "method", "work", "Worker", "work", "app", null, null, 1,
                1, 20, "void work() {}", 0.8, Map.of(
                        "llmValidatedEvidenceGroup", group,
                        "llmSupportedClaims", List.of("direct claim for " + group),
                        "indexVersion", indexVersion));
    }
}
