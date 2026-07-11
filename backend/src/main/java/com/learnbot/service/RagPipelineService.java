package com.learnbot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeConversationAnchor;
import com.learnbot.dto.CodeSearchResult;
import com.learnbot.dto.PreviousAnswerItem;
import com.learnbot.dto.RagConversationContext;
import com.learnbot.dto.RagConversationTurnContext;
import com.learnbot.dto.SearchResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.Duration;

@Service
public class RagPipelineService {
    private static final Logger log = LoggerFactory.getLogger(RagPipelineService.class);
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");
    private static final Pattern EXACT_IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{2,}");
    private static final int MAX_REWRITE_QUERIES = 6;
    private static final int MAX_QUERY_CHARS = 180;
    private static final int MAX_EVIDENCE_GROUP_CHARS = 64;
    private static final int MAX_SEMANTIC_LABEL_CHARS = 64;
    private static final List<String> CODE_SEARCH_OPERATION_TYPES = List.of(
            "keyword_search", "hybrid_search", "reference_search",
            "read_chunk", "read_symbol", "list_file_symbols", "read_file_range", "read_adjacent", "traverse_graph"
    );
    private static final List<String> CODE_GRAPH_RELATION_TYPES = List.of(
            "DEFINES", "CONTAINS", "CALLS", "REFERENCES", "EXTENDS", "IMPLEMENTS", "OVERRIDES",
            "ACCEPTS", "RETURNS", "THROWS", "READS_FIELD", "WRITES_FIELD", "ANNOTATED_BY",
            "INJECTS", "USES_ENTITY", "MAPS_TO_TABLE", "EXPOSES_ENDPOINT", "HANDLES_EVENT",
            "BINDS_TO", "DECLARES_CONTROL", "USES_COMMAND", "COMMAND_EXECUTES", "DATA_CONTEXT",
            "CODE_BEHIND", "PARTIAL_OF"
    );

    private final OllamaClient ollamaClient;
    private final LearnBotProperties properties;
    private final RuntimeTuningService runtimeTuningService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagPipelineService(OllamaClient ollamaClient, LearnBotProperties properties) {
        this(ollamaClient, properties, null);
    }

    @Autowired
    public RagPipelineService(OllamaClient ollamaClient, LearnBotProperties properties, RuntimeTuningService runtimeTuningService) {
        this.ollamaClient = ollamaClient;
        this.properties = properties;
        this.runtimeTuningService = runtimeTuningService;
    }

    public QueryPlan buildQueryPlan(String question, Domain domain, List<String> baselineQueries) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        addQuery(queries, question);
        addQueries(queries, baselineQueries);

        if (!pipeline().isRewriteEnabled()) {
            return new QueryPlan(domain, List.copyOf(queries), false, false, false, "rewrite disabled");
        }

        try {
            String response = requestStructuredJson(
                    "query rewrite",
                    rewriteSystemPrompt(domain),
                    rewriteUserPrompt(question, domain, baselineQueries),
                    Math.max(1, pipeline().getRewriteMaxOutputTokens()),
                    Duration.ofSeconds(Math.max(1, pipeline().getRewriteTimeoutSeconds())),
                    queryRewriteSchema()
            );
            List<String> rewritten = parseRewriteQueries(response);
            addQueries(queries, rewritten);
            boolean usedRewrite = !rewritten.isEmpty();
            return new QueryPlan(
                    domain,
                    List.copyOf(queries),
                    true,
                    usedRewrite,
                    false,
                    usedRewrite ? "llm rewrite accepted" : "llm rewrite returned no usable queries"
            );
        } catch (RuntimeException ex) {
            log.info("RAG query rewrite skipped domain={} reason={} question={}",
                    domain, ex.getClass().getSimpleName(), abbreviate(question));
            return new QueryPlan(domain, List.copyOf(queries), true, false, true, "llm rewrite failed");
        }
    }

    public EvidenceAssessment assessDocuments(String question, List<SearchResult> results, int minEvidence, int iteration) {
        if (results == null || results.isEmpty()) {
            return new EvidenceAssessment(false, iteration, 0, 0, 0, List.of("no evidence"));
        }

        double topScore = results.get(0).score();
        int distinctSources = (int) results.stream().map(SearchResult::documentId).distinct().count();
        double coverage = coverage(question, results.stream()
                .limit(8)
                .map(result -> safe(result.title()) + " " + safe(result.sourceUri()) + " " + safe(result.content()))
                .toList());
        boolean enoughCount = results.size() >= minEvidence || topScore >= 0.65;
        boolean enoughScore = topScore >= pipeline().getMinTopScore() || results.size() >= minEvidence + 2;
        boolean enoughCoverage = coverage >= pipeline().getMinCoverage() || queryTerms(question).isEmpty();
        boolean sufficient = enoughCount && enoughScore && enoughCoverage;
        return new EvidenceAssessment(
                sufficient,
                iteration,
                topScore,
                distinctSources,
                coverage,
                reasons(sufficient, enoughCount, enoughScore, enoughCoverage)
        );
    }

    public EvidenceAssessment assessCode(String question, List<CodeSearchResult> results, int minEvidence, int iteration) {
        if (results == null || results.isEmpty()) {
            return new EvidenceAssessment(false, iteration, 0, 0, 0, List.of("no evidence"));
        }

        double topScore = results.get(0).score();
        int distinctSources = (int) results.stream().map(CodeSearchResult::filePath).distinct().count();
        double coverage = coverage(question, results.stream()
                .limit(10)
                .map(result -> safe(result.filePath()) + " "
                        + safe(result.symbolName()) + " "
                        + safe(result.className()) + " "
                        + safe(result.methodName()) + " "
                        + safe(result.content()))
                .toList());
        boolean hasStructuredEvidence = results.stream().anyMatch(this::isStructuredCodeEvidence);
        boolean enoughCount = results.size() >= minEvidence || (hasStructuredEvidence && topScore >= 0.55);
        boolean enoughScore = topScore >= pipeline().getMinTopScore() || hasStructuredEvidence;
        boolean enoughCoverage = coverage >= pipeline().getMinCoverage()
                || queryTerms(question).isEmpty()
                || (hasStructuredEvidence && topScore >= 0.55);
        boolean sufficient = enoughCount && enoughScore && enoughCoverage;
        return new EvidenceAssessment(
                sufficient,
                iteration,
                topScore,
                distinctSources,
                coverage,
                reasons(sufficient, enoughCount, enoughScore, enoughCoverage)
        );
    }

    public AnswerAssessment assessAnswer(String answer, int evidenceCount, boolean citationRequired) {
        return assessAnswer(answer, evidenceCount, citationRequired, null);
    }

    public AnswerAssessment assessAnswer(String answer, int evidenceCount, boolean citationRequired, String doneReason) {
        String trimmed = safe(answer).trim();
        if (!pipeline().isSelfCheckEnabled()) {
            return new AnswerAssessment(true, "self-check disabled");
        }
        if (trimmed.isBlank()) {
            return new AnswerAssessment(false, "blank answer");
        }
        if (trimmed.length() < 12) {
            return new AnswerAssessment(false, "answer too short");
        }
        if ("length".equalsIgnoreCase(safe(doneReason))) {
            return new AnswerAssessment(false, "model stopped before finishing");
        }
        if (looksIncompleteEnding(trimmed)) {
            return new AnswerAssessment(false, "answer appears incomplete");
        }
        List<Integer> citations = citations(trimmed);
        if (citationRequired && citations.isEmpty()) {
            return new AnswerAssessment(false, "missing citation");
        }
        for (Integer citation : citations) {
            if (citation < 1 || citation > evidenceCount) {
                return new AnswerAssessment(false, "citation out of range");
            }
        }
        return new AnswerAssessment(true, "grounding checks passed");
    }

    public int maxIterations() {
        return Math.max(1, Math.min(3, pipeline().getMaxIterations()));
    }

    public int codeRetrievalMaxIterations() {
        return Math.max(1, Math.min(3, pipeline().getCodeRetrievalMaxIterations()));
    }

    public int documentSearchLimit(int requestedLimit) {
        return Math.max(1, Math.min(20, Math.max(requestedLimit, pipeline().getRerankTopN())));
    }

    public int codeSearchLimit(int requestedLimit) {
        return Math.max(1, Math.min(30, Math.max(requestedLimit, pipeline().getRerankTopN())));
    }

    public int documentContextLimit(int fallback) {
        int configured = runtimeTuningService == null ? pipeline().getDocumentContextLimit() : runtimeTuningService.documentContextLimit();
        return Math.max(1, Math.min(16, configured <= 0 ? fallback : configured));
    }

    public int codeContextLimit(int fallback) {
        int configured = runtimeTuningService == null ? pipeline().getCodeContextLimit() : runtimeTuningService.codeContextLimit();
        return Math.max(1, Math.min(24, configured <= 0 ? fallback : configured));
    }

    public int promptTokenBudgetBalanced() {
        return runtimeTuningService == null ? pipeline().getPromptTokenBudgetBalanced() : runtimeTuningService.promptTokenBudgetBalanced();
    }

    public int contextWindow() {
        return runtimeTuningService == null ? properties.getOllama().getContextWindow() : runtimeTuningService.llmContextWindow();
    }

    public int maxOutputTokens() {
        return runtimeTuningService == null ? properties.getOllama().getMaxOutputTokens() : runtimeTuningService.llmMaxOutputTokens();
    }

    public int overviewMaxDocuments() {
        return runtimeTuningService == null ? properties.getRag().getOverview().getMaxDocuments() : runtimeTuningService.overviewMaxDocuments();
    }

    public int overviewMaxCodeCategories() {
        return runtimeTuningService == null ? properties.getRag().getOverview().getMaxCodeCategories() : runtimeTuningService.overviewMaxCodeCategories();
    }

    public CodeRagRouteDecision routeCodeRagIntent(
            String question,
            String requestedMode,
            RagConversationContext conversationContext,
            boolean commitInsightAvailable
    ) {
        try {
            String response = requestStructuredJson(
                    "code route",
                    codeRagRouterSystemPrompt(),
                    codeRagRouterUserPrompt(question, requestedMode, conversationContext, commitInsightAvailable),
                    420,
                    Duration.ofSeconds(Math.max(1, pipeline().getCodeRouteTimeoutSeconds())),
                    codeRouteSchema()
            );
            CodeRagRouteDecision decision = parseCodeRagRoute(response);
            if (decision.route() == CodeRagRoute.UNKNOWN) {
                return CodeRagRouteDecision.fallback("router returned unknown route");
            }
            return decision;
        } catch (RuntimeException ex) {
            String message = safeMessage(ex);
            log.info("Code RAG route skipped reason={} message={} question={}",
                    ex.getClass().getSimpleName(), message, abbreviate(question));
            return CodeRagRouteDecision.fallback("router failed: " + message);
        }
    }

    public CodeEvidenceAdjudication adjudicateCodeEvidence(String question, String mode, List<CodeSearchResult> candidates, int limit) {
        return adjudicateCodeEvidence(question, mode, candidates, limit, List.of());
    }

    public CodeEvidenceAdjudication adjudicateCodeEvidence(
            String question,
            String mode,
            List<CodeSearchResult> candidates,
            int limit,
            List<CodeEvidenceChecklistItem> checklist
    ) {
        if (!pipeline().isCodeEvidenceAdjudicationEnabled() || candidates == null || candidates.isEmpty()) {
            return new CodeEvidenceAdjudication(false, false, "llm code evidence adjudication disabled", candidates == null ? List.of() : candidates);
        }
        int candidateLimit = Math.max(1, Math.min(pipeline().getCodeEvidenceAdjudicationMaxCandidates(), candidates.size()));
        List<CodeSearchResult> head = candidates.stream().limit(candidateLimit).toList();
        try {
            String response = requestStructuredJson(
                    "code evidence adjudication",
                    codeAdjudicationSystemPrompt(),
                    codeAdjudicationUserPrompt(question, mode, head, limit, checklist),
                    Math.max(1, pipeline().getCodeEvidenceAdjudicationMaxOutputTokens()),
                    Duration.ofSeconds(Math.max(1, pipeline().getCodeEvidenceAdjudicationTimeoutSeconds())),
                    codeAdjudicationSchema()
            );
            Map<Integer, AdjudicatedCandidate> decisions = parseCodeAdjudication(response);
            if (decisions.isEmpty()) {
                return new CodeEvidenceAdjudication(true, false, "llm code evidence adjudication returned no usable selections", candidates);
            }
            List<CodeSearchResult> adjudicated = applyCodeAdjudication(candidates, head, decisions);
            return new CodeEvidenceAdjudication(true, true, "llm code evidence adjudication used", adjudicated);
        } catch (RuntimeException ex) {
            log.info("RAG code evidence adjudication skipped reason={} message={} question={}",
                    ex.getClass().getSimpleName(), safeMessage(ex), abbreviate(question));
            return new CodeEvidenceAdjudication(true, false, "llm code evidence adjudication failed", candidates);
        }
    }

    public CodeEvidenceSearchPlan planCodeEvidenceSearch(String question, String mode, String repositoryContext, int maxQueries) {
        if (runtimeTuningService == null) {
            return new CodeEvidenceSearchPlan(false, false, 0.0, List.of(), List.of(), "runtime tuning unavailable");
        }
        if (question == null || question.isBlank()) {
            return new CodeEvidenceSearchPlan(false, false, 0.0, List.of(), List.of(), "blank question");
        }
        try {
            String response = requestStructuredJson(
                    "code evidence search plan",
                    codeEvidenceSearchPlanSystemPrompt(),
                    codeEvidenceSearchPlanUserPrompt(question, mode, repositoryContext, maxQueries),
                    512,
                    Duration.ofSeconds(Math.max(1, pipeline().getCodeEvidenceFollowUpTimeoutSeconds())),
                    codeEvidenceSearchPlanSchema()
            );
            CodeEvidenceSearchPlan draft = parseCodeEvidenceSearchPlan(response, maxQueries);
            if (!needsBehavioralPlanReview(draft)) {
                return draft;
            }
            String reviewedResponse = requestStructuredJson(
                    "code evidence search plan review",
                    codeEvidenceSearchPlanReviewSystemPrompt(),
                    codeEvidenceSearchPlanReviewUserPrompt(question, mode, response, maxQueries),
                    512,
                    Duration.ofSeconds(Math.max(1, pipeline().getCodeEvidenceFollowUpTimeoutSeconds())),
                    codeEvidenceSearchPlanSchema()
            );
            CodeEvidenceSearchPlan reviewed = parseCodeEvidenceSearchPlan(reviewedResponse, maxQueries);
            return reviewed.usable() ? reviewed : draft;
        } catch (RuntimeException ex) {
            log.info("RAG code evidence search planning skipped reason={} message={} question={}",
                    ex.getClass().getSimpleName(), safeMessage(ex), abbreviate(question));
            return new CodeEvidenceSearchPlan(true, false, 0.0, List.of(), List.of(), "search planner failed: " + safeMessage(ex));
        }
    }

    public CodeEvidenceFollowUpPlan planCodeEvidenceFollowUp(String question, String mode, List<CodeSearchResult> candidates, int maxQueries) {
        return planCodeEvidenceIteration(question, mode, candidates, maxQueries, List.of(), List.of(), 1);
    }

    public CodeEvidenceFollowUpPlan planCodeEvidenceFollowUp(
            String question,
            String mode,
            List<CodeSearchResult> candidates,
            int maxQueries,
            List<CodeEvidenceChecklistItem> checklist
    ) {
        return planCodeEvidenceIteration(question, mode, candidates, maxQueries, checklist, List.of(), 1);
    }

    public CodeEvidenceFollowUpPlan planCodeEvidenceIteration(
            String question,
            String mode,
            List<CodeSearchResult> candidates,
            int maxQueries,
            List<CodeEvidenceChecklistItem> checklist,
            List<String> operationObservations,
            int iteration
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return new CodeEvidenceFollowUpPlan(false, true, "no initial evidence", List.of(), List.of(), List.of(), List.of(), checklist, List.of());
        }
        try {
            String evidencePrompt = codeEvidenceCoverageUserPrompt(question, mode, candidates, checklist)
                    + codeEvidenceIterationContext(operationObservations, iteration);
            String response = requestStructuredJson(
                    "code evidence retrieval iteration",
                    codeEvidenceCoverageSystemPrompt(),
                    evidencePrompt,
                    Math.max(1, pipeline().getCodeEvidenceFollowUpMaxOutputTokens()),
                    Duration.ofSeconds(Math.max(1, pipeline().getCodeEvidenceFollowUpTimeoutSeconds())),
                    codeEvidenceFollowUpSchema()
            );
            CodeEvidenceFollowUpPlan plan = parseCodeEvidenceFollowUp(response, maxQueries, checklist);
            List<String> uncoveredWithoutOperation = uncoveredGroupsWithoutOperations(plan);
            if (!plan.enough() && !uncoveredWithoutOperation.isEmpty()) {
                String repairedResponse = requestStructuredJson(
                        "code evidence missing-group plan repair",
                        codeEvidenceCoverageSystemPrompt(),
                        evidencePrompt + "\n\nServer-validated plan error:\n"
                                + "These uncovered evidence groups have no executable operation: "
                                + uncoveredWithoutOperation + ". Return a corrected plan with at least one new, non-duplicate operation for each listed group. "
                                + "Do not add operations for groups already directly covered.",
                        Math.max(1, pipeline().getCodeEvidenceFollowUpMaxOutputTokens()),
                        Duration.ofSeconds(Math.max(1, pipeline().getCodeEvidenceFollowUpTimeoutSeconds())),
                        codeEvidenceFollowUpSchema()
                );
                plan = parseCodeEvidenceFollowUp(repairedResponse, maxQueries, checklist);
            }
            List<Integer> exactMethodIndexes = exactRequestedMethodIndexes(question, mode, candidates);
            if (plan.enough() || exactMethodIndexes.isEmpty()) {
                return plan;
            }
            String reviewedResponse = requestStructuredJson(
                    "code evidence exact method sufficiency review",
                    codeEvidenceCoverageSystemPrompt(),
                    evidencePrompt + "\n\nServer-validated contradiction:\n"
                            + "The original question names an exact method whose implementation body is present at candidate indexes "
                            + exactMethodIndexes + ". Re-evaluate the single-method behavior claim. If the body directly proves it, set enough=true and map its evidence group to those exact indexes in coverageSelections. Do not demand unrelated lifecycle evidence.",
                    Math.max(1, pipeline().getCodeEvidenceFollowUpMaxOutputTokens()),
                    Duration.ofSeconds(Math.max(1, pipeline().getCodeEvidenceFollowUpTimeoutSeconds())),
                    codeEvidenceFollowUpSchema()
            );
            return parseCodeEvidenceFollowUp(reviewedResponse, maxQueries, checklist);
        } catch (RuntimeException ex) {
            log.info("RAG code evidence retrieval iteration planning skipped iteration={} reason={} message={} question={}",
                    Math.max(1, iteration), ex.getClass().getSimpleName(), safeMessage(ex), abbreviate(question));
            return new CodeEvidenceFollowUpPlan(true, false, "retrieval iteration planner failed: " + safeMessage(ex), List.of(), List.of(), List.of(), List.of(), checklist, List.of());
        }
    }

    private List<String> uncoveredGroupsWithoutOperations(CodeEvidenceFollowUpPlan plan) {
        if (plan == null || plan.enough()) return List.of();
        LinkedHashSet<String> covered = plan.coverageSelections().stream()
                .map(CodeEvidenceCoverageSelection::evidenceGroup)
                .map(this::normalizeEvidenceGroup)
                .filter(group -> !"unknown".equals(group))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        LinkedHashSet<String> operated = plan.operations().stream()
                .map(CodeSearchOperation::evidenceGroup)
                .map(this::normalizeEvidenceGroup)
                .filter(group -> !"unknown".equals(group))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return plan.requiredEvidenceGroups().stream()
                .map(this::normalizeEvidenceGroup)
                .filter(group -> !"unknown".equals(group))
                .filter(group -> !covered.contains(group) && !operated.contains(group))
                .distinct()
                .toList();
    }

    private List<Integer> exactRequestedMethodIndexes(String question, String mode, List<CodeSearchResult> candidates) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        Set<String> requested = new LinkedHashSet<>();
        Matcher matcher = EXACT_IDENTIFIER_PATTERN.matcher(question);
        while (matcher.find()) {
            requested.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < Math.min(14, candidates.size()); index++) {
            CodeSearchResult candidate = candidates.get(index);
            if (candidate == null || candidate.content() == null || candidate.content().isBlank()) {
                continue;
            }
            String method = safe(candidate.methodName()).toLowerCase(Locale.ROOT);
            String symbol = safe(candidate.symbolName()).toLowerCase(Locale.ROOT);
            if ((!method.isBlank() && requested.contains(method)) || (!symbol.isBlank() && requested.contains(symbol))) {
                indexes.add(index + 1);
            }
        }
        return List.copyOf(indexes);
    }

    private LearnBotProperties.Rag.Pipeline pipeline() {
        return properties.getRag().getPipeline();
    }

    private String requestStructuredJson(
            String operation,
            String systemPrompt,
            String userPrompt,
            int maxOutputTokens,
            Duration timeout,
            Map<String, Object> schema
    ) {
        RuntimeException lastFailure = null;
        int maxAttempts = "code evidence adjudication".equals(operation) ? 1 : 2;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            int tokenLimit = attempt == 0
                    ? Math.max(1, maxOutputTokens)
                    : Math.min(2048, Math.max(maxOutputTokens + 256, maxOutputTokens * 2));
            String attemptSystemPrompt = attempt == 0
                    ? systemPrompt
                    : systemPrompt + "\nReturn one complete minified JSON object matching the schema. No prose.";
            String attemptUserPrompt = attempt == 0
                    ? userPrompt
                    : userPrompt + "\n\nThe previous structured response was invalid or truncated. Return only one complete JSON object.";
            OllamaClient.ChatResult result = structuredChatResult(
                    operation,
                    attemptSystemPrompt,
                    attemptUserPrompt,
                    tokenLimit,
                    timeout,
                    schema
            );
            try {
                String json = extractValidJsonObject(result == null ? "" : result.content());
                if (!json.isBlank()) {
                    return json;
                }
                lastFailure = new IllegalArgumentException(operation + " response did not contain JSON"
                        + nullable(" doneReason=", result == null ? null : result.doneReason()));
            } catch (RuntimeException ex) {
                lastFailure = ex;
            }
            if (attempt == 0 && maxAttempts > 1) {
                log.info("Structured LLM JSON retry operation={} reason={} doneReason={} promptTokens={} outputTokens={}",
                        operation,
                        lastFailure == null ? "unknown" : lastFailure.getClass().getSimpleName(),
                        result == null ? "" : safe(result.doneReason()),
                        result == null ? 0 : result.promptEvalCount(),
                        result == null ? 0 : result.evalCount());
            }
        }
        throw new IllegalArgumentException(operation + " response did not contain valid JSON", lastFailure);
    }

    private OllamaClient.ChatResult structuredChatResult(
            String operation,
            String systemPrompt,
            String userPrompt,
            int tokenLimit,
            Duration timeout,
            Map<String, Object> schema
    ) {
        OllamaClient.ChatRole role = structuredChatRole(operation);
        boolean allowUnformattedFallback = !"code evidence adjudication".equals(operation);
        try {
            OllamaClient.ChatResult result = ollamaClient.chatResult(
                    systemPrompt,
                    userPrompt,
                    role,
                    tokenLimit,
                    timeout,
                    schema
            );
            if (result != null) {
                return result;
            }
        } catch (RuntimeException ex) {
            log.info("Structured LLM format call unavailable reason={}", ex.getClass().getSimpleName());
            if (!allowUnformattedFallback) {
                throw ex;
            }
        }
        if (!allowUnformattedFallback) {
            throw new IllegalArgumentException("Structured LLM format call returned no result.");
        }
        return ollamaClient.chatResult(
                systemPrompt,
                userPrompt,
                role,
                tokenLimit,
                timeout
        );
    }

    private OllamaClient.ChatRole structuredChatRole(String operation) {
        if (operation != null && operation.startsWith("code evidence")) {
            int configured = runtimeTuningService == null ? 0 : runtimeTuningService.codeEvidenceDecisionModel();
            return configured == 1 ? OllamaClient.ChatRole.PRIMARY : OllamaClient.ChatRole.AUXILIARY;
        }
        return OllamaClient.ChatRole.AUXILIARY;
    }

    private String codeRagRouterSystemPrompt() {
        return """
                You route LearnBot code RAG questions.
                Return strict JSON only. No Markdown.
                The server will validate your JSON and will fall back to normal code search if uncertain.
                Do not answer the user.
                Prefer conversation context for short follow-ups such as numbers, "that", "more", or item references.
                Choose COMMIT_DIFF only when the user clearly asks about a git commit, hash, HEAD, latest changes, or diff.
                Do not route to COMMIT_DIFF merely because the text contains digits or a short hex-like token.

                JSON schema:
                {"route":"CODE_SEARCH","mode":"overview","confidence":0.0,"queries":["query"],"commitRef":"","targetFile":"","targetSymbol":"","reason":"short reason"}

                Allowed routes:
                ANSWER_FROM_PRIOR, EXPAND_PREVIOUS_ANSWER, CODE_OVERVIEW_FLOW, LOCATE_SYMBOL, EXPLAIN_METHOD, IMPACT_ANALYSIS, COMMIT_DIFF, CLARIFY, CODE_SEARCH.
                Allowed modes:
                overview, flow, locate, method, reasoning, impact, auto.
                """;
    }

    private String codeRagRouterUserPrompt(
            String question,
            String requestedMode,
            RagConversationContext conversationContext,
            boolean commitInsightAvailable
    ) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Question:\n").append(safe(question)).append("\n\n");
        prompt.append("Requested mode: ").append(safe(requestedMode).isBlank() ? "auto" : safe(requestedMode)).append("\n");
        prompt.append("Commit insight available: ").append(commitInsightAvailable).append("\n\n");
        if (conversationContext != null) {
            prompt.append("Conversation contextual: ").append(conversationContext.contextual()).append("\n");
            prompt.append("Previous answer expansion requested by server heuristic: ").append(conversationContext.previousAnswerExpansion()).append("\n");
            prompt.append("Recent turns:\n");
            for (RagConversationTurnContext turn : conversationContext.recentTurns().stream().limit(4).toList()) {
                prompt.append("- mode=").append(safe(turn.mode()))
                        .append(" q=").append(trimForPrompt(turn.question(), 220))
                        .append("\n  answer=").append(trimForPrompt(turn.answer(), 260))
                        .append("\n");
            }
            prompt.append("Code anchors:\n");
            for (CodeConversationAnchor anchor : conversationContext.codeAnchors().stream().limit(6).toList()) {
                prompt.append("- ").append(safe(anchor.filePath()))
                        .append(nullable("#", firstNonBlank(anchor.methodName(), anchor.className(), anchor.symbolName())))
                        .append(" lines=").append(anchor.lineStart()).append("-").append(anchor.lineEnd()).append("\n");
            }
            prompt.append("Previous answer items:\n");
            int index = 1;
            for (PreviousAnswerItem item : conversationContext.previousAnswerItems().stream().limit(10).toList()) {
                prompt.append(index++).append(". ").append(trimForPrompt(item.label(), 100))
                        .append(" evidenceChunks=").append(item.evidenceChunkIds().size())
                        .append("\n");
            }
            prompt.append("\n");
        }
        prompt.append("Return JSON only.");
        return prompt.toString();
    }

    private CodeRagRouteDecision parseCodeRagRoute(String response) {
        String json = extractJsonObject(response);
        if (json.isBlank()) {
            throw new IllegalArgumentException("Code RAG route response did not contain JSON");
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {
            });
            CodeRagRoute route = CodeRagRoute.from(String.valueOf(parsed.getOrDefault("route", "CODE_SEARCH")));
            String mode = normalizeRouteMode(String.valueOf(parsed.getOrDefault("mode", "")));
            double confidence = Math.max(0, Math.min(1, parseDouble(parsed.get("confidence"), 0.0)));
            List<String> queries = parsedStrings(parsed.get("queries")).stream().limit(4).toList();
            return new CodeRagRouteDecision(
                    route,
                    mode,
                    confidence,
                    queries,
                    stringValue(parsed.get("commitRef")),
                    stringValue(parsed.get("targetFile")),
                    stringValue(parsed.get("targetSymbol")),
                    stringValue(parsed.get("reason")),
                    true,
                    false
            );
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid code RAG route JSON", ex);
        }
    }

    private String normalizeRouteMode(String value) {
        String mode = safe(value).trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "overview", "flow", "locate", "method", "reasoning", "impact", "auto" -> mode;
            default -> "";
        };
    }

    private List<String> parsedStrings(Object value) {
        LinkedHashSet<String> output = new LinkedHashSet<>();
        addParsedStrings(output, value);
        return output.stream().toList();
    }

    private List<CodeEvidenceChecklistItem> parseChecklist(Object value, int maxQueries) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        int queryLimit = Math.max(1, Math.min(6, maxQueries));
        List<CodeEvidenceChecklistItem> items = new ArrayList<>();
        Map<String, String> groupOwners = new LinkedHashMap<>();
        for (Object item : collection) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String claimId = sanitizeChecklistText(stringValue(map.get("claimId")), 64);
            String group = normalizeEvidenceGroup(stringValue(map.get("evidenceGroup")));
            String goal = sanitizeChecklistText(stringValue(map.get("goal")), 180);
            List<String> queries = parsedStrings(firstNonNull(map.get("queries"), map.get("searchQueries"), map.get("queryHints"))).stream()
                    .map(this::sanitizeQuery)
                    .filter(query -> !query.isBlank())
                    .distinct()
                    .limit(queryLimit)
                    .toList();
            if (claimId.isBlank() && goal.isBlank() && queries.isEmpty()) {
                continue;
            }
            if (claimId.isBlank()) {
                claimId = "claim-" + (items.size() + 1);
            }
            String existingOwner = groupOwners.get(group);
            if (existingOwner != null && !existingOwner.equals(claimId)) {
                String claimGroup = normalizeEvidenceGroup(claimId);
                group = "unknown".equals(claimGroup) || groupOwners.containsKey(claimGroup)
                        ? group + "_claim_" + (items.size() + 1)
                        : claimGroup;
            }
            groupOwners.put(group, claimId);
            items.add(new CodeEvidenceChecklistItem(claimId, group, goal, queries));
            if (items.size() >= 8) {
                break;
            }
        }
        return items;
    }

    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String sanitizeChecklistText(String value, int maxChars) {
        String sanitized = safe(value).replaceAll("[\\r\\n]+", " ").trim();
        if (sanitized.length() <= maxChars) {
            return sanitized;
        }
        return sanitized.substring(0, Math.max(0, maxChars)).trim();
    }

    private void appendChecklist(StringBuilder prompt, List<CodeEvidenceChecklistItem> checklist) {
        if (prompt == null || checklist == null || checklist.isEmpty()) {
            return;
        }
        prompt.append("Required evidence checklist:\n");
        int index = 1;
        for (CodeEvidenceChecklistItem item : checklist.stream().limit(8).toList()) {
            prompt.append(index++).append(". claimId=").append(safe(item.claimId()))
                    .append(" evidenceGroup=").append(safe(item.evidenceGroup()))
                    .append(" goal=").append(safe(item.goal()))
                    .append(" queries=").append(String.join(" | ", item.queries()))
                    .append("\n");
        }
        prompt.append("Use this checklist to judge missing evidence and final evidence selection. ")
                .append("A single high-level orchestrator should not satisfy multiple checklist items unless the code excerpt directly proves them.\n\n");
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String nullable(String prefix, String value) {
        return value == null || value.isBlank() ? "" : prefix + value;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : abbreviate(message);
    }

    private String codeAdjudicationSystemPrompt() {
        return """
                You are an evidence selection judge for code RAG.
                Decide which retrieved code chunks are the best evidence for answering the user's question,
                and classify what each selected chunk can directly prove.
                Return strict JSON only. No Markdown.
                JSON schema: {"selected":[{"index":1,"score":0.0,"evidenceKind":"direct_code","implementationPhase":"runtime_phase","responsibility":"concrete_behavior","coverageGroup":"question_derived_group","mustUse":false,"supportedClaims":["claim"],"notSupportedClaims":["claim"],"rankReason":"short reason","reason":"short reason"}],"reason":"short reason"}
                Rules:
                - Do not answer the user question.
                - Prefer source chunks that directly implement runtime behavior for architecture, flow, and reasoning questions.
                - Order selected items by answer usefulness. The first selected item should be the strongest primary citation.
                - When required evidence checklist items are provided, select evidence for each checklist item when candidates support it.
                - Avoid using one orchestrator method as proof for every checklist item unless its excerpt directly shows those phases.
                - Treat coordinator/orchestrator methods as orchestration evidence by default. For concrete phase claims, prefer the actual callee method or repository/model client when candidates provide it.
                - For any concrete behavior claim, prefer the method or artifact that directly implements that behavior over a coordinator that only calls it.
                - Use tests only when the question asks about tests or when they are clearly supporting evidence.
                - Prefer direct evidence over indirect summaries when both are available.
                - Classify what the candidate actually proves, not what nearby terms suggest.
                - Graph relationship evidence can support inferred relationships, but must not be treated as a direct code statement.
                - Set mustUse=true only for candidates that are essential to answer the main question.
                - coverageGroup must reuse the exact stable snake_case group from the required evidence checklist when one applies; otherwise use a concise question-derived group or unknown.
                - implementationPhase and responsibility are concise stable snake_case labels derived from what the candidate actually proves, not values from a fixed taxonomy.
                - Select only indexes from the candidate list.
                """;
    }

    private String codeEvidenceCoverageSystemPrompt() {
        return """
                You judge whether current code RAG evidence is enough before answer generation.
                Return strict JSON only. No Markdown.
                Do not answer the user.
                If key implementation evidence is missing or the evidence is off-topic, request a small number of concrete follow-up search queries.
                This must work across programming languages and frameworks. Use file paths, symbols, services, controllers, repositories, routes, handlers, hooks, jobs, tasks, and database/query terms from the evidence when useful.
                JSON schema: {"enough":true,"missingAreas":["area"],"operations":[{"type":"hybrid_search","query":"query","path":"optional/path","symbol":"optionalSymbol","chunkId":"optional-uuid","lineStart":1,"lineEnd":20,"radius":1,"relations":["CALLS"],"direction":"FORWARD","maxHops":1,"area":"area","evidenceGroup":"group"}],"followUpQueries":[],"queryAreas":[],"requiredEvidenceGroups":["group"],"checklist":[{"claimId":"action","evidenceGroup":"action_outcome","goal":"behavior to prove","queries":["source query"]}],"coverageSelections":[{"evidenceGroup":"group","evidenceIndexes":[1]}],"reason":"short reason"}
                Rules:
                - Use operations for follow-up retrieval. Allowed types are keyword_search, hybrid_search, reference_search, read_chunk, read_symbol, list_file_symbols, read_file_range, read_adjacent, and traverse_graph.
                - keyword_search, hybrid_search, and reference_search require query. Never return shell commands, SQL, regex programs, or tool invocation syntax as a query.
                - keyword_search finds exact text and identifiers; hybrid_search combines lexical and semantic retrieval; reference_search finds definitions and references.
                - read_chunk requires chunkId.
                - read_symbol requires symbol; path is optional but recommended when the evidence identifies it.
                - list_file_symbols requires path and lists indexed structural symbols in that exact file. Use it when the correct file is known but the concrete symbol or line range is not.
                - read_file_range requires path, lineStart, and lineEnd.
                - read_adjacent requires chunkId; radius is optional.
                - traverse_graph requires an observed chunkId and one or more relations. Choose direction FORWARD for outgoing relations, REVERSE for incoming relations such as callers or implementations, or BOTH only when direction is genuinely unknown. maxHops is optional and must be 1 to 3.
                - Common relation examples are CALLS, REFERENCES, EXTENDS, IMPLEMENTS, OVERRIDES, READS_FIELD, WRITES_FIELD, DEFINES, and CONTAINS. Select relations from the requested behavior; do not infer a programming language or framework on the server's behalf.
                - Use direct-read identifiers only when they appear in the current evidence. Do not invent paths, symbols, chunk IDs, or line ranges.
                - Search locates an unknown file or symbol. Once the correct file is identified, navigate it with list_file_symbols or another direct-read operation before repeating semantic search.
                - When the correct file or class is present but its excerpt does not contain the requested behavior, use list_file_symbols if the symbol is unknown, or read_adjacent if the target is near an observed chunk.
                - When an exact relevant symbol appears in evidence, prefer read_symbol with its observed path. Use another search only when no identified candidate can be expanded directly.
                - For read_file_range, use the observed candidate lineStart and lineEnd when they enclose the target class or file section, up to 400 lines. Do not arbitrarily truncate an observed range to its first 100 lines.
                - Do not repeat an operation whose observation already returned the same broad class, DTO, test, or unrelated helper; change to a direct read or a more exact identifier query.
                - Omit fields that are not used by the selected operation.
                - Set enough=false when evidence is mostly tests, frontend gates, history storage, retention, docs, generated, or vendor code but the question asks about runtime behavior.
                - When a required evidence checklist is provided, enough=true only if each checklist item is directly covered or clearly irrelevant.
                - Preserve every distinct action, phase, and artifact explicitly requested by the user as a separate checklist claim. Do not replace requested behaviors with generic architectural layer presence.
                - Evidence groups must name observable requested behaviors or outcomes, not architecture roles. Names such as controller_layer, service_layer, repository_layer, api_layer, or business_logic are invalid substitutes when the question asks what actions occur across those layers.
                - Treat architecture roles named by the user as locations in which each requested action must be traced. They do not become sufficient claims by themselves.
                - A class-level or broad orchestration chunk does not prove a concrete action when the question asks how that action is performed. Request the concrete method or persistence implementation.
                - A class declaration, constructor, dependency field, approval flow, status lookup, or similarly adjacent operation proves only itself. Never map it to a requested action unless the excerpt directly contains that action's call, state transition, query, write, or return path.
                - Evidence satisfies a requested behavior only when the relevant actor, object, action, direction, state transition, and side effect agree. Shared vocabulary or architecture roles alone are not enough.
                - If a checklist item is only represented by a broad orchestrator and a concrete phase method is needed, request a follow-up query for the concrete implementation method.
                - For pipeline questions, separate coordinator/orchestrator evidence from concrete callee evidence when possible. A coordinator is useful flow evidence, but concrete phase claims are stronger when supported by their callee method.
                - For a single-method behavior question, an exact requested method body with readable implementation is sufficient for that method's behavior claim. Do not require unrelated callers, controllers, repositories, or lifecycle phases unless the question explicitly asks for them.
                - When the exact requested symbol and its implementation appear in a candidate, map that candidate in coverageSelections instead of declaring the same behavior group missing.
                - The user's wording may assume a conventional implementation that the source does not use. An exact method body is sufficient to correct that premise and explain what the code actually does. Never demand an imagined event unsubscription, persistence call, framework hook, or other conventional mechanism absent from the method.
                - Conversation history repositories, UI gate/status helpers, retention/cleanup services, and verification summaries are supporting evidence only. They are not enough for runtime flow or answer-generation questions unless the user explicitly asks about them.
                - If the current evidence would force the final answer to say that implementation details are not visible, set enough=false and request follow-up queries for the missing implementation path.
                - queryAreas must align one-to-one with followUpQueries when possible.
                - Derive requiredEvidenceGroups from the claims, phases, layers, or artifacts actually requested by the question. Use concise stable snake_case identifiers rather than a fixed taxonomy.
                - When an initial evidence checklist is provided, reuse its evidenceGroup identifiers exactly for the same claims throughout every iteration.
                - Audit the initial checklist against the original question on every iteration. Return a complete revised checklist. Preserve valid action claims, add any requested action or outcome the draft omitted, and replace architecture-role items that do not describe an observable behavior.
                - Create a new evidenceGroup only for a genuinely missing claim that is not represented by the initial checklist, and keep that identifier stable in later operations and adjudication.
                - coverageSelections maps each directly proven evidenceGroup to one or more 1-based indexes from Current evidence candidates. Include only indexes whose excerpts directly prove that group.
                - Every coverageSelections item must include one or more concrete supportedClaims and a pipelineStage directly demonstrated by the selected excerpts.
                - Keep routing, graph/index analysis, retrieval expansion, and answer generation as separate pipeline stages. Shared fallback vocabulary does not prove a transition between stages.
                - When enough=true, every requiredEvidenceGroup must have a coverageSelections entry with at least one direct evidence index. If that mapping cannot be made, set enough=false and request the missing operation.
                - When enough=false, every requiredEvidenceGroup not present in coverageSelections must have at least one executable operation with the exact same evidenceGroup. Do not spend operations only on already-covered groups.
                - Keep follow-up queries short, concrete, and source-code oriented.
                """;
    }

    private String codeEvidenceSearchPlanSystemPrompt() {
        return """
                You plan source-code retrieval for code RAG.
                Return strict JSON only. No Markdown.
                Do not answer the user.
                Create a small set of high-signal source-code search queries that should retrieve the concrete files, symbols, and behaviors needed by the question.
                This must work across languages and frameworks.
                JSON schema: {"usable":true,"confidence":0.0,"queries":["query"],"checklist":[{"claimId":"short-id","evidenceGroup":"orchestration","goal":"what must be proven","queries":["source query"]}],"reason":"short reason"}
                Rules:
                - Prefer exact API paths, class names, method names, file paths, framework roles, and operation names from the user question.
                - Preserve distinctive user vocabulary in at least one query per checklist item instead of replacing it entirely with generic architecture terms.
                - Do not invent likely class or method names. Identifier-shaped terms may come only from the question or repository hints.
                - Use distinct queries for distinct required phases or layers.
                - Build the checklist from the user's requested claims, phases, layers, and artifacts.
                - Preserve each distinct action or verb requested by the user as its own checklist claim. Architectural layers are scopes to search, not substitutes for the requested behaviors.
                - Name evidenceGroup after the requested behavior or outcome. Do not use controller_layer, service_layer, repository_layer, api_layer, business_logic, or other architecture-role labels as evidence groups for a behavioral question.
                - When a question asks how actions flow through named layers, make one checklist item per action and put the relevant layer names in that item's goal and queries. Do not make one checklist item per layer.
                - A class declaration, constructor, dependency field, or nearby but different workflow cannot satisfy a behavioral checklist item. Queries must target methods containing the requested call, state transition, query, write, or return path.
                - Preserve the actor, object, action, direction, state transition, and side effect requested by the user. Do not substitute a nearby workflow that differs on those fields.
                - Give each checklist item a concise stable snake_case evidenceGroup derived from what that item must prove. Do not select it from a fixed taxonomy, and reuse one identifier for the same claim.
                - Keep checklist queries source-code oriented and specific enough to retrieve concrete implementation methods.
                - For each checklist item, include likely concrete callee terms in queries when the question asks how a phase is implemented. Examples of generic callee terms include controller/handler, service/orchestrator, repository/storage, graph traversal/related chunks, rank/score, context/prompt builder, and model/client call.
                - If the question asks for a multi-step pipeline, create separate checklist items for the coordinator and important concrete phases when evidence is needed for those phases.
                - Do not generate broad generic queries like "code implementation" unless no specific clue exists.
                - Do not include prose, bullets, or explanations outside JSON.
                """;
    }

    private String codeEvidenceSearchPlanUserPrompt(String question, String mode, String repositoryContext, int maxQueries) {
        return "Question:\n" + safe(question) + "\n\n"
                + "Question mode: " + safe(mode) + "\n"
                + "Repository map / indexed structure hints:\n" + trimForPrompt(repositoryContext, 5000) + "\n\n"
                + "Maximum queries: " + Math.max(1, Math.min(6, maxQueries)) + "\n\n"
                + "Return JSON only.";
    }

    private boolean needsBehavioralPlanReview(CodeEvidenceSearchPlan plan) {
        if (plan == null || plan.checklist().isEmpty()) {
            return false;
        }
        if (plan.checklist().size() > 1) {
            return true;
        }
        return plan.checklist().stream()
                .map(CodeEvidenceChecklistItem::evidenceGroup)
                .map(this::normalizeEvidenceGroup)
                .allMatch(group -> group.matches(".*(?:controller|service|repository|layer|business_logic|api_layer|data_access).*$"));
    }

    private String codeEvidenceSearchPlanReviewSystemPrompt() {
        return """
                You audit and rewrite a draft source-code retrieval plan. Return strict JSON only using the supplied schema.
                Do not answer the user and do not preserve a flawed taxonomy merely for compatibility.
                The draft requires an independent audit because it is multi-step or uses architecture-oriented claims and may have omitted or conflated requested behaviors.
                Re-read the original question and create one checklist claim for every distinct requested action, state transition, output, or persisted effect.
                Architecture roles are search scopes. Put them in goals and queries, never use them as evidenceGroup substitutes for behavior.
                Each evidenceGroup must name the observable behavior or outcome it proves.
                Queries must target concrete runtime methods, calls, state changes, writes, or return paths across the relevant scopes.
                Preserve the actor, object, action, direction, state transition, and side effect from the original question. A nearby workflow with different fields is a different claim.
                Preserve distinctive user vocabulary and do not invent identifier-shaped class or method names absent from the question.
                Preserve useful exact identifiers from the draft, but remove invented paths or symbols not supported by the question or repository hints.
                """;
    }

    private String codeEvidenceSearchPlanReviewUserPrompt(String question, String mode, String draft, int maxQueries) {
        return "Question:\n" + safe(question) + "\n\n"
                + "Question mode: " + safe(mode) + "\n\n"
                + "Rejected draft plan:\n" + trimForPrompt(draft, 5000) + "\n\n"
                + "Maximum queries: " + Math.max(1, Math.min(6, maxQueries)) + "\n\n"
                + "Rewrite the complete plan and return JSON only.";
    }

    private String codeEvidenceCoverageUserPrompt(
            String question,
            String mode,
            List<CodeSearchResult> candidates,
            List<CodeEvidenceChecklistItem> checklist
    ) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Question:\n").append(safe(question)).append("\n\n");
        prompt.append("Question mode: ").append(safe(mode)).append("\n\n");
        appendChecklist(prompt, checklist);
        prompt.append("Current evidence candidates:\n");
        int previewCount = Math.min(14, candidates.size());
        int previewExcerptChars = previewCount > 10 ? 420 : 520;
        for (int index = 0; index < previewCount; index++) {
            CodeSearchResult result = candidates.get(index);
            CodeSourceClassifier.SourceProfile profile = CodeSourceClassifier.classify(result);
            prompt.append(index + 1).append(". file=").append(safe(result.filePath()))
                    .append(" chunkId=").append(result.chunkId() == null ? "" : result.chunkId())
                     .append(" lines=").append(result.lineStart()).append("-").append(result.lineEnd())
                     .append(" chunkType=").append(safe(result.chunkType()))
                     .append(" sourceRole=").append(profile.sourceRole())
                     .append("\nSymbols: ")
                    .append(safe(result.className())).append(" ")
                    .append(safe(result.methodName())).append(" ")
                    .append(safe(result.symbolName())).append("\n")
                    .append("Excerpt:\n")
                    .append(EvidenceExcerptSelector.select(question, result, previewExcerptChars).text())
                    .append("\n\n");
        }
        prompt.append("Return JSON only.");
        return prompt.toString();
    }

    private String codeEvidenceIterationContext(List<String> operationObservations, int iteration) {
        StringBuilder prompt = new StringBuilder("\n\nRetrieval iteration: ")
                .append(Math.max(1, iteration));
        List<String> observations = operationObservations == null
                ? List.of()
                : operationObservations.stream()
                .filter(this::notBlank)
                .map(observation -> trimForPrompt(observation, 240))
                .limit(12)
                .toList();
        if (!observations.isEmpty()) {
            prompt.append("\nPrevious operation observations:\n");
            for (String observation : observations) {
                prompt.append("- ").append(observation).append('\n');
            }
            prompt.append("Use these observations to avoid repeating failed or duplicate operations and to request the remaining evidence.");
        }
        return prompt.toString();
    }

    private CodeEvidenceFollowUpPlan parseCodeEvidenceFollowUp(
            String response,
            int maxQueries,
            List<CodeEvidenceChecklistItem> checklist
    ) {
        String json = extractJsonObject(response);
        if (json.isBlank()) {
            throw new IllegalArgumentException("Code evidence follow-up response did not contain JSON");
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {
            });
            boolean enough = Boolean.parseBoolean(String.valueOf(parsed.getOrDefault("enough", "true")));
            List<String> missingAreas = parsedStrings(parsed.get("missingAreas")).stream().limit(5).toList();
            int queryLimit = Math.max(0, Math.min(4, maxQueries));
            List<String> queries = parsedStrings(parsed.get("followUpQueries")).stream().limit(queryLimit).toList();
            List<String> queryAreas = parsedStrings(parsed.get("queryAreas")).stream().limit(queryLimit).toList();
            List<CodeSearchOperation> operations = parseCodeSearchOperations(parsed.get("operations"), queryLimit);
            if (operations.isEmpty() && !queries.isEmpty()) {
                List<CodeSearchOperation> compatible = new ArrayList<>();
                for (int index = 0; index < queries.size(); index++) {
                    compatible.add(new CodeSearchOperation("hybrid_search", queries.get(index), index < queryAreas.size() ? queryAreas.get(index) : "", ""));
                }
                operations = List.copyOf(compatible);
            } else if (!operations.isEmpty() && queries.isEmpty()) {
                queries = operations.stream()
                        .filter(CodeSearchOperation::isSearch)
                        .map(CodeSearchOperation::query)
                        .filter(query -> !query.isBlank())
                        .toList();
                queryAreas = operations.stream()
                        .filter(CodeSearchOperation::isSearch)
                        .filter(operation -> !operation.query().isBlank())
                        .map(CodeSearchOperation::area)
                        .toList();
            }
            LinkedHashSet<String> requiredGroups = new LinkedHashSet<>();
            parsedStrings(parsed.get("requiredEvidenceGroups")).stream()
                    .map(this::normalizeEvidenceGroup)
                    .filter(group -> !"unknown".equals(group))
                    .forEach(requiredGroups::add);
            checklist.stream()
                    .map(CodeEvidenceChecklistItem::evidenceGroup)
                    .map(this::normalizeEvidenceGroup)
                    .filter(group -> !"unknown".equals(group))
                    .forEach(requiredGroups::add);
            List<CodeEvidenceChecklistItem> revisedChecklist = parseChecklist(parsed.get("checklist"), 6);
            if (revisedChecklist.isEmpty()) {
                revisedChecklist = checklist;
            }
            revisedChecklist.stream()
                    .map(CodeEvidenceChecklistItem::evidenceGroup)
                    .map(this::normalizeEvidenceGroup)
                    .filter(group -> !"unknown".equals(group))
                    .forEach(requiredGroups::add);
            List<String> groups = requiredGroups.stream().limit(8).toList();
            List<CodeEvidenceCoverageSelection> coverageSelections = parseCodeEvidenceCoverageSelections(
                    parsed.get("coverageSelections"), 14);
            String reason = stringValue(parsed.get("reason"));
            return new CodeEvidenceFollowUpPlan(true, enough, reason, missingAreas, enough ? List.of() : queries, enough ? List.of() : queryAreas,
                    groups, revisedChecklist, enough ? List.of() : operations, coverageSelections);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid code evidence follow-up JSON", ex);
        }
    }

    private List<CodeSearchOperation> parseCodeSearchOperations(Object value, int limit) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        List<CodeSearchOperation> operations = new ArrayList<>();
        for (Object item : collection) {
            if (operations.size() >= limit || !(item instanceof Map<?, ?> map)) {
                continue;
            }
            String type = normalizeCodeSearchOperationType(map.get("type"));
            if (type.isBlank()) {
                continue;
            }
            String query = stringValue(map.get("query")).trim();
            CodeSearchOperation operation = new CodeSearchOperation(
                    type,
                    query,
                    stringValue(map.get("area")),
                    normalizeEvidenceGroup(stringValue(map.get("evidenceGroup"))),
                    stringValue(map.get("path")),
                    stringValue(map.get("symbol")),
                    stringValue(map.get("chunkId")),
                    nullableInteger(map.get("lineStart")),
                    nullableInteger(map.get("lineEnd")),
                    nullableInteger(map.get("radius")),
                    parsedStrings(map.get("relations")),
                    stringValue(map.get("direction")),
                    nullableInteger(map.get("maxHops"))
            );
            if (!operation.isSearch() || operation.validationError().isBlank()) {
                operations.add(operation);
            }
        }
        return List.copyOf(operations);
    }

    private CodeEvidenceSearchPlan parseCodeEvidenceSearchPlan(String response, int maxQueries) {
        String json = extractJsonObject(response);
        if (json.isBlank()) {
            throw new IllegalArgumentException("Code evidence search plan response did not contain JSON");
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {
            });
            boolean usable = Boolean.parseBoolean(String.valueOf(parsed.getOrDefault("usable", "true")));
            double confidence = Math.max(0.0, Math.min(1.0, parseDouble(parsed.get("confidence"), usable ? 0.5 : 0.0)));
            int queryLimit = Math.max(1, Math.min(6, maxQueries));
            List<String> queries = parsedStrings(parsed.get("queries")).stream()
                    .map(this::sanitizeQuery)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .limit(queryLimit)
                    .toList();
            List<CodeEvidenceChecklistItem> checklist = parseChecklist(parsed.get("checklist"), maxQueries);
            String reason = stringValue(parsed.get("reason"));
            return new CodeEvidenceSearchPlan(true, usable && (!queries.isEmpty() || !checklist.isEmpty()), confidence, queries, checklist, reason);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid code evidence search plan JSON", ex);
        }
    }

    private String codeAdjudicationUserPrompt(
            String question,
            String mode,
            List<CodeSearchResult> candidates,
            int limit,
            List<CodeEvidenceChecklistItem> checklist
    ) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Question:\n").append(safe(question)).append("\n\n");
        prompt.append("Question mode: ").append(safe(mode)).append("\n");
        prompt.append("Maximum useful evidence items: ").append(Math.max(1, limit)).append("\n\n");
        appendChecklist(prompt, checklist);
        prompt.append("Candidates:\n");
        for (int index = 0; index < candidates.size(); index++) {
            CodeSearchResult result = candidates.get(index);
            CodeSourceClassifier.SourceProfile profile = CodeSourceClassifier.classify(result);
            prompt.append(index + 1).append(". file=").append(safe(result.filePath()))
                     .append(" lines=").append(result.lineStart()).append("-").append(result.lineEnd())
                     .append(" chunkType=").append(safe(result.chunkType()))
                     .append(" sourceRole=").append(profile.sourceRole())
                     .append(" parserConfidence=").append(profile.parserConfidence())
                    .append(" retrievalSource=").append(metadataValue(result, "retrievalSource"))
                    .append(" graphExpanded=").append(metadataValue(result, "graphExpanded"))
                    .append(" graphEdgeType=").append(metadataValue(result, "graphEdgeType"))
                    .append(" graphEvidenceKind=").append(metadataValue(result, "graphEvidenceKind"))
                    .append("\nSymbols: ")
                    .append(safe(result.className())).append(" ")
                    .append(safe(result.methodName())).append(" ")
                    .append(safe(result.symbolName())).append("\n")
                    .append("Excerpt:\n")
                    .append(EvidenceExcerptSelector.select(question, result, adjudicationExcerptChars(candidates.size())).text())
                    .append("\n\n");
        }
        prompt.append("Return JSON only.");
        return prompt.toString();
    }

    private int adjudicationExcerptChars(int candidateCount) {
        if (candidateCount > 30) {
            return 420;
        }
        if (candidateCount > 20) {
            return 560;
        }
        if (candidateCount > 10) {
            return 700;
        }
        return 900;
    }

    private Map<Integer, AdjudicatedCandidate> parseCodeAdjudication(String response) {
        String json = extractJsonObject(response);
        if (json.isBlank()) {
            throw new IllegalArgumentException("Code evidence adjudication response did not contain JSON");
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {
            });
            Object selected = parsed.get("selected");
            if (!(selected instanceof Collection<?> collection)) {
                return Map.of();
            }
            java.util.LinkedHashMap<Integer, AdjudicatedCandidate> decisions = new java.util.LinkedHashMap<>();
            int rank = 0;
            for (Object item : collection) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                int index = parseInt(map.get("index"), -1);
                if (index < 1 || decisions.containsKey(index)) {
                    continue;
                }
                double score = Math.max(0, Math.min(1, parseDouble(map.get("score"), Math.max(0.1, 1.0 - (rank * 0.08)))));
                Object reasonValue = map.get("reason");
                String reason = reasonValue == null ? "selected by llm evidence adjudicator" : String.valueOf(reasonValue);
                String evidenceKind = normalizeEnumValue(map.get("evidenceKind"), "direct_code",
                        List.of("direct_code", "graph_relationship", "supporting_context"));
                String implementationPhase = normalizeSemanticLabel(map.get("implementationPhase"), "UNKNOWN");
                String responsibility = normalizeSemanticLabel(map.get("responsibility"), "unknown");
                String coverageGroup = normalizeEvidenceGroup(stringValue(map.get("coverageGroup")));
                Object mustUseValue = map.get("mustUse");
                boolean mustUse = mustUseValue != null && Boolean.parseBoolean(String.valueOf(mustUseValue));
                List<String> supportedClaims = parsedStrings(map.get("supportedClaims")).stream().limit(5).toList();
                List<String> notSupportedClaims = parsedStrings(map.get("notSupportedClaims")).stream().limit(5).toList();
                String rankReason = stringValue(map.get("rankReason"));
                decisions.put(index, new AdjudicatedCandidate(index, score, evidenceKind, implementationPhase,
                        responsibility, coverageGroup, mustUse, supportedClaims, notSupportedClaims, rankReason, reason, rank + 1));
                rank++;
            }
            return decisions;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid code evidence adjudication JSON", ex);
        }
    }

    private List<CodeSearchResult> applyCodeAdjudication(List<CodeSearchResult> candidates, List<CodeSearchResult> head, Map<Integer, AdjudicatedCandidate> decisions) {
        List<CodeSearchResult> adjusted = new ArrayList<>();
        Set<Integer> selectedIndexes = new java.util.LinkedHashSet<>();
        for (AdjudicatedCandidate decision : decisions.values()) {
            if (decision.index() < 1 || decision.index() > head.size() || !selectedIndexes.add(decision.index())) {
                continue;
            }
            CodeSearchResult result = head.get(decision.index() - 1);
            double bonus = 0.6 + (decision.score() * 0.4);
            adjusted.add(withAdjudicationMetadata(result, decision, bonus));
        }
        for (int index = 0; index < head.size(); index++) {
            int candidateIndex = index + 1;
            if (selectedIndexes.contains(candidateIndex)) {
                continue;
            }
            CodeSearchResult result = head.get(index);
            adjusted.add(withAdjudicationMetadata(result, null, -0.04));
        }
        if (candidates.size() > head.size()) {
            adjusted.addAll(candidates.subList(head.size(), candidates.size()));
        }
        return adjusted;
    }

    private CodeSearchResult withAdjudicationMetadata(CodeSearchResult result, AdjudicatedCandidate decision, double bonus) {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
        double currentScore = parseDouble(metadata.get("evidenceScore"), result.score());
        metadata.put("llmEvidenceAdjudicationAttempted", true);
        metadata.put("llmEvidenceAdjudicationSelected", decision != null);
        metadata.put("llmEvidenceAdjudicationBonus", round(currentScore + bonus));
        metadata.put("evidenceScore", round(currentScore + bonus));
        if (decision != null) {
            metadata.put("llmEvidenceAdjudicationScore", round(decision.score()));
            metadata.put("llmEvidenceAdjudicationReason", decision.reason());
            metadata.put("llmEvidenceSlateRank", decision.slateRank());
            metadata.put("llmEvidenceSlateMustUse", decision.mustUse());
            metadata.put("llmEvidenceKind", decision.evidenceKind());
            metadata.put("llmImplementationPhase", decision.implementationPhase());
            metadata.put("llmEvidenceResponsibility", decision.responsibility());
            metadata.put("llmEvidenceClassificationSource", "llm_adjudication");
            if (!"unknown".equals(decision.coverageGroup())) {
                metadata.put("llmEvidenceCoverageGroup", decision.coverageGroup());
            }
            if (!decision.rankReason().isBlank()) {
                metadata.put("llmEvidenceRankReason", decision.rankReason());
            }
            if (!decision.supportedClaims().isEmpty()) {
                metadata.put("llmSupportedClaims", decision.supportedClaims());
            }
            if (!decision.notSupportedClaims().isEmpty()) {
                metadata.put("llmNotSupportedClaims", decision.notSupportedClaims());
            }
        }
        return new CodeSearchResult(
                result.chunkId(), result.repositoryId(), result.fileId(), result.repositoryName(), result.filePath(),
                result.chunkType(), result.symbolName(), result.className(), result.methodName(), result.namespaceName(),
                result.controlName(), result.eventName(), result.chunkIndex(), result.lineStart(), result.lineEnd(),
                result.content(), result.score(), Map.copyOf(metadata)
        );
    }

    private double adjudicatedScore(CodeSearchResult result) {
        return parseDouble(result.metadata() == null ? null : result.metadata().get("llmEvidenceAdjudicationBonus"), result.score());
    }

    private String rewriteSystemPrompt(Domain domain) {
        String domainHint = domain == Domain.CODE
                ? "source-code search over files, symbols, methods, UI events, and git commit-related questions"
                : "private document search over PDFs, spreadsheets, web pages, policies, tables, and exact quotes";
        return """
                You rewrite user questions into retrieval queries for a RAG system.
                Return strict JSON only. No Markdown.
                JSON schema: {"queries":["query 1","query 2"],"keywords":["term 1","term 2"],"reason":"short reason"}
                Keep queries short and concrete. Preserve Korean terms and add English technical synonyms only when useful.
                Do not answer the question.
                Domain: """ + domainHint;
    }

    private String rewriteUserPrompt(String question, Domain domain, List<String> baselineQueries) {
        return "Domain: " + domain + "\n"
                + "Original question:\n" + safe(question) + "\n\n"
                + "Existing deterministic queries:\n" + String.join("\n", baselineQueries == null ? List.of() : baselineQueries) + "\n\n"
                + "Return JSON only.";
    }

    private List<String> parseRewriteQueries(String response) {
        String json = extractJsonObject(response);
        if (json.isBlank()) {
            throw new IllegalArgumentException("Query rewrite response did not contain JSON");
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {
            });
            LinkedHashSet<String> queries = new LinkedHashSet<>();
            addParsedStrings(queries, parsed.get("queries"));
            addParsedStrings(queries, parsed.get("keywords"));
            return queries.stream()
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .limit(MAX_REWRITE_QUERIES)
                    .toList();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid query rewrite JSON", ex);
        }
    }

    private void addParsedStrings(Set<String> output, Object value) {
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                addQuery(output, String.valueOf(item));
            }
            return;
        }
        if (value instanceof String text) {
            addQuery(output, text);
        }
    }

    private String extractValidJsonObject(String response) {
        String json = extractJsonObject(response);
        if (json.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) {
                throw new IllegalArgumentException("Structured response root must be a JSON object.");
            }
            return json;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Structured response was not valid JSON.", ex);
        }
    }

    private String extractJsonObject(String response) {
        String text = safe(response).trim();
        int start = text.indexOf('{');
        if (start < 0) {
            return "";
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
                if (depth < 0) {
                    return "";
                }
            }
        }
        return "";
    }

    private Map<String, Object> queryRewriteSchema() {
        return objectSchema(Map.of(
                "queries", arraySchema(stringSchema()),
                "keywords", arraySchema(stringSchema()),
                "reason", stringSchema()
        ), List.of("queries", "keywords", "reason"));
    }

    private Map<String, Object> codeRouteSchema() {
        return objectSchema(Map.of(
                "route", enumSchema("ANSWER_FROM_PRIOR", "EXPAND_PREVIOUS_ANSWER", "CODE_OVERVIEW_FLOW", "LOCATE_SYMBOL", "EXPLAIN_METHOD", "IMPACT_ANALYSIS", "COMMIT_DIFF", "CLARIFY", "CODE_SEARCH"),
                "mode", enumSchema("overview", "flow", "locate", "method", "reasoning", "impact", "auto", ""),
                "confidence", numberSchema(),
                "queries", arraySchema(stringSchema()),
                "commitRef", stringSchema(),
                "targetFile", stringSchema(),
                "targetSymbol", stringSchema(),
                "reason", stringSchema()
        ), List.of("route", "mode", "confidence", "queries", "commitRef", "targetFile", "targetSymbol", "reason"));
    }

    private Map<String, Object> codeEvidenceFollowUpSchema() {
        return objectSchema(Map.of(
                "enough", booleanSchema(),
                "missingAreas", arraySchema(stringSchema()),
                "operations", arraySchema(objectSchema(Map.ofEntries(
                        Map.entry("type", enumSchema(CODE_SEARCH_OPERATION_TYPES.toArray(String[]::new))),
                        Map.entry("query", stringSchema()),
                        Map.entry("area", stringSchema()),
                        Map.entry("evidenceGroup", evidenceGroupSchema()),
                        Map.entry("path", stringSchema()),
                        Map.entry("symbol", stringSchema()),
                        Map.entry("chunkId", stringSchema()),
                        Map.entry("lineStart", integerSchema()),
                        Map.entry("lineEnd", integerSchema()),
                        Map.entry("radius", integerSchema()),
                        Map.entry("relations", arraySchema(enumSchema(CODE_GRAPH_RELATION_TYPES.toArray(String[]::new)))),
                        Map.entry("direction", enumSchema("FORWARD", "REVERSE", "BOTH")),
                        Map.entry("maxHops", integerSchema())
                ), List.of("type", "area", "evidenceGroup"))),
                "followUpQueries", arraySchema(stringSchema()),
                "queryAreas", arraySchema(stringSchema()),
                "requiredEvidenceGroups", arraySchema(evidenceGroupSchema()),
                "checklist", arraySchema(objectSchema(Map.of(
                        "claimId", stringSchema(),
                        "evidenceGroup", evidenceGroupSchema(),
                        "goal", stringSchema(),
                        "queries", arraySchema(stringSchema())
                ), List.of("claimId", "evidenceGroup", "goal", "queries"))),
                "coverageSelections", arraySchema(objectSchema(Map.of(
                        "evidenceGroup", evidenceGroupSchema(),
                        "evidenceIndexes", arraySchema(integerSchema()),
                        "supportedClaims", arraySchema(stringSchema()),
                        "pipelineStage", semanticLabelSchema()
                ), List.of("evidenceGroup", "evidenceIndexes", "supportedClaims", "pipelineStage"))),
                "reason", stringSchema()
        ), List.of("enough", "missingAreas", "followUpQueries", "queryAreas", "requiredEvidenceGroups", "checklist", "coverageSelections", "reason"));
    }

    private Map<String, Object> codeEvidenceSearchPlanSchema() {
        return objectSchema(Map.of(
                "usable", booleanSchema(),
                "confidence", numberSchema(),
                "queries", arraySchema(stringSchema()),
                "checklist", arraySchema(objectSchema(Map.of(
                        "claimId", stringSchema(),
                        "evidenceGroup", evidenceGroupSchema(),
                        "goal", stringSchema(),
                        "queries", arraySchema(stringSchema())
                ), List.of("claimId", "evidenceGroup", "goal", "queries"))),
                "reason", stringSchema()
        ), List.of("usable", "confidence", "queries", "checklist", "reason"));
    }

    private Map<String, Object> codeAdjudicationSchema() {
        return objectSchema(Map.of(
                "selected", arraySchema(objectSchema(Map.ofEntries(
                        Map.entry("index", integerSchema()),
                        Map.entry("score", numberSchema()),
                        Map.entry("evidenceKind", enumSchema("direct_code", "graph_relationship", "supporting_context")),
                        Map.entry("implementationPhase", semanticLabelSchema()),
                        Map.entry("responsibility", semanticLabelSchema()),
                        Map.entry("coverageGroup", evidenceGroupSchema()),
                        Map.entry("mustUse", booleanSchema()),
                        Map.entry("supportedClaims", arraySchema(stringSchema())),
                        Map.entry("notSupportedClaims", arraySchema(stringSchema())),
                        Map.entry("rankReason", stringSchema()),
                        Map.entry("reason", stringSchema())
                ), List.of("index", "score", "evidenceKind", "implementationPhase", "responsibility", "coverageGroup", "mustUse", "supportedClaims", "notSupportedClaims", "rankReason", "reason"))),
                "reason", stringSchema()
        ), List.of("selected", "reason"));
    }

    private Map<String, Object> evidenceGroupSchema() {
        return Map.of(
                "type", "string",
                "minLength", 1,
                "maxLength", MAX_EVIDENCE_GROUP_CHARS,
                "pattern", "^[a-z0-9]+(?:_[a-z0-9]+)*$"
        );
    }

    private List<CodeEvidenceCoverageSelection> parseCodeEvidenceCoverageSelections(Object value, int maxEvidenceIndex) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        List<CodeEvidenceCoverageSelection> selections = new ArrayList<>();
        for (Object item : collection) {
            if (selections.size() >= 8 || !(item instanceof Map<?, ?> map)) {
                continue;
            }
            String group = normalizeEvidenceGroup(stringValue(map.get("evidenceGroup")));
            if ("unknown".equals(group) || !(map.get("evidenceIndexes") instanceof Collection<?> indexes)) {
                continue;
            }
            LinkedHashSet<Integer> validIndexes = new LinkedHashSet<>();
            for (Object rawIndex : indexes) {
                try {
                    int index = rawIndex instanceof Number number
                            ? number.intValue()
                            : Integer.parseInt(String.valueOf(rawIndex));
                    if (index >= 1 && index <= maxEvidenceIndex) {
                        validIndexes.add(index);
                    }
                } catch (NumberFormatException ignored) {
                    // Invalid model-provided indexes cannot satisfy evidence coverage.
                }
            }
            List<String> supportedClaims = parsedStrings(map.get("supportedClaims"));
            if (!validIndexes.isEmpty() && !supportedClaims.isEmpty()) {
                selections.add(new CodeEvidenceCoverageSelection(group, List.copyOf(validIndexes), supportedClaims,
                        stringValue(map.get("pipelineStage"))));
            }
        }
        return List.copyOf(selections);
    }

    private Map<String, Object> semanticLabelSchema() {
        return Map.of(
                "type", "string",
                "minLength", 1,
                "maxLength", MAX_SEMANTIC_LABEL_CHARS,
                "pattern", "^[A-Za-z0-9]+(?:_[A-Za-z0-9]+)*$"
        );
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private Map<String, Object> arraySchema(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }

    private Map<String, Object> enumSchema(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }

    private Map<String, Object> stringSchema() {
        return Map.of("type", "string");
    }

    private Map<String, Object> booleanSchema() {
        return Map.of("type", "boolean");
    }

    private Map<String, Object> numberSchema() {
        return Map.of("type", "number");
    }

    private Map<String, Object> integerSchema() {
        return Map.of("type", "integer");
    }

    private void addQueries(Set<String> queries, Collection<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            addQuery(queries, value);
        }
    }

    private void addQuery(Set<String> queries, String value) {
        String query = sanitizeQuery(value);
        if (query.isBlank()) {
            return;
        }
        queries.add(query);
    }

    private String sanitizeQuery(String value) {
        String query = safe(value).replaceAll("\\s+", " ").trim();
        if (query.length() > MAX_QUERY_CHARS) {
            query = query.substring(0, MAX_QUERY_CHARS).trim();
        }
        return query;
    }

    private double coverage(String question, List<String> texts) {
        List<String> terms = queryTerms(question);
        if (terms.isEmpty()) {
            return 1.0;
        }
        String haystack = normalize(String.join(" ", texts));
        long matched = terms.stream().filter(haystack::contains).count();
        return (double) matched / (double) terms.size();
    }

    private List<String> queryTerms(String question) {
        String normalized = normalize(question);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> terms = new ArrayList<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2 && !isStopWord(token)) {
                terms.add(token);
            }
        }
        return terms.stream().distinct().limit(12).toList();
    }

    private boolean isStopWord(String token) {
        return List.of(
                "the", "and", "for", "with", "what", "where", "when", "how",
                "about", "please", "show", "tell", "this", "that"
        ).contains(token);
    }

    private String normalize(String value) {
        return safe(value)
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHangul}\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private List<String> reasons(boolean sufficient, boolean enoughCount, boolean enoughScore, boolean enoughCoverage) {
        if (sufficient) {
            return List.of("evidence sufficient");
        }
        List<String> reasons = new ArrayList<>();
        if (!enoughCount) {
            reasons.add("not enough evidence");
        }
        if (!enoughScore) {
            reasons.add("top score below threshold");
        }
        if (!enoughCoverage) {
            reasons.add("query coverage below threshold");
        }
        return reasons;
    }

    private List<Integer> citations(String answer) {
        List<Integer> citations = new ArrayList<>();
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        while (matcher.find()) {
            try {
                citations.add(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // Regex guarantees digits, but keep this path harmless.
            }
        }
        return citations;
    }

    private boolean isStructuredCodeEvidence(CodeSearchResult result) {
        return result != null && ("class".equals(result.chunkType())
                || "method".equals(result.chunkType())
                || "event_handler".equals(result.chunkType())
                || "xaml_event".equals(result.chunkType())
                || "xaml_view".equals(result.chunkType())
                || "project_structure".equals(result.chunkType())
                || "repository_summary".equals(result.chunkType())
                || "directory_summary".equals(result.chunkType())
                || "file_summary".equals(result.chunkType())
                || notBlank(result.methodName())
                || notBlank(result.className())
                || notBlank(result.symbolName()));
    }

    private boolean looksIncompleteEnding(String answer) {
        String trimmed = stripTrailingMarkdownNoise(answer);
        if (trimmed.isBlank()) {
            return true;
        }
        char last = trimmed.charAt(trimmed.length() - 1);
        if (isAcceptableTerminal(last)) {
            return false;
        }
        String lastLine = trimmed.lines()
                .reduce((first, second) -> second)
                .orElse(trimmed)
                .trim();
        String normalized = lastLine.replaceAll("\\s+", " ");
        return normalized.length() < 24 || endsWithDanglingWord(normalized);
    }

    private String stripTrailingMarkdownNoise(String answer) {
        String trimmed = safe(answer).trim();
        while (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }
        return trimmed;
    }

    private boolean isAcceptableTerminal(char value) {
        return ".?!。！？)]}`|".indexOf(value) >= 0
                || "다요음함됨임".indexOf(value) >= 0;
    }

    private boolean endsWithDanglingWord(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.endsWith("라는")
                || normalized.endsWith("이라는")
                || normalized.endsWith("위한")
                || normalized.endsWith("통해")
                || normalized.endsWith("및")
                || normalized.endsWith("또는")
                || normalized.endsWith("그리고")
                || normalized.endsWith("정");
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String normalizeEvidenceGroup(String value) {
        String normalized = safe(value)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return boundedOpaqueId(normalized, MAX_EVIDENCE_GROUP_CHARS, "unknown");
    }

    private String normalizeSemanticLabel(Object value, String fallback) {
        String normalized = stringValue(value)
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return boundedOpaqueId(normalized, MAX_SEMANTIC_LABEL_CHARS, fallback);
    }

    private String boundedOpaqueId(String value, int maxChars, String fallback) {
        String normalized = safe(value);
        if (normalized.isBlank()) {
            return fallback;
        }
        if (normalized.length() > maxChars) {
            normalized = normalized.substring(0, maxChars).replaceAll("_+$", "");
        }
        return normalized.isBlank() ? fallback : normalized;
    }

    private String metadataValue(CodeSearchResult result, String key) {
        if (result == null || result.metadata() == null || key == null) {
            return "";
        }
        Object value = result.metadata().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String normalizeEnumValue(Object value, String fallback, List<String> allowed) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isBlank()) {
            return fallback;
        }
        for (String candidate : allowed == null ? List.<String>of() : allowed) {
            if (candidate.equalsIgnoreCase(text)) {
                return candidate;
            }
        }
        return fallback;
    }

    private String normalizeCodeSearchOperationType(Object value) {
        String text = stringValue(value);
        if (text.isBlank()) {
            return "hybrid_search";
        }
        return normalizeEnumValue(text, "", CODE_SEARCH_OPERATION_TYPES);
    }

    private Integer nullableInteger(Object value) {
        if (value == null || value instanceof String text && text.isBlank()) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int parseInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private double parseDouble(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private String trimForPrompt(String value, int maxChars) {
        String text = safe(value).replaceAll("\\s+", " ").trim();
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars)).trim() + "...";
    }

    private String abbreviate(String value) {
        String compact = safe(value).replaceAll("\\s+", " ").trim();
        return compact.length() <= 120 ? compact : compact.substring(0, 120) + "...";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public enum Domain {
        DOCUMENT,
        CODE
    }

    public record QueryPlan(
            Domain domain,
            List<String> queries,
            boolean rewriteAttempted,
            boolean rewriteUsed,
            boolean rewriteFailed,
            String reason
    ) {
    }

    public record EvidenceAssessment(
            boolean sufficient,
            int iteration,
            double topScore,
            int distinctSources,
            double coverage,
            List<String> reasons
    ) {
    }

    public record AnswerAssessment(boolean acceptable, String reason) {
    }

    public record CodeEvidenceAdjudication(
            boolean attempted,
            boolean used,
            String reason,
            List<CodeSearchResult> results
    ) {
    }

    public record CodeEvidenceSearchPlan(
            boolean attempted,
            boolean usable,
            double confidence,
            List<String> queries,
            List<CodeEvidenceChecklistItem> checklist,
            String reason
    ) {
        public CodeEvidenceSearchPlan {
            queries = queries == null ? List.of() : List.copyOf(queries);
            checklist = checklist == null ? List.of() : List.copyOf(checklist);
            reason = reason == null ? "" : reason;
        }
    }

    public record CodeEvidenceChecklistItem(
            String claimId,
            String evidenceGroup,
            String goal,
            List<String> queries
    ) {
        public CodeEvidenceChecklistItem {
            claimId = claimId == null ? "" : claimId;
            evidenceGroup = evidenceGroup == null ? "unknown" : evidenceGroup;
            goal = goal == null ? "" : goal;
            queries = queries == null ? List.of() : List.copyOf(queries);
        }
    }

    public record CodeEvidenceFollowUpPlan(
            boolean attempted,
            boolean enough,
            String reason,
            List<String> missingAreas,
            List<String> followUpQueries,
            List<String> queryAreas,
            List<String> requiredEvidenceGroups,
            List<CodeEvidenceChecklistItem> checklist,
            List<CodeSearchOperation> operations,
            List<CodeEvidenceCoverageSelection> coverageSelections
    ) {
        public CodeEvidenceFollowUpPlan(
                boolean attempted,
                boolean enough,
                String reason,
                List<String> missingAreas,
                List<String> followUpQueries,
                List<String> queryAreas,
                List<String> requiredEvidenceGroups,
                List<CodeEvidenceChecklistItem> checklist,
                List<CodeSearchOperation> operations
        ) {
            this(attempted, enough, reason, missingAreas, followUpQueries, queryAreas,
                    requiredEvidenceGroups, checklist, operations, List.of());
        }

        public CodeEvidenceFollowUpPlan {
            reason = reason == null ? "" : reason;
            missingAreas = missingAreas == null ? List.of() : List.copyOf(missingAreas);
            followUpQueries = followUpQueries == null ? List.of() : List.copyOf(followUpQueries);
            queryAreas = queryAreas == null ? List.of() : List.copyOf(queryAreas);
            requiredEvidenceGroups = requiredEvidenceGroups == null ? List.of() : List.copyOf(requiredEvidenceGroups);
            checklist = checklist == null ? List.of() : List.copyOf(checklist);
            operations = operations == null ? List.of() : List.copyOf(operations);
            coverageSelections = coverageSelections == null ? List.of() : List.copyOf(coverageSelections);
        }
    }

    public record CodeEvidenceCoverageSelection(String evidenceGroup, List<Integer> evidenceIndexes,
                                                List<String> supportedClaims, String pipelineStage) {
        public CodeEvidenceCoverageSelection(String evidenceGroup, List<Integer> evidenceIndexes) {
            this(evidenceGroup, evidenceIndexes, List.of(), "unknown");
        }
        public CodeEvidenceCoverageSelection {
            evidenceGroup = evidenceGroup == null ? "unknown" : evidenceGroup;
            evidenceIndexes = evidenceIndexes == null ? List.of() : List.copyOf(evidenceIndexes);
            supportedClaims = supportedClaims == null ? List.of() : supportedClaims.stream()
                    .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
            pipelineStage = pipelineStage == null || pipelineStage.isBlank() ? "unknown" : pipelineStage;
        }
    }

    public record CodeSearchOperation(
            String type,
            String query,
            String area,
            String evidenceGroup,
            String path,
            String symbol,
            String chunkId,
            Integer lineStart,
            Integer lineEnd,
            Integer radius,
            List<String> relations,
            String direction,
            Integer maxHops
    ) {
        public CodeSearchOperation(String type, String query, String area, String evidenceGroup) {
            this(type, query, area, evidenceGroup, "", "", "", null, null, null, List.of(), "", null);
        }

        public CodeSearchOperation(String type, String query, String area, String evidenceGroup,
                                   String path, String symbol, String chunkId,
                                   Integer lineStart, Integer lineEnd, Integer radius) {
            this(type, query, area, evidenceGroup, path, symbol, chunkId,
                    lineStart, lineEnd, radius, List.of(), "", null);
        }

        public CodeSearchOperation {
            type = type == null ? "hybrid_search" : type;
            query = query == null ? "" : query;
            area = area == null ? "" : area;
            evidenceGroup = evidenceGroup == null ? "" : evidenceGroup;
            path = path == null ? "" : path;
            symbol = symbol == null ? "" : symbol;
            chunkId = chunkId == null ? "" : chunkId;
            relations = relations == null ? List.of() : relations.stream()
                    .map(value -> value == null ? "" : value.trim().toUpperCase(Locale.ROOT))
                    .filter(CODE_GRAPH_RELATION_TYPES::contains)
                    .distinct()
                    .limit(8)
                    .toList();
            direction = direction == null || direction.isBlank() ? "BOTH" : direction.trim().toUpperCase(Locale.ROOT);
        }

        public boolean isSearch() {
            return "keyword_search".equals(type)
                    || "hybrid_search".equals(type)
                    || "reference_search".equals(type);
        }

        public boolean isDirectRead() {
            return "read_chunk".equals(type)
                    || "read_symbol".equals(type)
                    || "list_file_symbols".equals(type)
                    || "read_file_range".equals(type)
                    || "read_adjacent".equals(type)
                    || "traverse_graph".equals(type);
        }

        public String validationError() {
            return switch (type) {
                case "keyword_search", "hybrid_search", "reference_search" -> query.isBlank() ? "query is required" : "";
                case "read_chunk" -> chunkId.isBlank() ? "chunkId is required" : "";
                case "read_symbol" -> symbol.isBlank() ? "symbol is required" : "";
                case "list_file_symbols" -> path.isBlank() ? "path is required" : "";
                case "read_file_range" -> {
                    if (path.isBlank()) {
                        yield "path is required";
                    }
                    if (lineStart == null) {
                        yield "lineStart is required";
                    }
                    if (lineEnd == null) {
                        yield "lineEnd is required";
                    }
                    yield "";
                }
                case "read_adjacent" -> chunkId.isBlank() ? "chunkId is required" : "";
                case "traverse_graph" -> {
                    if (chunkId.isBlank()) {
                        yield "chunkId is required";
                    }
                    if (relations.isEmpty()) {
                        yield "at least one supported relation is required";
                    }
                    if (!List.of("FORWARD", "REVERSE", "BOTH").contains(direction)) {
                        yield "direction must be FORWARD, REVERSE, or BOTH";
                    }
                    if (maxHops != null && (maxHops < 1 || maxHops > 3)) {
                        yield "maxHops must be between 1 and 3";
                    }
                    yield "";
                }
                default -> "unsupported operation type";
            };
        }
    }

    public enum CodeRagRoute {
        ANSWER_FROM_PRIOR,
        EXPAND_PREVIOUS_ANSWER,
        CODE_OVERVIEW_FLOW,
        LOCATE_SYMBOL,
        EXPLAIN_METHOD,
        IMPACT_ANALYSIS,
        COMMIT_DIFF,
        CLARIFY,
        CODE_SEARCH,
        UNKNOWN;

        static CodeRagRoute from(String value) {
            if (value == null || value.isBlank()) {
                return CODE_SEARCH;
            }
            try {
                return CodeRagRoute.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return UNKNOWN;
            }
        }
    }

    public record CodeRagRouteDecision(
            CodeRagRoute route,
            String mode,
            double confidence,
            List<String> queries,
            String commitRef,
            String targetFile,
            String targetSymbol,
            String reason,
            boolean attempted,
            boolean fallback
    ) {
        public CodeRagRouteDecision {
            route = route == null ? CodeRagRoute.CODE_SEARCH : route;
            mode = mode == null ? "" : mode;
            queries = queries == null ? List.of() : List.copyOf(queries);
            commitRef = commitRef == null ? "" : commitRef;
            targetFile = targetFile == null ? "" : targetFile;
            targetSymbol = targetSymbol == null ? "" : targetSymbol;
            reason = reason == null ? "" : reason;
        }

        static CodeRagRouteDecision fallback(String reason) {
            return new CodeRagRouteDecision(CodeRagRoute.CODE_SEARCH, "", 0.0, List.of(), "", "", "", reason, true, true);
        }
    }

    private record AdjudicatedCandidate(
            int index,
            double score,
            String evidenceKind,
            String implementationPhase,
            String responsibility,
            String coverageGroup,
            boolean mustUse,
            List<String> supportedClaims,
            List<String> notSupportedClaims,
            String rankReason,
            String reason,
            int slateRank
    ) {
        private AdjudicatedCandidate {
            evidenceKind = evidenceKind == null || evidenceKind.isBlank() ? "direct_code" : evidenceKind;
            implementationPhase = implementationPhase == null || implementationPhase.isBlank() ? "UNKNOWN" : implementationPhase;
            responsibility = responsibility == null || responsibility.isBlank() ? "unknown" : responsibility;
            coverageGroup = coverageGroup == null || coverageGroup.isBlank() ? "unknown" : coverageGroup;
            supportedClaims = supportedClaims == null ? List.of() : List.copyOf(supportedClaims);
            notSupportedClaims = notSupportedClaims == null ? List.of() : List.copyOf(notSupportedClaims);
            rankReason = rankReason == null ? "" : rankReason;
            reason = reason == null ? "" : reason;
        }
    }
}
