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
        return evaluate(plan, evidence, "");
    }

    Outcome evaluate(
            RagPipelineService.CodeEvidenceFollowUpPlan plan,
            List<CodeSearchResult> evidence,
            String expectedIndexVersion
    ) {
        List<CodeSearchResult> safeEvidence = evidence == null ? List.of() : evidence;
        if (safeEvidence.isEmpty()) {
            return new Outcome(Decision.DENY, List.of("no evidence was retrieved"), List.of(), List.of());
        }

        List<String> requiredGroups = plan == null ? List.of() : declaredGroups(plan);
        Set<String> coveredGroups = new LinkedHashSet<>();
        Set<String> identities = new LinkedHashSet<>();
        for (CodeSearchResult result : safeEvidence) {
            if (result == null || result.content() == null || result.content().isBlank()) {
                continue;
            }
            Map<String, Object> metadata = result.metadata() == null ? Map.of() : result.metadata();
            addGroup(coveredGroups, metadata.get("llmValidatedEvidenceGroup"));
            if (metadata.containsKey("llmValidatedEvidenceGroup")) {
                if (!hasSupportedClaim(metadata)) {
                    return new Outcome(Decision.DENY,
                            List.of("validated evidence has no directly supported claim"), requiredGroups, List.of());
                }
                if (!hasIdentity(metadata)) {
                    return new Outcome(Decision.DENY,
                            List.of("validated evidence has no index identity"), requiredGroups, List.of());
                }
            }
            addIdentity(identities, metadata);
        }
        if (identities.size() > 1) {
            return new Outcome(Decision.DENY,
                    List.of("evidence comes from multiple index identities"), requiredGroups, List.of());
        }
        if (expectedIndexVersion != null && !expectedIndexVersion.isBlank()
                && identities.stream().noneMatch(identity -> identity.startsWith(expectedIndexVersion + "|"))) {
            return new Outcome(Decision.DENY,
                    List.of("evidence does not belong to the pinned active index"), requiredGroups, List.of());
        }
        if (plan == null || !plan.attempted()) {
            return new Outcome(Decision.DISCOVERY,
                    List.of("evidence sufficiency was not evaluated"), requiredGroups, List.of());
        }

        Set<String> availableEvidenceIds = safeEvidence.stream()
                .filter(java.util.Objects::nonNull)
                .map(CodeEvidenceId::from)
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        Set<String> requiredClaimIds = plan.claimResults().isEmpty()
                ? Set.of()
                : plan.checklist().stream()
                        .map(RagPipelineService.CodeEvidenceChecklistItem::claimId)
                        .filter(value -> value != null && !value.isBlank())
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, RagPipelineService.CodeClaimResult> resultsByClaim = plan.claimResults().stream()
                .collect(java.util.stream.Collectors.toMap(
                        RagPipelineService.CodeClaimResult::claimId,
                        result -> result,
                        (left, right) -> right));
        Map<String, String> displayGoals = plan.checklist().stream()
                .collect(java.util.stream.Collectors.toMap(
                        RagPipelineService.CodeEvidenceChecklistItem::claimId,
                        item -> displayGoal(item),
                        (left, right) -> left));
        List<String> resolvedClaims = new java.util.ArrayList<>();
        List<String> missingReasons = new java.util.ArrayList<>();
        for (String claimId : requiredClaimIds) {
            RagPipelineService.CodeClaimResult result = resultsByClaim.get(claimId);
            if (result == null || !result.terminalWithEvidence()) {
                missingReasons.add(displayGoals.getOrDefault(claimId, "requested behavior is not yet verified"));
                continue;
            }
            List<String> missingIds = result.evidenceIds().stream()
                    .filter(id -> !availableEvidenceIds.contains(id))
                    .toList();
            if (!missingIds.isEmpty()) {
                missingReasons.add(displayGoals.getOrDefault(claimId, "verified evidence was not retained in the final context"));
                continue;
            }
            resolvedClaims.add(claimId);
        }
        if (!plan.enough()) {
            if (plan.missingAreas().isEmpty()) {
                missingReasons.add("the evidence planner did not confirm sufficiency");
            } else {
                plan.missingAreas().stream()
                        .map(area -> displayGoals.getOrDefault(area, area))
                        .filter(value -> value != null && !value.isBlank())
                        .forEach(missingReasons::add);
            }
        }
        List<String> missingGroups = requiredGroups.stream()
                .filter(group -> !coveredGroups.contains(group))
                .toList();
        if (requiredClaimIds.isEmpty()) {
            missingGroups.forEach(group -> missingReasons.add("required behavior is not yet verified"));
        }

        boolean claimsComplete = requiredClaimIds.isEmpty() || resolvedClaims.size() == requiredClaimIds.size();
        boolean groupsComplete = missingGroups.isEmpty();
        if (plan.enough() && claimsComplete && groupsComplete) {
            return new Outcome(Decision.FULL, List.of(), requiredGroups, resolvedClaims);
        }
        if (!resolvedClaims.isEmpty() || !coveredGroups.isEmpty()) {
            return new Outcome(Decision.PARTIAL, List.copyOf(new LinkedHashSet<>(missingReasons)),
                    requiredGroups, resolvedClaims);
        }
        return new Outcome(Decision.DISCOVERY, List.copyOf(new LinkedHashSet<>(missingReasons)),
                requiredGroups, List.of());
    }

    private String displayGoal(RagPipelineService.CodeEvidenceChecklistItem item) {
        if (item == null) return "requested behavior is not yet verified";
        if (item.goal() != null && !item.goal().isBlank()) return item.goal().trim();
        return java.util.stream.Stream.of(item.actor(), item.action(), item.object(), item.expectedOutcome())
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining(" "));
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

    private boolean hasIdentity(Map<String, Object> metadata) {
        String indexVersion = String.valueOf(metadata.getOrDefault("indexVersion", "")).trim();
        String fingerprint = String.valueOf(metadata.getOrDefault("contentFingerprint", "")).trim();
        return !indexVersion.isBlank() || !fingerprint.isBlank();
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

    enum Decision {
        FULL,
        PARTIAL,
        DISCOVERY,
        DENY
    }

    record Outcome(Decision decision, List<String> missingReasons, List<String> requiredGroups,
                   List<String> resolvedClaimIds) {
        Outcome {
            decision = decision == null ? Decision.DENY : decision;
            missingReasons = missingReasons == null ? List.of() : List.copyOf(missingReasons);
            requiredGroups = requiredGroups == null ? List.of() : List.copyOf(requiredGroups);
            resolvedClaimIds = resolvedClaimIds == null ? List.of() : List.copyOf(resolvedClaimIds);
        }

        boolean sufficient() {
            return decision == Decision.FULL;
        }

        boolean answerable() {
            return decision == Decision.FULL || decision == Decision.PARTIAL;
        }
    }
}
