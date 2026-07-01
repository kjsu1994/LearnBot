package com.learnbot.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class LocalAgentPatchMutationInputBuilder {
    private LocalAgentPatchMutationInputBuilder() {
    }

    static Map<String, Object> build(
            Map<String, Object> sourceInput,
            Map<String, Object> linkedPatchDryRunOutput,
            UUID sourceRequestId,
            UUID releaseAttemptId
    ) {
        if (sourceInput == null || sourceInput.isEmpty()) {
            throw new IllegalArgumentException("Patch mutation release requires source request input.");
        }
        if (linkedPatchDryRunOutput == null || linkedPatchDryRunOutput.isEmpty()) {
            throw new IllegalArgumentException("Patch mutation release requires linked patch dry-run output.");
        }
        if (!Boolean.TRUE.equals(linkedPatchDryRunOutput.get("dryRun"))
                || !Boolean.TRUE.equals(linkedPatchDryRunOutput.get("preflightPassed"))
                || !Boolean.FALSE.equals(linkedPatchDryRunOutput.get("mutationApplied"))) {
            throw new IllegalArgumentException("Patch mutation release requires a successful non-mutating dry-run.");
        }

        String manifestId = snapshotManifestId(linkedPatchDryRunOutput);
        if (manifestId == null || manifestId.isBlank()) {
            throw new IllegalArgumentException("Patch mutation release requires a managed snapshot manifest id.");
        }

        Map<String, Object> mutationInput = new LinkedHashMap<>(sourceInput);
        mutationInput.put("dryRunOnly", false);
        mutationInput.put("mutationAllowed", true);
        mutationInput.put("manifestId", manifestId);
        mutationInput.put("snapshotManifestId", manifestId);
        mutationInput.put("sourceRequestId", sourceRequestId.toString());
        mutationInput.put("releaseAttemptId", releaseAttemptId.toString());
        mutationInput.put("freshObservationOnly", false);
        mutationInput.put("releaseMutationInputSchema", "learnbot.local-agent.patch-mutation-input.v1");
        mutationInput.put("mutationPreflight", Map.of(
                "sourceRequestId", sourceRequestId.toString(),
                "releaseAttemptId", releaseAttemptId.toString(),
                "dryRun", true,
                "preflightPassed", true,
                "mutationApplied", false,
                "snapshotCreated", Boolean.TRUE.equals(linkedPatchDryRunOutput.get("snapshotCreated")),
                "manifestId", manifestId
        ));
        return Map.copyOf(mutationInput);
    }

    @SuppressWarnings("unchecked")
    private static String snapshotManifestId(Map<String, Object> dryRunOutput) {
        Object direct = dryRunOutput.get("snapshotManifestId");
        if (direct instanceof String text && !text.isBlank()) {
            return text;
        }
        Object manifestId = dryRunOutput.get("manifestId");
        if (manifestId instanceof String text && !text.isBlank()) {
            return text;
        }
        Object snapshotObservation = dryRunOutput.get("snapshotObservation");
        if (snapshotObservation instanceof Map<?, ?> snapshotMap) {
            Object manifestPreview = snapshotMap.get("manifestPreview");
            if (manifestPreview instanceof Map<?, ?> manifestMap) {
                Object id = manifestMap.get("id");
                if (id instanceof String text && !text.isBlank()) {
                    return text;
                }
            }
        }
        Object manifests = dryRunOutput.get("manifests");
        if (manifests instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> manifest) {
            Object id = manifest.get("id");
            if (id instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }
}
