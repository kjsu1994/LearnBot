package com.learnbot.service;

import com.fasterxml.jackson.core.type.TypeReference;
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
    private static final int MAX_REWRITE_QUERIES = 6;
    private static final int MAX_QUERY_CHARS = 180;
    private static final int CODE_RAG_ROUTE_TIMEOUT_SECONDS = 20;

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
            String response = ollamaClient.chatResult(
                    rewriteSystemPrompt(domain),
                    rewriteUserPrompt(question, domain, baselineQueries),
                    OllamaClient.ChatRole.AUXILIARY,
                    Math.max(1, pipeline().getRewriteMaxOutputTokens()),
                    Duration.ofSeconds(Math.max(1, pipeline().getRewriteTimeoutSeconds()))
            ).content();
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
            String response = ollamaClient.chatResult(
                    codeRagRouterSystemPrompt(),
                    codeRagRouterUserPrompt(question, requestedMode, conversationContext, commitInsightAvailable),
                    OllamaClient.ChatRole.AUXILIARY,
                    420,
                    Duration.ofSeconds(CODE_RAG_ROUTE_TIMEOUT_SECONDS)
            ).content();
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
        if (!pipeline().isCodeEvidenceAdjudicationEnabled() || candidates == null || candidates.isEmpty()) {
            return new CodeEvidenceAdjudication(false, false, "llm code evidence adjudication disabled", candidates == null ? List.of() : candidates);
        }
        int candidateLimit = Math.max(1, Math.min(pipeline().getCodeEvidenceAdjudicationMaxCandidates(), candidates.size()));
        List<CodeSearchResult> head = candidates.stream().limit(candidateLimit).toList();
        try {
            String response = ollamaClient.chatResult(
                    codeAdjudicationSystemPrompt(),
                    codeAdjudicationUserPrompt(question, mode, head, limit),
                    OllamaClient.ChatRole.AUXILIARY,
                    Math.max(1, pipeline().getCodeEvidenceAdjudicationMaxOutputTokens()),
                    Duration.ofSeconds(Math.max(1, pipeline().getCodeEvidenceAdjudicationTimeoutSeconds()))
            ).content();
            Map<Integer, AdjudicatedCandidate> decisions = parseCodeAdjudication(response);
            if (decisions.isEmpty()) {
                return new CodeEvidenceAdjudication(true, false, "llm code evidence adjudication returned no usable selections", candidates);
            }
            List<CodeSearchResult> adjudicated = applyCodeAdjudication(candidates, head, decisions);
            return new CodeEvidenceAdjudication(true, true, "llm code evidence adjudication used", adjudicated);
        } catch (RuntimeException ex) {
            log.info("RAG code evidence adjudication skipped reason={} question={}",
                    ex.getClass().getSimpleName(), abbreviate(question));
            return new CodeEvidenceAdjudication(true, false, "llm code evidence adjudication failed", candidates);
        }
    }

    public CodeEvidenceFollowUpPlan planCodeEvidenceFollowUp(String question, String mode, List<CodeSearchResult> candidates, int maxQueries) {
        if (candidates == null || candidates.isEmpty()) {
            return new CodeEvidenceFollowUpPlan(false, true, "no initial evidence", List.of(), List.of(), List.of());
        }
        try {
            String response = ollamaClient.chatResult(
                    codeEvidenceCoverageSystemPrompt(),
                    codeEvidenceCoverageUserPrompt(question, mode, candidates),
                    OllamaClient.ChatRole.AUXILIARY,
                    520,
                    Duration.ofSeconds(12)
            ).content();
            return parseCodeEvidenceFollowUp(response, maxQueries);
        } catch (RuntimeException ex) {
            log.info("RAG code evidence follow-up planning skipped reason={} message={} question={}",
                    ex.getClass().getSimpleName(), safeMessage(ex), abbreviate(question));
            return new CodeEvidenceFollowUpPlan(true, true, "follow-up planner failed: " + safeMessage(ex), List.of(), List.of(), List.of());
        }
    }

    private LearnBotProperties.Rag.Pipeline pipeline() {
        return properties.getRag().getPipeline();
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
                Decide which retrieved code chunks are the best evidence for answering the user's question.
                Return strict JSON only. No Markdown.
                JSON schema: {"selected":[{"index":1,"score":0.0,"reason":"short reason"}],"reason":"short reason"}
                Rules:
                - Do not answer the user question.
                - Prefer source chunks that directly implement runtime behavior for architecture, flow, and reasoning questions.
                - Use tests only when the question asks about tests or when they are clearly supporting evidence.
                - Use local-agent/tooling chunks only when the question asks about local agents, tools, patching, or agent execution.
                - Prefer direct evidence over indirect summaries when both are available.
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
                JSON schema: {"enough":true,"missingAreas":["area"],"followUpQueries":["query"],"queryAreas":["area for query"],"reason":"short reason"}
                Rules:
                - Set enough=false when evidence is mostly tests, frontend gates, history storage, retention, docs, generated, or vendor code but the question asks about runtime behavior.
                - Conversation history repositories, UI gate/status helpers, retention/cleanup services, and verification summaries are supporting evidence only. They are not enough for runtime flow or answer-generation questions unless the user explicitly asks about them.
                - If the current evidence would force the final answer to say that implementation details are not visible, set enough=false and request follow-up queries for the missing implementation path.
                - For broad flow questions, spread follow-up queries across distinct missing areas such as entrypoint, processing/chunking, persistence/index storage, retrieval/search, context construction, and answer/model generation.
                - Do not spend all follow-up queries on indexing or storage if retrieval/search or answer/model generation is missing.
                - For RAG answer-generation questions, require direct runtime evidence for retrieval/search, context construction, and model/answer generation, not only stored conversation turns.
                - queryAreas must align one-to-one with followUpQueries when possible.
                - Keep follow-up queries short, concrete, and source-code oriented.
                """;
    }

    private String codeEvidenceCoverageUserPrompt(String question, String mode, List<CodeSearchResult> candidates) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Question:\n").append(safe(question)).append("\n\n");
        prompt.append("Question mode: ").append(safe(mode)).append("\n\n");
        prompt.append("Current evidence candidates:\n");
        for (int index = 0; index < Math.min(10, candidates.size()); index++) {
            CodeSearchResult result = candidates.get(index);
            CodeSourceClassifier.SourceProfile profile = CodeSourceClassifier.classify(result);
            prompt.append(index + 1).append(". file=").append(safe(result.filePath()))
                    .append(" lines=").append(result.lineStart()).append("-").append(result.lineEnd())
                    .append(" chunkType=").append(safe(result.chunkType()))
                    .append(" sourceRole=").append(profile.sourceRole())
                    .append(" runtimeRole=").append(profile.runtimeRole())
                    .append(" domainRole=").append(profile.domainRole())
                    .append("\nSymbols: ")
                    .append(safe(result.className())).append(" ")
                    .append(safe(result.methodName())).append(" ")
                    .append(safe(result.symbolName())).append("\n")
                    .append("Excerpt:\n")
                    .append(trimForPrompt(result.content(), 520))
                    .append("\n\n");
        }
        prompt.append("Return JSON only.");
        return prompt.toString();
    }

    private CodeEvidenceFollowUpPlan parseCodeEvidenceFollowUp(String response, int maxQueries) {
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
            String reason = stringValue(parsed.get("reason"));
            return new CodeEvidenceFollowUpPlan(true, enough, reason, missingAreas, enough ? List.of() : queries, enough ? List.of() : queryAreas);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid code evidence follow-up JSON", ex);
        }
    }

    private String codeAdjudicationUserPrompt(String question, String mode, List<CodeSearchResult> candidates, int limit) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Question:\n").append(safe(question)).append("\n\n");
        prompt.append("Question mode: ").append(safe(mode)).append("\n");
        prompt.append("Maximum useful evidence items: ").append(Math.max(1, limit)).append("\n\n");
        prompt.append("Candidates:\n");
        for (int index = 0; index < candidates.size(); index++) {
            CodeSearchResult result = candidates.get(index);
            CodeSourceClassifier.SourceProfile profile = CodeSourceClassifier.classify(result);
            prompt.append(index + 1).append(". file=").append(safe(result.filePath()))
                    .append(" lines=").append(result.lineStart()).append("-").append(result.lineEnd())
                    .append(" chunkType=").append(safe(result.chunkType()))
                    .append(" sourceRole=").append(profile.sourceRole())
                    .append(" runtimeRole=").append(profile.runtimeRole())
                    .append(" domainRole=").append(profile.domainRole())
                    .append(" parserConfidence=").append(profile.parserConfidence())
                    .append("\nSymbols: ")
                    .append(safe(result.className())).append(" ")
                    .append(safe(result.methodName())).append(" ")
                    .append(safe(result.symbolName())).append("\n")
                    .append("Excerpt:\n")
                    .append(trimForPrompt(result.content(), 900))
                    .append("\n\n");
        }
        prompt.append("Return JSON only.");
        return prompt.toString();
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
                decisions.put(index, new AdjudicatedCandidate(index, score, reason));
                rank++;
            }
            return Map.copyOf(decisions);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid code evidence adjudication JSON", ex);
        }
    }

    private List<CodeSearchResult> applyCodeAdjudication(List<CodeSearchResult> candidates, List<CodeSearchResult> head, Map<Integer, AdjudicatedCandidate> decisions) {
        List<CodeSearchResult> adjusted = new ArrayList<>();
        for (int index = 0; index < head.size(); index++) {
            CodeSearchResult result = head.get(index);
            AdjudicatedCandidate decision = decisions.get(index + 1);
            double bonus = decision == null ? -0.04 : 0.28 + (decision.score() * 0.32);
            adjusted.add(withAdjudicationMetadata(result, decision, bonus));
        }
        if (candidates.size() > head.size()) {
            adjusted.addAll(candidates.subList(head.size(), candidates.size()));
        }
        return adjusted.stream()
                .sorted(java.util.Comparator.comparingDouble(this::adjudicatedScore).reversed())
                .toList();
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

    private String extractJsonObject(String response) {
        String text = safe(response).trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "";
        }
        return text.substring(start, end + 1);
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
        String query = safe(value).replaceAll("\\s+", " ").trim();
        if (query.isBlank()) {
            return;
        }
        if (query.length() > MAX_QUERY_CHARS) {
            query = query.substring(0, MAX_QUERY_CHARS).trim();
        }
        queries.add(query);
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

    public record CodeEvidenceFollowUpPlan(
            boolean attempted,
            boolean enough,
            String reason,
            List<String> missingAreas,
            List<String> followUpQueries,
            List<String> queryAreas
    ) {
        public CodeEvidenceFollowUpPlan {
            reason = reason == null ? "" : reason;
            missingAreas = missingAreas == null ? List.of() : List.copyOf(missingAreas);
            followUpQueries = followUpQueries == null ? List.of() : List.copyOf(followUpQueries);
            queryAreas = queryAreas == null ? List.of() : List.copyOf(queryAreas);
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

    private record AdjudicatedCandidate(int index, double score, String reason) {
    }
}
