package com.learnbot.service.coderag.retrieval;

import com.learnbot.service.RagPipelineService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CodeRetrievalPlanValidator {
    public PlanValidationResult validate(
            RagPipelineService.CodeEvidenceFollowUpPlan plan,
            RepositoryQuestionMapBuilder.RepositoryQuestionMap repositoryMap,
            Set<String> executedOperationKeys
    ) {
        if (plan == null) {
            return invalid(PlanValidationCode.INVALID_NO_EXECUTABLE_OPERATION, "plan is missing");
        }
        Set<String> knownClaims = plan.checklist().stream()
                .map(RagPipelineService.CodeEvidenceChecklistItem::claimId)
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> resolvedClaims = plan.claimResults().stream()
                .filter(RagPipelineService.CodeClaimResult::terminalWithEvidence)
                .map(RagPipelineService.CodeClaimResult::claimId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> unresolvedClaims = new LinkedHashSet<>(knownClaims);
        unresolvedClaims.removeAll(resolvedClaims);
        Set<String> operationIds = new LinkedHashSet<>();
        Set<String> fingerprints = new LinkedHashSet<>();
        List<RagPipelineService.CodeSearchOperation> executable = new ArrayList<>();
        List<PlanValidationError> errors = new ArrayList<>();

        for (RagPipelineService.CodeSearchOperation requested : plan.operations()) {
            RagPipelineService.CodeSearchOperation operation = normalizeObservedRead(requested, repositoryMap);
            operation = bindObservedOrigin(operation, repositoryMap);
            PlanValidationCode code = validateOperation(
                    operation, repositoryMap, knownClaims, unresolvedClaims,
                    operationIds, fingerprints, executedOperationKeys == null ? Set.of() : executedOperationKeys);
            if (code == PlanValidationCode.VALID) {
                executable.add(operation);
            } else {
                errors.add(new PlanValidationError(
                        code, operation.operationId(), validationDetail(code, operation, repositoryMap)));
            }
        }
        if (!errors.isEmpty()) {
            return new PlanValidationResult(errors.get(0).code(), List.copyOf(errors), List.copyOf(executable));
        }
        boolean explicitStop = !"NONE".equals(plan.terminationRequest());
        if (!plan.enough() && !unresolvedClaims.isEmpty() && executable.isEmpty() && !explicitStop) {
            return invalid(PlanValidationCode.INVALID_NO_EXECUTABLE_OPERATION,
                    "unresolved claims remain but the plan has no executable operation");
        }
        return new PlanValidationResult(PlanValidationCode.VALID, List.of(), List.copyOf(executable));
    }

    private RagPipelineService.CodeSearchOperation normalizeObservedRead(
            RagPipelineService.CodeSearchOperation operation,
            RepositoryQuestionMapBuilder.RepositoryQuestionMap repositoryMap
    ) {
        if (operation == null || repositoryMap == null
                || !"read_file_range".equals(operation.type())
                || (operation.lineStart() != null && operation.lineEnd() != null)
                || operation.path().isBlank() || operation.symbol().isBlank()
                || !repositoryMap.observesSymbol(operation.path(), operation.symbol())) {
            return operation;
        }
        return new RagPipelineService.CodeSearchOperation(
                "read_symbol", operation.query(), operation.area(), operation.evidenceGroup(),
                operation.path(), operation.symbol(), "", null, null, operation.radius(),
                operation.relations(), operation.direction(), operation.maxHops(), operation.operationId(),
                operation.claimIds(), operation.originEvidenceIds());
    }

    private RagPipelineService.CodeSearchOperation bindObservedOrigin(
            RagPipelineService.CodeSearchOperation operation,
            RepositoryQuestionMapBuilder.RepositoryQuestionMap repositoryMap
    ) {
        if (operation == null || !operation.isDirectRead() || repositoryMap == null) return operation;
        boolean suppliedOriginsAreValid = !operation.originEvidenceIds().isEmpty()
                && operation.originEvidenceIds().stream().allMatch(repositoryMap::containsEvidenceId)
                && (operation.path().isBlank() || operation.originEvidenceIds().stream()
                .anyMatch(id -> repositoryMap.originSupportsPath(id, operation.path())));
        if (suppliedOriginsAreValid) return operation;
        String origin = repositoryMap.originEvidenceIdFor(operation);
        if (origin.isBlank()) return operation;
        return new RagPipelineService.CodeSearchOperation(
                operation.type(), operation.query(), operation.area(), operation.evidenceGroup(),
                operation.path(), operation.symbol(), operation.chunkId(), operation.lineStart(),
                operation.lineEnd(), operation.radius(), operation.relations(), operation.direction(),
                operation.maxHops(), operation.operationId(), operation.claimIds(), List.of(origin));
    }

    private String validationDetail(
            PlanValidationCode code,
            RagPipelineService.CodeSearchOperation operation,
            RepositoryQuestionMapBuilder.RepositoryQuestionMap repositoryMap
    ) {
        if (!operation.validationError().isBlank()) return operation.validationError();
        if (code == PlanValidationCode.INVALID_MISSING_ORIGIN && repositoryMap != null) {
            List<String> candidates = repositoryMap.originEvidenceIdsFor(operation);
            return candidates.isEmpty()
                    ? "originEvidenceIds requires an observed evidence ID matching the requested operand"
                    : "originEvidenceIds requires one of these observed matching IDs: " + candidates;
        }
        return "";
    }

    private PlanValidationCode validateOperation(
            RagPipelineService.CodeSearchOperation operation,
            RepositoryQuestionMapBuilder.RepositoryQuestionMap repositoryMap,
            Set<String> knownClaims,
            Set<String> unresolvedClaims,
            Set<String> operationIds,
            Set<String> fingerprints,
            Set<String> executedOperationKeys
    ) {
        if (operation.operationId().isBlank()) return PlanValidationCode.INVALID_MISSING_OPERATION_ID;
        if (!operationIds.add(operation.operationId())) return PlanValidationCode.INVALID_DUPLICATE_OPERATION_ID;
        if (operation.claimIds().isEmpty()) return PlanValidationCode.INVALID_MISSING_CLAIM_IDS;
        if (operation.claimIds().stream().anyMatch(id -> !knownClaims.contains(id))) {
            return PlanValidationCode.INVALID_UNKNOWN_CLAIM_ID;
        }
        if (operation.claimIds().stream().noneMatch(unresolvedClaims::contains)) {
            return PlanValidationCode.INVALID_RESOLVED_CLAIM_TARGET;
        }
        if (!operation.validationError().isBlank()) return PlanValidationCode.INVALID_OPERAND;
        String fingerprint = fingerprint(operation);
        if (!fingerprints.add(fingerprint) || executedOperationKeys.contains(fingerprint)) {
            return PlanValidationCode.INVALID_DUPLICATE_OPERATION;
        }
        if (operation.isSearch()) return PlanValidationCode.VALID;
        if (operation.originEvidenceIds().isEmpty()) return PlanValidationCode.INVALID_MISSING_ORIGIN;
        if (repositoryMap == null || operation.originEvidenceIds().stream().anyMatch(id -> !repositoryMap.containsEvidenceId(id))) {
            return PlanValidationCode.INVALID_UNKNOWN_ORIGIN;
        }
        if (!operation.path().isBlank()) {
            if (!repositoryMap.observesPath(operation.path())) return PlanValidationCode.INVALID_UNOBSERVED_OPERAND;
            if (operation.originEvidenceIds().stream().noneMatch(id -> repositoryMap.originSupportsPath(id, operation.path()))) {
                return PlanValidationCode.INVALID_ORIGIN_OPERAND_MISMATCH;
            }
        }
        if ("read_symbol".equals(operation.type())
                && !repositoryMap.observesSymbol(operation.path(), operation.symbol())) {
            return PlanValidationCode.INVALID_UNOBSERVED_OPERAND;
        }
        if (("read_chunk".equals(operation.type()) || "read_adjacent".equals(operation.type())
                || "traverse_graph".equals(operation.type())) && !repositoryMap.observesChunk(operation.chunkId())) {
            return PlanValidationCode.INVALID_UNOBSERVED_OPERAND;
        }
        return PlanValidationCode.VALID;
    }

    private String fingerprint(RagPipelineService.CodeSearchOperation operation) {
        return CodeRetrievalCoordinator.operationKey(operation);
    }

    private PlanValidationResult invalid(PlanValidationCode code, String message) {
        return new PlanValidationResult(code, List.of(new PlanValidationError(code, "", message)), List.of());
    }

    public enum PlanValidationCode {
        VALID,
        INVALID_NO_EXECUTABLE_OPERATION,
        INVALID_MISSING_OPERATION_ID,
        INVALID_DUPLICATE_OPERATION_ID,
        INVALID_MISSING_CLAIM_IDS,
        INVALID_UNKNOWN_CLAIM_ID,
        INVALID_RESOLVED_CLAIM_TARGET,
        INVALID_MISSING_ORIGIN,
        INVALID_UNKNOWN_ORIGIN,
        INVALID_ORIGIN_OPERAND_MISMATCH,
        INVALID_UNOBSERVED_OPERAND,
        INVALID_OPERAND,
        INVALID_DUPLICATE_OPERATION
    }

    public record PlanValidationError(PlanValidationCode code, String operationId, String detail) {
    }

    public record PlanValidationResult(
            PlanValidationCode code,
            List<PlanValidationError> errors,
            List<RagPipelineService.CodeSearchOperation> executableOperations
    ) {
        public boolean valid() {
            return code == PlanValidationCode.VALID;
        }
    }
}
