package com.learnbot.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalAgentPatchMutationInputBuilderTest {
    @Test
    void buildsLocalAgentCompatibleMutationInputFromLinkedDryRunSnapshot() {
        UUID sourceRequestId = UUID.randomUUID();
        UUID releaseAttemptId = UUID.randomUUID();

        Map<String, Object> input = LocalAgentPatchMutationInputBuilder.build(
                Map.of(
                        "schemaVersion", 1,
                        "diff", "--- a/README.md\n+++ b/README.md\n@@ -1 +1 @@\n-old\n+new\n",
                        "targetFiles", List.of("README.md"),
                        "expectedFiles", List.of(Map.of("path", "README.md", "sha256", "abc123")),
                        "dryRunOnly", true,
                        "mutationAllowed", false,
                        "freshObservationOnly", true
                ),
                Map.of(
                        "dryRun", true,
                        "preflightPassed", true,
                        "mutationApplied", false,
                        "snapshotCreated", true,
                        "snapshotObservation", Map.of(
                                "manifestPreview", Map.of(
                                        "id", "snap-1234",
                                        "schema", "learnbot.local-agent.snapshot-manifest.v1"
                                )
                        )
                ),
                sourceRequestId,
                releaseAttemptId
        );

        assertThat(input)
                .containsEntry("dryRunOnly", false)
                .containsEntry("mutationAllowed", true)
                .containsEntry("manifestId", "snap-1234")
                .containsEntry("snapshotManifestId", "snap-1234")
                .containsEntry("sourceRequestId", sourceRequestId.toString())
                .containsEntry("releaseAttemptId", releaseAttemptId.toString())
                .containsEntry("freshObservationOnly", false)
                .containsEntry("releaseMutationInputSchema", "learnbot.local-agent.patch-mutation-input.v1");
        assertThat(input.get("mutationPreflight")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> preflight = (Map<String, Object>) input.get("mutationPreflight");
        assertThat(preflight)
                .containsEntry("dryRun", true)
                .containsEntry("preflightPassed", true)
                .containsEntry("mutationApplied", false)
                .containsEntry("manifestId", "snap-1234");
    }

    @Test
    void refusesMutationInputWhenDryRunAlreadyMutatedFiles() {
        assertThatThrownBy(() -> LocalAgentPatchMutationInputBuilder.build(
                Map.of("diff", "patch"),
                Map.of(
                        "dryRun", true,
                        "preflightPassed", true,
                        "mutationApplied", true,
                        "snapshotManifestId", "snap-1234"
                ),
                UUID.randomUUID(),
                UUID.randomUUID()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("successful non-mutating dry-run");
    }
}
