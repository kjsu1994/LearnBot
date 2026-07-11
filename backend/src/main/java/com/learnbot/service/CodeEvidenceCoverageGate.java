package com.learnbot.service;

import com.learnbot.dto.CodeSearchResult;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class CodeEvidenceCoverageGate {
    Outcome evaluate(
            RagPipelineService.CodeEvidenceFollowUpPlan plan,
            List<CodeSearchResult> evidence
    ) {
        List<CodeSearchResult> safeEvidence = evidence == null ? List.of() : evidence;
        if (safeEvidence.isEmpty()) {
            return new Outcome(false, List.of("no evidence was retrieved"), List.of());
        }
        if (plan == null || !plan.attempted()) {
            return new Outcome(false, List.of("evidence sufficiency was not evaluated"), List.of());
        }
        if (!plan.enough()) {
            List<String> missing = plan.missingAreas().isEmpty()
                    ? List.of("the evidence planner did not confirm sufficiency")
                    : plan.missingAreas();
            return new Outcome(false, missing, declaredGroups(plan));
        }

        List<String> requiredGroups = declaredGroups(plan);
        Set<String> coveredGroups = new LinkedHashSet<>();
        Set<String> identities = new LinkedHashSet<>();
        for (CodeSearchResult result : safeEvidence) {
            if (result == null || result.content() == null || result.content().isBlank()) {
                continue;
            }
            Map<String, Object> metadata = result.metadata() == null ? Map.of() : result.metadata();
            addGroup(coveredGroups, metadata.get("llmValidatedEvidenceGroup"));
            if (metadata.containsKey("llmValidatedEvidenceGroup") && !hasSupportedClaim(metadata)) {
                return new Outcome(false, List.of("validated evidence has no directly supported claim"), requiredGroups);
            }
            addIdentity(identities, metadata);
        }
        if (identities.size() > 1) {
            return new Outcome(false, List.of("evidence comes from multiple index identities"), requiredGroups);
        }
        List<String> missingGroups = requiredGroups.stream()
                .filter(group -> !coveredGroups.contains(group))
                .toList();
        if (!missingGroups.isEmpty()) {
            return new Outcome(false,
                    missingGroups.stream().map(group -> "missing evidence group: " + group).toList(),
                    requiredGroups);
        }
        return new Outcome(true, List.of(), requiredGroups);
    }

    private boolean hasSupportedClaim(Map<String, Object> metadata) {
        Object value = metadata.get("llmSupportedClaims");
        return value instanceof Collection<?> values
                && values.stream().anyMatch(item -> item != null && !String.valueOf(item).isBlank());
    }

    private void addIdentity(Set<String> identities, Map<String, Object> metadata) {
        String indexVersion = String.valueOf(metadata.getOrDefault("indexVersion", "")).trim();
        String fingerprint = String.valueOf(metadata.getOrDefault("contentFingerprint", "")).trim();
        if (!indexVersion.isBlank() || !fingerprint.isBlank()) identities.add(indexVersion + "|" + fingerprint);
    }

    private List<String> declaredGroups(RagPipelineService.CodeEvidenceFollowUpPlan plan) {
        LinkedHashSet<String> groups = new LinkedHashSet<>();
        for (String group : plan.requiredEvidenceGroups()) {
            addGroup(groups, group);
        }
        return List.copyOf(groups);
    }

    private void addGroup(Set<String> groups, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Collection<?> values) {
            values.forEach(item -> addGroup(groups, item));
            return;
        }
        String group = String.valueOf(value).trim().toLowerCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        if (!group.isBlank() && !"unknown".equals(group)) {
            groups.add(group);
        }
    }

    record Outcome(boolean sufficient, List<String> missingReasons, List<String> requiredGroups) {
        Outcome {
            missingReasons = missingReasons == null ? List.of() : List.copyOf(missingReasons);
            requiredGroups = requiredGroups == null ? List.of() : List.copyOf(requiredGroups);
        }
    }
}
