package com.learnbot.service.coderag.evidence;

import com.learnbot.service.RagPipelineService;

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
        assertThat(outcome.missingReasons()).containsExactly("required behavior is not yet verified");
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
        assertThat(outcome.missingReasons()).containsExactly("required behavior is not yet verified");
    }

    @Test
    void rejectsValidatedGroupWithoutDirectClaim() {
        var plan = plan(true, List.of(), List.of("graph_failure"));
        CodeSearchResult evidence = new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", "src/Graph.java",
                "method", "build", "Graph", "build", "app", null, null, 1,
                1, 20, "void build() {}", 0.8, Map.of(
                        "llmValidatedEvidence", true,
                        "llmValidatedEvidenceGroup", "graph_failure"));

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

    @Test
    void rejectsValidatedEvidenceWithoutIndexIdentity() {
        var plan = plan(true, List.of(), List.of("queue_claim"));
        CodeSearchResult evidence = new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", "src/Worker.java",
                "method", "work", "Worker", "work", "app", null, null, 1,
                1, 20, "void work() {}", 0.8, Map.of(
                        "llmValidatedEvidenceGroup", "queue_claim",
                        "llmValidatedEvidence", true,
                        "llmSupportedClaims", List.of("work is directly implemented")));

        var outcome = gate.evaluate(plan, List.of(evidence));

        assertThat(outcome.sufficient()).isFalse();
        assertThat(outcome.missingReasons()).containsExactly("validated evidence has no index identity");
    }

    @Test
    void rejectsTerminalClaimWhenItsEvidenceWasDroppedFromFinalContext() {
        CodeSearchResult present = evidence("distributed_flow");
        var checklist = List.of(new RagPipelineService.CodeEvidenceChecklistItem(
                "distributed-flow", "distributed_flow", "prove distributed flow", List.of()));
        var claimResults = List.of(new RagPipelineService.CodeClaimResult(
                "distributed-flow", "SUPPORTED", List.of("index-current:missing:1-20"),
                "behavior is distributed", List.of(), ""));
        var plan = new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, true, "verified", List.of(), List.of(), List.of(), List.of("distributed_flow"),
                checklist, List.of(), List.of(), "distributed hypothesis", 2, "DISTRIBUTED", claimResults);

        var outcome = gate.evaluate(plan, List.of(present), "index-current");

        assertThat(outcome.sufficient()).isFalse();
        assertThat(outcome.missingReasons()).contains("prove distributed flow");
    }

    @Test
    void acceptsEvidenceBackedContradictedClaimAsAResolvableCorrection() {
        CodeSearchResult present = evidence("initial_premise");
        String evidenceId = CodeEvidenceId.from(present);
        var checklist = List.of(new RagPipelineService.CodeEvidenceChecklistItem(
                "initial-premise", "initial_premise", "test the premise", List.of()));
        var claimResults = List.of(new RagPipelineService.CodeClaimResult(
                "initial-premise", "CONTRADICTED", List.of(evidenceId),
                "the source directly disproves the initial premise", List.of(), "corrected-flow"));
        var plan = new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, true, "corrected", List.of(), List.of(), List.of(), List.of("initial_premise"),
                checklist, List.of(), List.of(), "corrected hypothesis", 2, "CORRECTED", claimResults);

        var outcome = gate.evaluate(plan, List.of(present), "index-current");

        assertThat(outcome.sufficient()).isTrue();
    }

    @Test
    void rejectsEvidenceOutsidePinnedActiveIndex() {
        var plan = plan(true, List.of(), List.of("queue_claim"));

        var outcome = gate.evaluate(plan, List.of(evidenceWithIdentity("queue_claim", "index-old")), "index-current");

        assertThat(outcome.sufficient()).isFalse();
        assertThat(outcome.missingReasons()).containsExactly("evidence does not belong to the pinned active index");
    }

    @Test
    void allowsPartialAnswerWhenAtLeastOneClaimHasRetainedDirectEvidence() {
        CodeSearchResult present = evidence("request_claim");
        String evidenceId = CodeEvidenceId.from(present);
        var checklist = List.of(
                new RagPipelineService.CodeEvidenceChecklistItem(
                        "request-claim", "request_claim", "prove request claim", List.of()),
                new RagPipelineService.CodeEvidenceChecklistItem(
                        "response-store", "response_store", "prove response storage", List.of()));
        var claimResults = List.of(
                new RagPipelineService.CodeClaimResult(
                        "request-claim", "SUPPORTED", List.of(evidenceId),
                        "the request is claimed", List.of(), ""),
                new RagPipelineService.CodeClaimResult(
                        "response-store", "UNRESOLVED", List.of(), "", List.of(), ""));
        var plan = new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, "partial", List.of("response storage"), List.of(), List.of(),
                List.of("request_claim", "response_store"), checklist, List.of(), List.of(),
                "distributed flow", 2, "DISTRIBUTED", claimResults);

        var outcome = gate.evaluate(plan, List.of(present), "index-current");

        assertThat(outcome.decision()).isEqualTo(CodeEvidenceCoverageGate.Decision.PARTIAL);
        assertThat(outcome.answerable()).isTrue();
        assertThat(outcome.resolvedClaimIds()).containsExactly("request-claim");
        assertThat(outcome.missingReasons()).anyMatch(reason -> reason.contains("response storage"));
    }

    @Test
    void reportsDiscoveryWhenCandidatesExistButNoClaimWasValidated() {
        CodeSearchResult candidate = new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", "src/Candidate.java",
                "method", "candidate", "Candidate", "candidate", "app", null, null, 1,
                1, 20, "void candidate() {}", 0.8, Map.of("indexVersion", "index-current"));
        var plan = plan(false, List.of("implementation body"), List.of("implementation"));

        var outcome = gate.evaluate(plan, List.of(candidate), "index-current");

        assertThat(outcome.decision()).isEqualTo(CodeEvidenceCoverageGate.Decision.DISCOVERY);
        assertThat(outcome.answerable()).isTrue();
    }

    @Test
    void ignoresAProvisionalGroupThatWasNeverExplicitlyValidated() {
        var plan = plan(true, List.of(), List.of("graph_failure"));
        CodeSearchResult provisional = new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", "src/Graph.java",
                "method", "build", "Graph", "build", "app", null, null, 1,
                1, 20, "void build() {}", 0.8, Map.of("llmValidatedEvidenceGroup", "graph_failure"));

        var outcome = gate.evaluate(plan, List.of(provisional));

        assertThat(outcome.decision()).isEqualTo(CodeEvidenceCoverageGate.Decision.DISCOVERY);
        assertThat(outcome.missingReasons()).containsExactly("required behavior is not yet verified");
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
                        "llmValidatedEvidence", true,
                        "llmSupportedClaims", List.of("work is directly implemented"),
                        "indexVersion", "index-current"));
    }

    private CodeSearchResult evidenceWithIdentity(String group, String indexVersion) {
        return new CodeSearchResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repo", "src/Worker.java",
                "method", "work", "Worker", "work", "app", null, null, 1,
                1, 20, "void work() {}", 0.8, Map.of(
                        "llmValidatedEvidenceGroup", group,
                        "llmValidatedEvidence", true,
                        "llmSupportedClaims", List.of("direct claim for " + group),
                        "indexVersion", indexVersion));
    }
}
