package com.learnbot.service.coderag.retrieval;

import com.learnbot.dto.CodeSymbolOutline;
import com.learnbot.service.CodeIntelligenceAuthority;
import com.learnbot.service.RagPipelineService;
import com.learnbot.service.coderag.model.CodeNavigationHandle;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CodeRetrievalPlanValidator {
    private static final Pattern SEMANTIC_TOKEN = Pattern.compile("[\\p{L}\\p{N}_]{2,}");
    private static final Pattern CODE_SHAPED_IDENTIFIER = Pattern.compile(
            "\\b(?:[A-Za-z]+\\d[A-Za-z0-9_]*|[A-Za-z]+(?:[A-Z][A-Za-z0-9]*)+|[A-Z][A-Z0-9]+(?:_[A-Z0-9]+)+)\\b");

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
            operation = normalizeObservedTraversal(operation, repositoryMap);
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
        if (!plan.enough() && !unresolvedClaims.isEmpty() && executable.isEmpty() && explicitStop
                && hasUntriedClaimNavigation(plan.checklist(), unresolvedClaims, repositoryMap,
                executedOperationKeys == null ? Set.of() : executedOperationKeys)) {
            return invalid(PlanValidationCode.INVALID_UNTRIED_NAVIGATION,
                    "an observed claim-linked symbol remains available for a typed read operation");
        }
        if (!plan.enough() && !unresolvedClaims.isEmpty() && executable.isEmpty() && !explicitStop) {
            return invalid(PlanValidationCode.INVALID_NO_EXECUTABLE_OPERATION,
                    "unresolved claims remain but the plan has no executable operation");
        }
        return new PlanValidationResult(PlanValidationCode.VALID, List.of(), List.copyOf(executable));
    }

    /**
     * Applies the normal typed-operation checks plus a first-plan semantic invariant. Every linked
     * claim needs one query anchored in the question's behavior vocabulary. One translated/source-
     * vocabulary companion may then be used when it remains aligned with that claim's behavior.
     * Claim behavior and query coverage are compared structurally rather than through a language-specific word list.
     */
    public PlanValidationResult validateInitial(
            String question,
            RagPipelineService.CodeEvidenceFollowUpPlan plan,
            RepositoryQuestionMapBuilder.RepositoryQuestionMap repositoryMap,
            Set<String> executedOperationKeys
    ) {
        PlanValidationResult base = validate(plan, repositoryMap, executedOperationKeys);
        if (plan == null || base.executableOperations().isEmpty()) {
            return base;
        }
        Map<String, RagPipelineService.CodeEvidenceChecklistItem> claims = plan.checklist().stream()
                .collect(java.util.stream.Collectors.toMap(
                        RagPipelineService.CodeEvidenceChecklistItem::claimId,
                        value -> value,
                        (left, right) -> left));
        Set<String> questionAnchors = distinctiveTokens(question);
        List<RagPipelineService.CodeSearchOperation> anchoredOperations = base.executableOperations().stream()
                .filter(RagPipelineService.CodeSearchOperation::isSearch)
                .filter(operation -> isQuestionAnchorForAllClaims(operation, claims, questionAnchors))
                .toList();
        Set<String> claimsWithAnchoredQuery = anchoredOperations.stream()
                .flatMap(operation -> operation.claimIds().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, Integer> companionCounts = new java.util.HashMap<>();
        List<RagPipelineService.CodeSearchOperation> executable = new ArrayList<>();
        List<PlanValidationError> errors = new ArrayList<>(base.errors());
        for (RagPipelineService.CodeSearchOperation operation : base.executableOperations()) {
            if (!operation.isSearch()
                    || anchoredOperations.contains(operation)
                    || isBoundedClaimCompanion(operation, claims, claimsWithAnchoredQuery, companionCounts)
                    || isObservedCompositeCallableQuery(operation, repositoryMap)) {
                executable.add(operation);
                continue;
            }
            errors.add(new PlanValidationError(
                    PlanValidationCode.INVALID_QUERY_CLAIM_MISMATCH,
                    operation.operationId(),
                    "each claim needs a question-behavior anchor before one aligned source-vocabulary companion"));
        }
        if (errors.isEmpty()) {
            return base;
        }
        return new PlanValidationResult(
                errors.get(0).code(), List.copyOf(errors), List.copyOf(executable));
    }

    /**
     * Bridges multilingual questions to source vocabulary only through a callable
     * that the active repository map already observed. A composite callable name
     * is required so generic verbs and broad type/container names cannot bypass
     * the question/claim anchoring contract.
     */
    private boolean isObservedCompositeCallableQuery(
            RagPipelineService.CodeSearchOperation operation,
            RepositoryQuestionMapBuilder.RepositoryQuestionMap repositoryMap
    ) {
        if (operation == null || !operation.isSearch() || operation.query().isBlank()
                || repositoryMap == null) {
            return false;
        }
        java.util.stream.Stream<String> inventoryCallables = repositoryMap.symbolInventories().values().stream()
                .flatMap(inventory -> inventory.symbols().stream())
                .filter(this::isTrustedCallableOutline)
                .map(CodeSymbolOutline::name)
                .map(CodeRetrievalPlanValidator::canonicalSymbol);
        java.util.stream.Stream<String> observedCalls = repositoryMap.navigationHandles().stream()
                .filter(handle -> handle.kind() == CodeNavigationHandle.Kind.CALL)
                .map(CodeNavigationHandle::symbol)
                .map(CodeRetrievalPlanValidator::canonicalSymbol);
        return java.util.stream.Stream.concat(inventoryCallables, observedCalls)
                .filter(symbol -> distinctiveTokens(symbol).size() >= 2)
                .anyMatch(symbol -> containsExactIdentifier(operation.query(), symbol));
    }

    private boolean isTrustedCallableOutline(CodeSymbolOutline outline) {
        if (outline == null) return false;
        String kind = outline.kind() == null ? "" : outline.kind().trim().toLowerCase(Locale.ROOT);
        return ("method".equals(kind) || "constructor".equals(kind))
                && CodeIntelligenceAuthority.from(outline.authority()).rank()
                >= CodeIntelligenceAuthority.SYNTAX.rank();
    }

    private boolean containsExactIdentifier(String query, String symbol) {
        if (query == null || query.isBlank() || symbol == null || symbol.isBlank()) return false;
        Pattern exact = Pattern.compile(
                "(?<![\\p{L}\\p{N}_$])" + Pattern.quote(symbol) + "(?![\\p{L}\\p{N}_$])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return exact.matcher(query).find();
    }

    static String canonicalSymbol(String value) {
        String symbol = value == null ? "" : value.trim();
        int parameters = symbol.indexOf('(');
        if (parameters >= 0) symbol = symbol.substring(0, parameters);
        symbol = symbol.replace("::", ".").replace('#', '.');
        int separator = symbol.lastIndexOf('.');
        if (separator >= 0 && separator + 1 < symbol.length()) {
            symbol = symbol.substring(separator + 1);
        }
        int generic = symbol.indexOf('<');
        return (generic > 0 ? symbol.substring(0, generic) : symbol).trim();
    }

    private boolean isQuestionAnchorForAllClaims(
            RagPipelineService.CodeSearchOperation operation,
            Map<String, RagPipelineService.CodeEvidenceChecklistItem> claims,
            Set<String> questionAnchors
    ) {
        if (operation == null || operation.claimIds().isEmpty()) return false;
        Set<String> queryTokens = distinctiveTokens(operation.query());
        if (queryTokens.isEmpty() || questionAnchors == null || questionAnchors.isEmpty()) return false;
        boolean questionCoverage = hasQuestionAnchoringCoverage(queryTokens, questionAnchors);
        if (operation.claimIds().size() == 1) {
            RagPipelineService.CodeEvidenceChecklistItem claim = claims.get(operation.claimIds().get(0));
            Set<String> claimTokens = claimBehaviorTokens(claim);
            boolean semanticBridge = claim != null && hasSubstantiveQuestionClaimBridge(
                    queryTokens, questionAnchors, claimTokens);
            return questionCoverage && (semanticBridge
                    || hasDominantQuestionCoverage(queryTokens, questionAnchors));
        }
        if (!hasStrictQuestionAnchoringCoverage(queryTokens, questionAnchors)) return false;
        for (String claimId : operation.claimIds()) {
            RagPipelineService.CodeEvidenceChecklistItem claim = claims.get(claimId);
            if (claim == null || !hasSubstantiveQuestionClaimBridge(
                    queryTokens, questionAnchors, claimBehaviorTokens(claim))) {
                return false;
            }
        }
        return true;
    }

    private boolean hasQuestionAnchoringCoverage(Set<String> queryTokens, Set<String> questionTokens) {
        int matched = matchedTargetTokenCount(queryTokens, questionTokens);
        if (queryTokens.size() == 1 || questionTokens.size() == 1) {
            return queryTokens.size() == 1 && questionTokens.size() == 1 && matched == 1;
        }
        return matched >= 2 && matched * 2 >= queryTokens.size();
    }

    private boolean hasDominantQuestionCoverage(Set<String> queryTokens, Set<String> questionTokens) {
        int matched = matchedTargetTokenCount(queryTokens, questionTokens);
        return hasQuestionAnchoringCoverage(queryTokens, questionTokens)
                && matched * 2 >= questionTokens.size();
    }

    private boolean hasStrictQuestionAnchoringCoverage(Set<String> queryTokens, Set<String> questionTokens) {
        int matched = matchedTargetTokenCount(queryTokens, questionTokens);
        return hasQuestionAnchoringCoverage(queryTokens, questionTokens)
                && matched * 2 > queryTokens.size();
    }

    private boolean hasSubstantiveQuestionClaimBridge(
            Set<String> queryTokens,
            Set<String> questionTokens,
            Set<String> claimTokens
    ) {
        if (queryTokens.isEmpty() || questionTokens.isEmpty() || claimTokens.isEmpty()) return false;
        Set<String> bridgeTargets = questionTokens.stream()
                .filter(questionToken -> queryTokens.stream().anyMatch(query -> lexicalMatch(query, questionToken)))
                .filter(questionToken -> claimTokens.stream().anyMatch(claim -> lexicalMatch(claim, questionToken)))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        int matched = Math.min(
                matchedTargetTokenCount(queryTokens, bridgeTargets),
                matchedTargetTokenCount(claimTokens, bridgeTargets));
        int required = queryTokens.size() >= 2 && questionTokens.size() >= 2 && claimTokens.size() >= 2 ? 2 : 1;
        return matched >= required;
    }

    private boolean isBoundedClaimCompanion(
            RagPipelineService.CodeSearchOperation operation,
            Map<String, RagPipelineService.CodeEvidenceChecklistItem> claims,
            Set<String> claimsWithAnchoredQuery,
            Map<String, Integer> companionCounts
    ) {
        Set<String> queryTokens = distinctiveTokens(operation.query());
        if (queryTokens.isEmpty()) return false;
        if (operation.claimIds().isEmpty()
                || operation.claimIds().stream().anyMatch(id -> !claimsWithAnchoredQuery.contains(id))) {
            return false;
        }
        for (String claimId : operation.claimIds()) {
            RagPipelineService.CodeEvidenceChecklistItem claim = claims.get(claimId);
            Set<String> claimTokens = claimBehaviorTokens(claim);
            boolean aligned = hasSubstantiveOverlap(queryTokens, claimTokens)
                    || hasCodeShapedClaimOverlap(operation.query(), claimTokens);
            if (claim == null || !aligned
                    || companionCounts.getOrDefault(claimId, 0) >= 1) {
                return false;
            }
        }
        operation.claimIds().forEach(id -> companionCounts.merge(id, 1, Integer::sum));
        return true;
    }

    /**
     * A bounded source-vocabulary companion may use a composite identifier that is split into
     * several semantic tokens. Requiring the whole short query to be majority-covered rejects
     * valid translated identifiers, so accept one code-shaped identifier only when its own
     * tokens overlap the claim. Plain prose and short-prefix coincidences do not qualify.
     */
    private boolean hasCodeShapedClaimOverlap(String query, Set<String> claimTokens) {
        if (query == null || query.isBlank() || claimTokens == null || claimTokens.isEmpty()) return false;
        Matcher matcher = CODE_SHAPED_IDENTIFIER.matcher(query);
        while (matcher.find()) {
            Set<String> identifierTokens = distinctiveTokens(matcher.group());
            if (!identifierTokens.isEmpty()
                    && matchedTargetTokenCount(identifierTokens, claimTokens) >= 1) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSubstantiveOverlap(Set<String> left, Set<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) return false;
        int matched = matchedTargetTokenCount(left, right);
        int required = left.size() >= 2 && right.size() >= 2 ? 2 : 1;
        return matched >= required && matched * 2 > left.size();
    }

    static int matchedTargetTokenCount(Set<String> source, Set<String> target) {
        if (source == null || target == null || source.isEmpty() || target.isEmpty()) return 0;
        List<String> sources = source.stream().sorted().toList();
        List<String> targets = target.stream().sorted().toList();
        int[] targetOwners = new int[targets.size()];
        java.util.Arrays.fill(targetOwners, -1);
        int matched = 0;
        for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
            if (assignTokenMatch(sourceIndex, sources, targets, targetOwners, new boolean[targets.size()])) {
                matched++;
            }
        }
        return matched;
    }

    /** Maximum one-to-one lexical matching prevents one short prefix from covering many target terms. */
    private static boolean assignTokenMatch(
            int sourceIndex,
            List<String> sources,
            List<String> targets,
            int[] targetOwners,
            boolean[] visitedTargets
    ) {
        for (int targetIndex = 0; targetIndex < targets.size(); targetIndex++) {
            if (visitedTargets[targetIndex]
                    || !lexicalMatch(sources.get(sourceIndex), targets.get(targetIndex))) continue;
            visitedTargets[targetIndex] = true;
            int previousOwner = targetOwners[targetIndex];
            if (previousOwner < 0
                    || assignTokenMatch(previousOwner, sources, targets, targetOwners, visitedTargets)) {
                targetOwners[targetIndex] = sourceIndex;
                return true;
            }
        }
        return false;
    }

    static Set<String> claimBehaviorTokens(RagPipelineService.CodeEvidenceChecklistItem claim) {
        if (claim == null) return Set.of();
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        tokens.addAll(distinctiveTokens(claim.action()));
        tokens.addAll(distinctiveTokens(claim.object()));
        tokens.addAll(distinctiveTokens(claim.expectedOutcome()));
        if (tokens.isEmpty()) tokens.addAll(distinctiveTokens(claim.goal()));
        return Set.copyOf(tokens);
    }

    private boolean hasUntriedClaimNavigation(
            List<RagPipelineService.CodeEvidenceChecklistItem> checklist,
            Set<String> unresolvedClaims,
            RepositoryQuestionMapBuilder.RepositoryQuestionMap repositoryMap,
            Set<String> executedOperationKeys
    ) {
        if (repositoryMap == null || unresolvedClaims == null || unresolvedClaims.isEmpty()) return false;
        for (RagPipelineService.CodeEvidenceChecklistItem claim : checklist == null
                ? List.<RagPipelineService.CodeEvidenceChecklistItem>of() : checklist) {
            if (!unresolvedClaims.contains(claim.claimId())) continue;
            LinkedHashSet<String> intent = new LinkedHashSet<>(claimBehaviorTokens(claim));
            claim.queries().forEach(query -> intent.addAll(distinctiveTokens(query)));
            if (intent.isEmpty()) continue;
            for (RepositoryQuestionMapBuilder.FileSymbolInventory inventory
                    : repositoryMap.symbolInventories().values()) {
                for (com.learnbot.dto.CodeSymbolOutline symbol : inventory.symbols()) {
                    Set<String> symbolTokens = distinctiveTokens(String.join(" ",
                            symbol.name(), symbol.qualifiedName()));
                    if (!overlaps(intent, symbolTokens)) continue;
                    String key = String.join("|", "read_symbol", inventory.path(), symbol.name());
                    if (!executedOperationKeys.contains(key)) return true;
                }
            }
            for (CodeNavigationHandle handle : repositoryMap.navigationHandles()) {
                Set<String> symbolTokens = distinctiveTokens(handle.symbol());
                if (!overlaps(intent, symbolTokens)) continue;
                if (handle.kind() == CodeNavigationHandle.Kind.CALL) {
                    String symbol = canonicalSymbol(handle.symbol());
                    String key = String.join("|", "read_symbol", "", symbol);
                    if (!symbol.isBlank() && !executedOperationKeys.contains(key)) return true;
                }
                if (handle.kind() == CodeNavigationHandle.Kind.DEFINITION && handle.chunkId() != null) {
                    String prefix = "traverse_graph|" + handle.chunkId() + "|";
                    if (executedOperationKeys.stream().noneMatch(key -> key.startsWith(prefix))) return true;
                }
            }
            for (RepositoryQuestionMapBuilder.RelationEvidence relation : repositoryMap.relations()) {
                Set<String> relationTokens = distinctiveTokens(String.join(" ",
                        relation.from(), relation.to(), relation.fromPath(), relation.toPath(), relation.type()));
                if (!overlaps(intent, relationTokens)) continue;
                String targetSymbol = canonicalSymbol(relation.to());
                String symbolRead = String.join("|", "read_symbol", relation.toPath(), targetSymbol);
                String chunkRead = relation.toChunkId() == null
                        ? "" : String.join("|", "read_chunk", relation.toChunkId().toString());
                String traversalPrefix = relation.fromChunkId() == null ? ""
                        : "traverse_graph|" + relation.fromChunkId() + "|" + relation.type() + "|"
                        + relation.direction() + "|";
                boolean attempted = !targetSymbol.isBlank() && executedOperationKeys.contains(symbolRead)
                        || !chunkRead.isBlank() && executedOperationKeys.contains(chunkRead)
                        || !traversalPrefix.isBlank()
                        && executedOperationKeys.stream().anyMatch(key -> key.startsWith(traversalPrefix));
                if (!attempted && (!targetSymbol.isBlank() || relation.toChunkId() != null)) return true;
            }
        }
        return false;
    }

    private boolean overlaps(Set<String> left, Set<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) return false;
        return left.stream().anyMatch(leftToken ->
                right.stream().anyMatch(rightToken -> lexicalMatch(leftToken, rightToken)));
    }

    static Set<String> distinctiveTokens(String value) {
        String split = value == null ? "" : value
                .replaceAll("([\\p{Ll}\\p{N}])([\\p{Lu}])", "$1 $2")
                .replaceAll("[^\\p{L}\\p{N}_]+", " ")
                .toLowerCase(Locale.ROOT);
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        Matcher matcher = SEMANTIC_TOKEN.matcher(split);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return Set.copyOf(tokens);
    }

    static boolean lexicalMatch(String left, String right) {
        if (left.equals(right)) return true;
        if (left.codePointCount(0, left.length()) < 2 || right.codePointCount(0, right.length()) < 2) return false;
        return left.startsWith(right) || right.startsWith(left);
    }

    private RagPipelineService.CodeSearchOperation normalizeObservedRead(
            RagPipelineService.CodeSearchOperation operation,
            RepositoryQuestionMapBuilder.RepositoryQuestionMap repositoryMap
    ) {
        if (operation == null || repositoryMap == null) return operation;
        boolean missingRange = operation.lineStart() == null || operation.lineEnd() == null;
        boolean oversizedRange = operation.lineStart() != null && operation.lineEnd() != null
                && operation.lineEnd() >= operation.lineStart()
                && (long) operation.lineEnd() - operation.lineStart() + 1
                > CodeEvidenceOperationExecutor.MAX_LINE_SPAN;
        if ("read_file_range".equals(operation.type())
                && (missingRange || oversizedRange)
                && !operation.path().isBlank() && !operation.symbol().isBlank()
                && repositoryMap.observesSymbol(operation.path(), operation.symbol())) {
            return new RagPipelineService.CodeSearchOperation(
                    "read_symbol", operation.query(), operation.area(), operation.evidenceGroup(),
                    operation.path(), operation.symbol(), "", null, null, operation.radius(),
                    operation.relations(), operation.direction(), operation.maxHops(), operation.operationId(),
                    operation.claimIds(), operation.originEvidenceIds());
        }
        if ("read_symbol".equals(operation.type())
                && !operation.path().isBlank() && !operation.symbol().isBlank()
                && !repositoryMap.observesSymbol(operation.path(), operation.symbol())
                && repositoryMap.observesCallFromPath(operation.path(), operation.symbol())) {
            return new RagPipelineService.CodeSearchOperation(
                    operation.type(), operation.query(), operation.area(), operation.evidenceGroup(),
                    "", canonicalSymbol(operation.symbol()), operation.chunkId(), operation.lineStart(),
                    operation.lineEnd(), operation.radius(), operation.relations(), operation.direction(),
                    operation.maxHops(), operation.operationId(), operation.claimIds(),
                    operation.originEvidenceIds());
        }
        return operation;
    }

    private RagPipelineService.CodeSearchOperation normalizeObservedTraversal(
            RagPipelineService.CodeSearchOperation operation,
            RepositoryQuestionMapBuilder.RepositoryQuestionMap repositoryMap
    ) {
        if (operation == null || repositoryMap == null || !"traverse_graph".equals(operation.type())) {
            return operation;
        }
        Set<String> observed = repositoryMap.observedTraversalRelations(
                operation.chunkId(), operation.direction());
        if (observed.isEmpty()) return operation;
        List<String> intersection = operation.relations().stream()
                .map(value -> value == null ? "" : value.trim().toUpperCase(Locale.ROOT))
                .filter(observed::contains)
                .distinct()
                .toList();
        List<String> normalized = !intersection.isEmpty()
                ? intersection
                : observed.size() == 1 ? List.copyOf(observed) : operation.relations();
        if (normalized.equals(operation.relations())) return operation;
        return new RagPipelineService.CodeSearchOperation(
                operation.type(), operation.query(), operation.area(), operation.evidenceGroup(),
                operation.path(), operation.symbol(), operation.chunkId(), operation.lineStart(),
                operation.lineEnd(), operation.radius(), normalized, operation.direction(),
                operation.maxHops(), operation.operationId(), operation.claimIds(),
                operation.originEvidenceIds());
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
        if (code == PlanValidationCode.INVALID_DUPLICATE_OPERATION) {
            return "the same typed operation or equivalent source read already completed; "
                    + "reassess the existing evidence or choose an untried observed operand for an unresolved claim";
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
        if (!fingerprints.add(fingerprint) || executedOperationKeys.contains(fingerprint)
                || repositoryMap != null
                && repositoryMap.hasExecutedEquivalentRead(operation, executedOperationKeys)) {
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
        if ("traverse_graph".equals(operation.type())) {
            Set<String> observed = repositoryMap.observedTraversalRelations(
                    operation.chunkId(), operation.direction());
            if (!observed.isEmpty() && operation.relations().stream()
                    .map(value -> value == null ? "" : value.trim().toUpperCase(Locale.ROOT))
                    .noneMatch(observed::contains)) {
                return PlanValidationCode.INVALID_UNOBSERVED_OPERAND;
            }
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
        INVALID_DUPLICATE_OPERATION,
        INVALID_UNTRIED_NAVIGATION,
        INVALID_QUERY_CLAIM_MISMATCH
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
