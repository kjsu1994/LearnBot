package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.AdminTuningMetricSample;
import com.learnbot.dto.CodeAskResponse;
import com.learnbot.dto.CodeConversationAnchor;
import com.learnbot.dto.CodeEvidence;
import com.learnbot.dto.CodeSearchResult;
import com.learnbot.dto.PreviousAnswerItem;
import com.learnbot.dto.RagConversationContext;
import com.learnbot.dto.RagConversationTurnContext;
import com.learnbot.repository.CodeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class CodeRagService {
    private static final int OVERVIEW_CONTEXT_LIMIT = 12;
    private static final int DEFAULT_CONTEXT_LIMIT = 8;
    private static final int OVERVIEW_CONTEXT_CHARS = 620;
    private static final int DEFAULT_CONTEXT_CHARS = 1200;
    private static final int FALLBACK_EXCERPT_CHARS = 180;
    private static final double CONVERSATION_PINNED_BOOST = 0.18;

    private final CodeSearchService searchService;
    private final CodeRepository codeRepository;
    private final CodeReferenceService referenceService;
    private final CommitInsightService commitInsightService;
    private final OllamaClient ollamaClient;
    private final LearnBotProperties properties;
    private final RagPipelineService pipelineService;
    private final CodeEvidenceRanker evidenceRanker;
    private final RagMetricsService ragMetricsService;

    @Autowired
    public CodeRagService(
            CodeSearchService searchService,
            CodeRepository codeRepository,
            CodeReferenceService referenceService,
            CommitInsightService commitInsightService,
            OllamaClient ollamaClient,
            LearnBotProperties properties,
            RagPipelineService pipelineService,
            CodeEvidenceRanker evidenceRanker,
            RagMetricsService ragMetricsService
    ) {
        this.searchService = searchService;
        this.codeRepository = codeRepository;
        this.referenceService = referenceService;
        this.commitInsightService = commitInsightService;
        this.ollamaClient = ollamaClient;
        this.properties = properties;
        this.pipelineService = pipelineService;
        this.evidenceRanker = evidenceRanker;
        this.ragMetricsService = ragMetricsService;
    }

    public CodeRagService(
            CodeSearchService searchService,
            CodeReferenceService referenceService,
            CommitInsightService commitInsightService,
            OllamaClient ollamaClient,
            LearnBotProperties properties,
            RagPipelineService pipelineService,
            CodeEvidenceRanker evidenceRanker
    ) {
        this(searchService, null, referenceService, commitInsightService, ollamaClient, properties, pipelineService, evidenceRanker, null);
    }

    public CodeRagService(
            CodeSearchService searchService,
            CodeReferenceService referenceService,
            CommitInsightService commitInsightService,
            OllamaClient ollamaClient,
            LearnBotProperties properties,
            RagPipelineService pipelineService
    ) {
        this(searchService, null, referenceService, commitInsightService, ollamaClient, properties, pipelineService, new CodeEvidenceRanker(properties), null);
    }

    CodeRagService(
            CodeSearchService searchService,
            CodeReferenceService referenceService,
            CommitInsightService commitInsightService,
            OllamaClient ollamaClient,
            LearnBotProperties properties
    ) {
        this(searchService, null, referenceService, commitInsightService, ollamaClient, properties, new RagPipelineService(ollamaClient, properties), new CodeEvidenceRanker(properties), null);
    }

    CodeRagService(
            CodeSearchService searchService,
            CodeReferenceService referenceService,
            OllamaClient ollamaClient,
            LearnBotProperties properties
    ) {
        this(searchService, referenceService, null, ollamaClient, properties);
    }

    public CodeAskResponse ask(UUID repositoryId, String question, String mode, Integer limit) {
        return ask(repositoryId, null, java.util.List.of(com.learnbot.repository.SecurityRepository.DEFAULT_SPACE_ID), question, mode, limit);
    }

    public CodeAskResponse ask(UUID repositoryId, UUID selectedSpaceId, List<UUID> spaceIds, String question, String mode, Integer limit) {
        if (commitInsightService != null && commitInsightService.isCommitQuestion(question)) {
            return commitInsightService.answer(repositoryId, question);
        }
        ollamaClient.beginPrimaryRequest();
        try {
            return askPrioritized(repositoryId, selectedSpaceId, spaceIds, question, mode, limit, null);
        } finally {
            ollamaClient.finishPrimaryRequest();
        }
    }

    public CodeAskResponse askConversational(UUID repositoryId, UUID selectedSpaceId, List<UUID> spaceIds, String question, String mode, Integer limit, RagConversationContext conversationContext) {
        if (commitInsightService != null && commitInsightService.isCommitQuestion(question)) {
            return commitInsightService.answer(repositoryId, question);
        }
        ollamaClient.beginPrimaryRequest();
        try {
            return askPrioritized(repositoryId, selectedSpaceId, spaceIds, question, mode, limit, conversationContext);
        } finally {
            ollamaClient.finishPrimaryRequest();
        }
    }

    private CodeAskResponse askPrioritized(UUID repositoryId, UUID selectedSpaceId, List<UUID> spaceIds, String question, String mode, Integer limit, RagConversationContext conversationContext) {
        long askStarted = System.nanoTime();
        String originalQuestion = safe(question, "");
        String effectiveQuestion = effectiveQuestion(originalQuestion, conversationContext);
        CodeQuestionMode questionMode = classifyCodeQuestion(effectiveQuestion, mode, conversationContext);
        int safeLimit = safeLimit(questionMode, limit);
        long retrievalStarted = System.nanoTime();
        CodeRetrieval retrieval = retrieveCodeEvidence(repositoryId, selectedSpaceId, spaceIds, effectiveQuestion, questionMode, safeLimit, conversationContext);
        long retrievalMs = elapsedMs(retrievalStarted);
        List<CodeSearchResult> results = retrieval.results();
        if (results.isEmpty()) {
            recordMetrics(questionMode.value(), retrieval, retrievalMs, 0, 0, 0, 0, 0, false, false, elapsedMs(askStarted));
            return new CodeAskResponse(
                    questionMode.value(),
                    "코드 근거가 부족해 답변할 수 없습니다. 질문 범위를 좁히거나 파일명, 화면명, 메서드명 같은 단서를 더 넣어주세요.",
                    List.of(),
                    "낮음",
                    List.of("검색된 코드 근거가 없어 추측 답변을 생성하지 않았습니다.")
            );
        }

        String systemPrompt = """
                You are LearnBot Code, a private source-code RAG assistant.
                Answer in Korean using only the provided source-code context.
                Do not invent files, classes, methods, or behavior not shown in the context.
                Always cite evidence with bracket numbers like [1].
                Mention file path and line range when explaining code.
                If evidence is insufficient, say what is missing and list the closest files found.
                Include a short reliability note when evidence is weak or indirect.
                For code explanations, structure the answer as follows when applicable:
                1. Summary
                2. Detailed explanation
                3. Execution flow
                4. Related files, classes, and methods
                5. Important implementation details
                
                Use markdown headings.
                Prefer detailed explanations over brief summaries.
                Explain not only what the code does, but also why it exists and how it interacts with related components.
                Do not speculate beyond the provided evidence.
                """ + "\n" + questionMode.instruction();
        long contextStarted = System.nanoTime();
        String promptPrefix = questionPrompt(originalQuestion, effectiveQuestion, conversationContext)
                + conversationFocus(conversationContext);
        CodeContextBundle contextBundle = buildBudgetedContext(
                effectiveQuestion,
                questionMode,
                systemPrompt,
                promptPrefix,
                answerContextResults(questionMode, effectiveQuestion, results)
        );
        List<CodeSearchResult> answerResults = contextBundle.results();
        String userPrompt = promptPrefix + "\n\nSource-code context:\n" + contextBundle.context();
        long contextMs = elapsedMs(contextStarted);
        String answer;
        boolean llmUnavailable = false;
        boolean answerRewritten = false;
        boolean answerRetried = false;
        String answerDoneReason = null;
        OllamaClient.ChatResult finalChatResult = null;
        long llmMs = 0;
        try {
            long llmStarted = System.nanoTime();
            OllamaClient.ChatResult chatResult = ollamaClient.chatResult(systemPrompt, userPrompt);
            llmMs += elapsedMs(llmStarted);
            finalChatResult = chatResult;
            answer = chatResult.content();
            answerDoneReason = chatResult.doneReason();
            String qualityReason = qualityFailureReason(answer, answerResults.size(), answerDoneReason);
            if (qualityReason != null && pipelineService.maxIterations() > 1) {
                String retryPrompt = userPrompt
                        + "\n\nPrevious answer failed quality check: " + qualityReason + "."
                        + "\nRewrite the answer using only the cited code context. Cite every factual claim with [n].";
                long retryStarted = System.nanoTime();
                OllamaClient.ChatResult retryResult = ollamaClient.chatResult(systemPrompt + "\nBe concise and citation-strict.", retryPrompt);
                llmMs += elapsedMs(retryStarted);
                String retryAnswer = retryResult.content();
                if (qualityFailureReason(retryAnswer, answerResults.size(), retryResult.doneReason()) == null) {
                    answer = retryAnswer;
                    answerDoneReason = retryResult.doneReason();
                    finalChatResult = retryResult;
                    answerRetried = true;
                }
            }
        } catch (RuntimeException ex) {
            answer = fallbackAnswer(questionMode, originalQuestion, answerResults);
            answerDoneReason = null;
            llmUnavailable = true;
        }
        if (qualityFailureReason(answer, answerResults.size(), answerDoneReason) != null) {
            answer = questionMode == CodeQuestionMode.OVERVIEW
                    ? overviewFallbackAnswer(answerResults)
                    : fallbackAnswer(questionMode, originalQuestion, answerResults);
            answerRewritten = true;
        }
        recordMetrics(
                questionMode.value(),
                retrieval,
                retrievalMs,
                contextMs,
                llmMs,
                answerResults.size(),
                finalChatResult == null ? 0 : finalChatResult.promptEvalCount(),
                finalChatResult == null ? 0 : finalChatResult.evalCount(),
                llmUnavailable || answerRewritten,
                llmUnavailable,
                elapsedMs(askStarted)
        );
        return new CodeAskResponse(
                questionMode.value(),
                answer,
                buildEvidence(answerResults),
                confidence(answerResults, retrieval.assessment()),
                conversationDiagnostics(
                        diagnostics(questionMode, results, answerResults, llmUnavailable, answerRewritten, answerRetried, retrieval),
                        originalQuestion,
                        effectiveQuestion,
                        conversationContext,
                        retrieval
                )
        );
    }

    private String effectiveQuestion(String originalQuestion, RagConversationContext conversationContext) {
        if (conversationContext == null || !conversationContext.contextual()) {
            return safe(originalQuestion, "");
        }
        if (conversationContext.previousAnswerExpansion()) {
            return safe(originalQuestion, "");
        }
        String rewritten = safe(conversationContext.rewrittenQuestion(), "");
        return rewritten.isBlank() ? safe(originalQuestion, "") : rewritten;
    }

    private String questionPrompt(String originalQuestion, String effectiveQuestion, RagConversationContext conversationContext) {
        if (conversationContext != null && conversationContext.previousAnswerExpansion()) {
            return "Original user question:\n" + originalQuestion
                    + "\n\nThis is a request to expand the previous answer. Keep the previous answer outline and expand each item using only the current source-code context.";
        }
        if (conversationContext == null || !conversationContext.contextual() || safe(effectiveQuestion, "").equals(safe(originalQuestion, ""))) {
            return "Question:\n" + originalQuestion;
        }
        return "Original user question:\n" + originalQuestion
                + "\n\nConversation-aware search question:\n" + effectiveQuestion
                + "\n\nAnswer the original user question. Use the conversation-aware question only to resolve references.";
    }

    private int safeLimit(CodeQuestionMode questionMode, Integer limit) {
        int defaultLimit = questionMode == CodeQuestionMode.OVERVIEW
                ? Math.max(properties.getCode().getTopK(), 14)
                : properties.getCode().getTopK();
        return limit == null ? defaultLimit : Math.max(1, Math.min(limit, 24));
    }

    private CodeQuestionMode classifyCodeQuestion(String question, String mode, RagConversationContext conversationContext) {
        CodeQuestionMode requested = CodeQuestionMode.from(mode);
        if (!properties.getRag().getOverview().isEnabled()) {
            return requested;
        }
        boolean autoMode = mode == null || mode.isBlank() || "auto".equalsIgnoreCase(mode.trim());
        if (autoMode && previousAnswerExpansion(conversationContext)) {
            return CodeQuestionMode.OVERVIEW;
        }
        boolean explicitMode = !autoMode;
        if (explicitMode && requested != CodeQuestionMode.OVERVIEW) {
            return requested;
        }
        String normalized = normalizeCodeText(question);
        if (containsAny(normalized, "flow", "workflow", "sequence", "request flow", "call flow", "흐름", "과정", "절차", "순서", "호출")) {
            return CodeQuestionMode.CALL_FLOW;
        }
        if (containsAny(normalized, "impact", "effect", "affected", "test", "fix", "bug", "problem", "영향", "변경 영향", "테스트", "수정", "문제", "버그")) {
            return CodeQuestionMode.IMPACT;
        }
        if (containsAny(normalized, "locate", "where", "file", "line", "path", "위치", "어디", "파일", "라인", "경로")) {
            return CodeQuestionMode.LOCATE;
        }
        if (containsAny(normalized, "architecture", "structure", "overview", "module", "component", "아키텍처", "구조", "개요", "구성", "전체")) {
            return CodeQuestionMode.OVERVIEW;
        }
        if (autoMode && conversationContext != null && conversationContext.contextual()) {
            CodeQuestionMode previousMode = previousTurnMode(conversationContext);
            if (previousMode != null) {
                return previousMode;
            }
            return conversationAnchorFallbackMode(conversationContext);
        }
        return requested;
    }

    private CodeQuestionMode previousTurnMode(RagConversationContext conversationContext) {
        if (conversationContext == null || conversationContext.recentTurns() == null) {
            return null;
        }
        return conversationContext.recentTurns().stream()
                .map(RagConversationTurnContext::mode)
                .filter(mode -> mode != null && !mode.isBlank())
                .map(String::trim)
                .flatMap(mode -> java.util.Arrays.stream(CodeQuestionMode.values())
                        .filter(candidate -> candidate.value().equalsIgnoreCase(mode))
                        .findFirst()
                        .stream())
                .filter(this::canInheritAutoMode)
                .findFirst()
                .orElse(null);
    }

    private CodeQuestionMode conversationAnchorFallbackMode(RagConversationContext conversationContext) {
        if (conversationContext == null || conversationContext.codeAnchors() == null || conversationContext.codeAnchors().isEmpty()) {
            return CodeQuestionMode.OVERVIEW;
        }
        boolean hasMethodAnchor = conversationContext.codeAnchors().stream()
                .anyMatch(anchor -> anchor.methodName() != null && !anchor.methodName().isBlank());
        return hasMethodAnchor ? CodeQuestionMode.EXPLAIN_METHOD : CodeQuestionMode.OVERVIEW;
    }

    private boolean canInheritAutoMode(CodeQuestionMode mode) {
        return mode == CodeQuestionMode.LOCATE
                || mode == CodeQuestionMode.EXPLAIN_METHOD
                || mode == CodeQuestionMode.UI_EVENT;
    }

    private CodeRetrieval retrieveCodeEvidence(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            String question,
            CodeQuestionMode questionMode,
            int limit,
            RagConversationContext conversationContext
    ) {
        Map<UUID, CodeSearchResult> merged = new LinkedHashMap<>();
        int pinnedCandidateCount = collectPinnedConversationEvidence(repositoryId, selectedSpaceId, spaceIds, question, conversationContext, merged);
        int searchLimit = pipelineService.codeSearchLimit(questionMode == CodeQuestionMode.OVERVIEW ? limit + 12 : limit + 8);
        collectEvidenceForQuery(repositoryId, selectedSpaceId, spaceIds, question, questionMode, searchLimit, merged);
        for (String query : conversationAnchorQueries(question, conversationContext)) {
            collectEvidenceForQuery(repositoryId, selectedSpaceId, spaceIds, query, questionMode, searchLimit, merged);
        }
        if (questionMode == CodeQuestionMode.OVERVIEW || questionMode == CodeQuestionMode.CALL_FLOW) {
            for (String query : codeOverviewQueries(question, questionMode)) {
                collectEvidenceForQuery(repositoryId, selectedSpaceId, spaceIds, query, questionMode, searchLimit, merged);
            }
        }
        List<CodeSearchResult> results = rankedCodeEvidence(question, questionMode, merged, limit);
        RagPipelineService.EvidenceAssessment assessment = pipelineService.assessCode(
                question,
                results,
                minCodeEvidence(questionMode),
                1
        );
        RagPipelineService.QueryPlan queryPlan = new RagPipelineService.QueryPlan(
                RagPipelineService.Domain.CODE,
                conversationAnchorQueries(question, conversationContext).isEmpty()
                        ? List.of(question)
                        : java.util.stream.Stream.concat(java.util.stream.Stream.of(question), conversationAnchorQueries(question, conversationContext).stream()).toList(),
                false,
                false,
                "initial search"
        );
        int iteration = 1;

        if (!assessment.sufficient() && pipelineService.maxIterations() > 1) {
            queryPlan = pipelineService.buildQueryPlan(
                    question,
                    RagPipelineService.Domain.CODE,
                    searchService.expandedQueries(question)
            );
            for (String query : queryPlan.queries()) {
                collectEvidenceForQuery(repositoryId, selectedSpaceId, spaceIds, query, questionMode, searchLimit, merged);
            }
            iteration = 2;
            results = rankedCodeEvidence(question, questionMode, merged, limit);
            assessment = pipelineService.assessCode(
                    question,
                    results,
                    minCodeEvidence(questionMode),
                    iteration
            );
        }

        int pinnedUsedCount = (int) results.stream().filter(this::isConversationPinned).count();
        return new CodeRetrieval(results, assessment, queryPlan, iteration, merged.size(), pinnedCandidateCount, pinnedUsedCount);
    }

    private int collectPinnedConversationEvidence(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            String effectiveQuestion,
            RagConversationContext conversationContext,
            Map<UUID, CodeSearchResult> merged
    ) {
        if (codeRepository == null || conversationContext == null) {
            return 0;
        }
        Set<UUID> requiredIds = requiredCodeChunkIds(conversationContext);
        if (requiredIds.isEmpty() && (conversationContext.codeAnchors() == null || conversationContext.codeAnchors().isEmpty())) {
            return 0;
        }
        Set<UUID> chunkIds = new java.util.LinkedHashSet<>(requiredIds);
        (conversationContext.codeAnchors() == null ? List.<CodeConversationAnchor>of() : conversationContext.codeAnchors()).stream()
                .map(CodeConversationAnchor::chunkId)
                .filter(id -> id != null)
                .distinct()
                .limit(8)
                .forEach(chunkIds::add);
        if (chunkIds.isEmpty()) {
            return 0;
        }
        try {
            List<CodeSearchResult> pinned = codeRepository.findActiveChunksByIds(repositoryId, List.copyOf(chunkIds), spaceIds, selectedSpaceId);
            int added = 0;
            boolean weakQuestionTerms = primaryQuestionTerms(effectiveQuestion).size() <= 2;
            for (CodeSearchResult result : pinned) {
                boolean required = requiredIds.contains(result.chunkId());
                if (!required && !previousAnswerExpansion(conversationContext) && !weakQuestionTerms && !isRelevantPinnedEvidence(effectiveQuestion, result)) {
                    continue;
                }
                merge(merged, markConversationPinned(result, required || added < 2 || isRelevantPinnedEvidence(effectiveQuestion, result), required, previousItemLabel(conversationContext, result.chunkId())));
                added++;
            }
            return added;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private boolean isRelevantPinnedEvidence(String question, CodeSearchResult result) {
        List<String> terms = primaryQuestionTerms(question);
        if (terms.isEmpty()) {
            return true;
        }
        String target = normalizeCodeText(String.join(" ",
                safe(result.filePath(), ""),
                safe(result.symbolName(), ""),
                safe(result.className(), ""),
                safe(result.methodName(), ""),
                safe(result.content(), "")
        ));
        return terms.stream().anyMatch(target::contains);
    }

    private CodeSearchResult markConversationPinned(CodeSearchResult result, boolean boost) {
        return markConversationPinned(result, boost, false, "");
    }

    private CodeSearchResult markConversationPinned(CodeSearchResult result, boolean boost, boolean required, String previousItemLabel) {
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
        metadata.put("conversationPinned", true);
        metadata.put("conversationAnchor", true);
        metadata.put("evidenceRole", "conversation_pinned");
        metadata.put("evidenceRankReason", "Pinned from previous code conversation evidence");
        if (required) {
            metadata.put("conversationRequired", true);
        }
        if (previousItemLabel != null && !previousItemLabel.isBlank()) {
            metadata.put("previousAnswerItem", previousItemLabel);
        }
        return new CodeSearchResult(
                result.chunkId(), result.repositoryId(), result.fileId(), result.repositoryName(), result.filePath(),
                result.chunkType(), result.symbolName(), result.className(), result.methodName(), result.namespaceName(),
                result.controlName(), result.eventName(), result.chunkIndex(), result.lineStart(), result.lineEnd(),
                result.content(), boost ? result.score() + CONVERSATION_PINNED_BOOST : result.score(), Map.copyOf(metadata)
        );
    }

    private List<String> conversationAnchorQueries(String question, RagConversationContext conversationContext) {
        if (conversationContext == null || conversationContext.codeAnchors() == null || conversationContext.codeAnchors().isEmpty()) {
            return List.of();
        }
        List<String> queries = new ArrayList<>();
        boolean expansion = previousAnswerExpansion(conversationContext);
        for (CodeConversationAnchor anchor : conversationContext.codeAnchors()) {
            String query = String.join(" ",
                    expansion ? "" : safe(question, ""),
                    safe(anchor.filePath(), ""),
                    safe(anchor.symbolName(), ""),
                    safe(anchor.className(), ""),
                    safe(anchor.methodName(), "")
            ).trim();
            if (!query.isBlank() && !queries.contains(query)) {
                queries.add(query);
            }
            if (queries.size() >= 6) {
                break;
            }
        }
        return queries;
    }

    private String conversationFocus(RagConversationContext conversationContext) {
        if (conversationContext == null || !conversationContext.contextual()) {
            return "";
        }
        String recentTurns = conversationContext.recentTurns() == null ? "" : conversationContext.recentTurns().stream()
                .limit(3)
                .map(this::conversationTurnSummary)
                .filter(summary -> !summary.isBlank())
                .collect(Collectors.joining("\n"));
        String anchors = conversationContext.codeAnchors() == null ? "" : conversationContext.codeAnchors().stream()
                .limit(5)
                .map(anchor -> "- " + safe(anchor.filePath(), "unknown")
                        + nullable(" / symbol=", anchor.symbolName())
                        + nullable(" / class=", anchor.className())
                        + nullable(" / method=", anchor.methodName())
                        + (anchor.lineStart() > 0 ? " / lines=" + anchor.lineStart() + "-" + Math.max(anchor.lineStart(), anchor.lineEnd()) : ""))
                .collect(Collectors.joining("\n"));
        String previousOutline = previousAnswerOutline(conversationContext);
        return "\n\nConversation focus:\n"
                + (conversationContext.previousAnswerExpansion()
                ? "Previous-answer expansion mode: keep the previous answer item structure, expand each item only from current source-code context, cite every item, and mark insufficient items as \"\ucd94\uac00 \uadfc\uac70 \ubd80\uc871\".\n"
                : "Use the previous conversation only to resolve follow-up references. Ignore it if it conflicts with the retrieved source-code context.\n")
                + (previousOutline.isBlank() ? "" : "Previous answer outline:\n" + previousOutline + "\n")
                + (recentTurns.isBlank() ? "" : "Recent turns:\n" + recentTurns + "\n")
                + (anchors.isBlank() ? "" : "Previous code evidence anchors:\n" + anchors);
    }

    private String previousAnswerOutline(RagConversationContext conversationContext) {
        if (conversationContext == null || conversationContext.previousAnswerItems().isEmpty()) {
            return "";
        }
        return conversationContext.previousAnswerItems().stream()
                .limit(12)
                .map(item -> "- " + safe(item.label(), "")
                        + (item.citationNumbers().isEmpty() ? "" : " / previous citations=" + item.citationNumbers())
                        + (item.evidenceChunkIds().isEmpty() ? " / \ucd94\uac00 \uadfc\uac70 \ubd80\uc871" : " / requiredChunks=" + item.evidenceChunkIds()))
                .collect(Collectors.joining("\n"));
    }

    private String conversationTurnSummary(RagConversationTurnContext turn) {
        if (turn == null) {
            return "";
        }
        return "- Q: " + trimInline(turn.question())
                + "\n  Evidence: " + conversationEvidenceSummary(turn.evidence());
    }

    private String conversationEvidenceSummary(com.fasterxml.jackson.databind.JsonNode evidence) {
        if (evidence == null || !evidence.isArray() || evidence.isEmpty()) {
            return "none";
        }
        List<String> values = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode item : evidence) {
            if (values.size() >= 3) {
                break;
            }
            String filePath = item.path("filePath").asText("");
            String symbol = item.path("methodName").asText(item.path("symbolName").asText(""));
            if (!filePath.isBlank()) {
                values.add(filePath + (symbol == null || symbol.isBlank() ? "" : "#" + symbol));
            }
        }
        return values.isEmpty() ? "none" : String.join("; ", values);
    }

    private void collectEvidenceForQuery(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            String query,
            CodeQuestionMode questionMode,
            int limit,
            Map<UUID, CodeSearchResult> merged
    ) {
        List<CodeSearchResult> results = collectEvidence(repositoryId, selectedSpaceId, spaceIds, query, questionMode, limit);
        for (CodeSearchResult result : results) {
            merge(merged, result);
        }
    }

    private List<CodeSearchResult> rankedCodeEvidence(
            String question,
            CodeQuestionMode questionMode,
            Map<UUID, CodeSearchResult> merged,
            int limit
    ) {
        return evidenceRanker.rank(question, questionMode, List.copyOf(merged.values())).stream()
                .limit(limit)
                .toList();
    }

    private int minCodeEvidence(CodeQuestionMode questionMode) {
        return switch (questionMode) {
            case OVERVIEW, IMPACT -> 4;
            case CALL_FLOW -> 3;
            default -> 2;
        };
    }

    private List<CodeSearchResult> collectEvidence(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            String question,
            CodeQuestionMode questionMode,
            int limit
    ) {
        Map<UUID, CodeSearchResult> merged = new LinkedHashMap<>();
        int searchLimit = questionMode == CodeQuestionMode.OVERVIEW ? Math.min(30, limit + 12) : Math.min(30, limit + 8);
        for (CodeSearchResult result : searchService.search(repositoryId, question, searchLimit, spaceIds, selectedSpaceId)) {
            merge(merged, result);
        }
        List<String> identifiers = searchService.identifiersFrom(question);
        for (String identifier : identifiers == null ? List.<String>of() : identifiers) {
            try {
                var references = referenceService.findReferences(repositoryId, selectedSpaceId, spaceIds, identifier, 10);
                for (CodeSearchResult definition : references.definitions()) {
                    merge(merged, boost(definition, questionMode == CodeQuestionMode.OVERVIEW ? 0.28 : 0.35));
                }
                for (CodeSearchResult reference : references.references()) {
                    merge(merged, boost(reference, questionMode == CodeQuestionMode.CALL_FLOW ? 0.22 : 0.12));
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid symbol candidates should not block a natural-language code answer.
            }
        }
        return evidenceRanker.rank(question, questionMode, List.copyOf(merged.values())).stream()
                .limit(limit)
                .toList();
    }

    private List<CodeSearchResult> answerContextResults(CodeQuestionMode questionMode, String question, List<CodeSearchResult> results) {
        int limit = pipelineService.codeContextLimit(questionMode == CodeQuestionMode.OVERVIEW ? OVERVIEW_CONTEXT_LIMIT : DEFAULT_CONTEXT_LIMIT);
        List<CodeSearchResult> ranked = evidenceRanker.rank(question, questionMode, results);
        List<CodeSearchResult> selected;
        if (questionMode == CodeQuestionMode.CALL_FLOW) {
            selected = ranked.stream()
                    .sorted(Comparator.comparingDouble((CodeSearchResult result) -> evidenceRanker.score(result)).reversed()
                            .thenComparingInt(this::flowRank))
                    .limit(limit)
                    .toList();
            return preservePinnedEvidence(ranked, selected, limit);
        }
        if (questionMode == CodeQuestionMode.OVERVIEW || questionMode == CodeQuestionMode.IMPACT) {
            selected = diverseByCategory(ranked, limit);
            return preservePinnedEvidence(ranked, selected, limit);
        }
        selected = ranked.stream().limit(limit).toList();
        return preservePinnedEvidence(ranked, selected, limit);
    }

    private List<CodeSearchResult> preservePinnedEvidence(List<CodeSearchResult> ranked, List<CodeSearchResult> selected, int limit) {
        if (ranked == null || selected == null || selected.stream().anyMatch(this::isConversationPinned)) {
            return preserveRequiredEvidence(ranked, selected == null ? List.of() : selected, limit);
        }
        java.util.Optional<CodeSearchResult> pinned = ranked.stream().filter(this::isConversationPinned).findFirst();
        if (pinned.isEmpty() || selected.stream().anyMatch(result -> result.chunkId().equals(pinned.get().chunkId()))) {
            return preserveRequiredEvidence(ranked, selected, limit);
        }
        List<CodeSearchResult> adjusted = new ArrayList<>(selected);
        if (adjusted.size() < limit) {
            adjusted.add(pinned.get());
            return preserveRequiredEvidence(ranked, adjusted, limit);
        }
        for (int index = adjusted.size() - 1; index >= 0; index--) {
            if (!isConversationPinned(adjusted.get(index))) {
                adjusted.set(index, pinned.get());
                return preserveRequiredEvidence(ranked, adjusted, limit);
            }
        }
        return preserveRequiredEvidence(ranked, selected, limit);
    }

    private List<CodeSearchResult> preserveRequiredEvidence(List<CodeSearchResult> ranked, List<CodeSearchResult> selected, int limit) {
        List<CodeSearchResult> adjusted = new ArrayList<>(selected == null ? List.of() : selected);
        List<CodeSearchResult> required = ranked.stream()
                .filter(this::isRequiredConversationPinned)
                .filter(result -> adjusted.stream().noneMatch(current -> current.chunkId().equals(result.chunkId())))
                .toList();
        for (CodeSearchResult result : required) {
            if (adjusted.size() < limit) {
                adjusted.add(result);
                continue;
            }
            boolean replaced = false;
            for (int index = adjusted.size() - 1; index >= 0; index--) {
                if (!isRequiredConversationPinned(adjusted.get(index))) {
                    adjusted.set(index, result);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                break;
            }
        }
        return adjusted;
    }

    private List<CodeSearchResult> diverseByCategory(List<CodeSearchResult> ranked, int limit) {
        Map<String, CodeSearchResult> selected = new LinkedHashMap<>();
        Set<UUID> seenChunks = new HashSet<>();
        for (CodeSearchResult result : ranked) {
            String category = category(result);
            if (!selected.containsKey(category) && seenChunks.add(result.chunkId())) {
                selected.put(category, result);
            }
            if (selected.size() >= limit) {
                break;
            }
        }
        for (CodeSearchResult result : ranked) {
            if (seenChunks.add(result.chunkId())) {
                selected.putIfAbsent(result.chunkId().toString(), result);
            }
            if (selected.size() >= limit) {
                break;
            }
        }
        int categoryLimit = questionModeLimit(limit);
        return selected.values().stream().limit(Math.min(limit, categoryLimit)).toList();
    }

    private int questionModeLimit(int limit) {
        return Math.max(1, Math.min(limit, pipelineService.overviewMaxCodeCategories()));
    }

    private List<String> codeOverviewQueries(String question, CodeQuestionMode questionMode) {
        String base = safe(question, "").trim();
        if (questionMode == CodeQuestionMode.CALL_FLOW) {
            return List.of(
                    base + " controller service repository handler request response flow",
                    "call flow execution sequence entrypoint service repository",
                    "요청 처리 흐름 컨트롤러 서비스 저장소 핸들러"
            );
        }
        return List.of(
                base + " project structure architecture modules responsibilities",
                "project structure repository summary module map architecture",
                "아키텍처 구조 구성 모듈 책임 전체 개요"
        );
    }

    private boolean containsAny(String value, String... needles) {
        String safeValue = safe(value, "");
        for (String needle : needles) {
            if (safeValue.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String buildContext(String question, CodeQuestionMode questionMode, List<CodeSearchResult> results) {
        if (results.isEmpty()) {
            return "No source-code context retrieved.";
        }
        int maxChars = questionMode == CodeQuestionMode.OVERVIEW ? OVERVIEW_CONTEXT_CHARS : DEFAULT_CONTEXT_CHARS;
        return IntStream.range(0, results.size())
                .mapToObj(index -> {
                    CodeSearchResult result = results.get(index);
                    return "[" + (index + 1) + "] "
                            + result.filePath() + ":" + result.lineStart() + "-" + result.lineEnd()
                            + " type=" + result.chunkType()
                            + nullable(" class=", result.className())
                            + nullable(" method=", result.methodName())
                            + nullable(" control=", result.controlName())
                            + nullable(" event=", result.eventName())
                            + graphContext(result)
                            + evidenceRankingContext(result)
                            + "\n" + codeExcerpt(question, result, maxChars);
                })
                .collect(Collectors.joining("\n\n"));
    }

    private CodeContextBundle buildBudgetedContext(
            String question,
            CodeQuestionMode questionMode,
            String systemPrompt,
            String promptPrefix,
            List<CodeSearchResult> results
    ) {
        List<CodeSearchResult> selected = new ArrayList<>(results == null ? List.of() : results);
        String context = buildContext(question, questionMode, selected);
        int budget = promptTokenBudget();
        int requiredCount = (int) selected.stream().filter(this::isRequiredConversationPinned).count();
        int minResults = Math.min(selected.size(), Math.max(requiredCount, isConversationPinned(selected) ? 1 : Math.min(2, selected.size())));
        while (selected.size() > minResults
                && estimateTokens(systemPrompt) + estimateTokens(promptPrefix) + estimateTokens(context) > budget) {
            removeBudgetCandidate(selected);
            context = buildContext(question, questionMode, selected);
        }
        return new CodeContextBundle(List.copyOf(selected), context);
    }

    private boolean isConversationPinned(List<CodeSearchResult> results) {
        return results != null && results.stream().anyMatch(this::isConversationPinned);
    }

    private void removeBudgetCandidate(List<CodeSearchResult> selected) {
        for (int index = selected.size() - 1; index >= 0; index--) {
            if (!isConversationPinned(selected.get(index)) && !isRequiredConversationPinned(selected.get(index))) {
                selected.remove(index);
                return;
            }
        }
        for (int index = selected.size() - 1; index >= 0; index--) {
            if (!isRequiredConversationPinned(selected.get(index))) {
                selected.remove(index);
                return;
            }
        }
        selected.remove(selected.size() - 1);
    }

    private int promptTokenBudget() {
        int contextWindow = Math.max(2048, pipelineService.contextWindow());
        int configured = Math.max(512, pipelineService.promptTokenBudgetBalanced());
        return Math.min(configured, Math.max(1800, contextWindow - 700));
    }

    private int estimateTokens(String value) {
        String compact = safe(value, "").trim();
        if (compact.isEmpty()) {
            return 0;
        }
        return Math.max(1, (compact.length() + 2) / 3);
    }

    private String fallbackAnswer(CodeQuestionMode questionMode, String question, List<CodeSearchResult> results) {
        if (results.isEmpty()) {
            return "LLM 답변을 생성하지 못했고 관련 코드 근거도 찾지 못했습니다.";
        }
        return switch (questionMode) {
            case LOCATE -> locateFallbackAnswer(results);
            case EXPLAIN_METHOD -> methodFallbackAnswer(question, results);
            case CALL_FLOW -> flowFallbackAnswer(results);
            case UI_EVENT -> uiEventFallbackAnswer(results);
            case IMPACT -> impactFallbackAnswer(results);
            case OVERVIEW -> overviewFallbackAnswer(results);
        };
    }

    private String overviewFallbackAnswer(List<CodeSearchResult> results) {
        String repositoryName = results.stream()
                .map(CodeSearchResult::repositoryName)
                .filter(this::notBlank)
                .findFirst()
                .orElse("선택한 저장소");
        String purpose = inferPurpose(results);
        StringBuilder answer = new StringBuilder();
        answer.append("검색된 코드 근거 기준으로 보면, `")
                .append(repositoryName)
                .append("`은 ")
                .append(purpose)
                .append("입니다.\n\n");
        answer.append("주요 구성은 다음과 같습니다.\n");
        categoryEvidence(results).forEach((category, result) -> answer
                .append("- ")
                .append(category)
                .append(": `")
                .append(result.filePath())
                .append("` ")
                .append(result.lineStart())
                .append("-")
                .append(result.lineEnd())
                .append(" 근거에서 확인됩니다 [")
                .append(results.indexOf(result) + 1)
                .append("].\n"));
        answer.append("\n확인 한계: 이 설명은 현재 검색된 ")
                .append(results.size())
                .append("개 코드 근거를 요약한 것입니다. 저장소 전체 목적을 더 정확히 보려면 README, 설정 파일, 주요 엔트리포인트를 함께 인덱싱하거나 더 구체적인 질문을 추가하는 것이 좋습니다.");
        return answer.toString();
    }

    private String locateFallbackAnswer(List<CodeSearchResult> results) {
        String candidates = IntStream.range(0, Math.min(results.size(), 6))
                .mapToObj(index -> {
                    CodeSearchResult result = results.get(index);
                    return "- `" + result.filePath() + "` " + result.lineStart() + "-" + result.lineEnd()
                            + fallbackSymbolText(result)
                            + ": " + evidenceSummary(result)
                            + " [" + (index + 1) + "]";
                })
                .collect(Collectors.joining("\n"));
        return "LLM 답변 품질이 낮아 검색 근거 기준으로 후보 위치를 정리합니다.\n\n" + candidates
                + "\n\n확인 한계: 검색된 코드 조각 기준의 후보입니다. 정확한 진입점은 호출 흐름 탭에서 함께 확인하는 것이 좋습니다.";
    }

    private String methodFallbackAnswer(String question, List<CodeSearchResult> results) {
        CodeSearchResult primary = results.stream()
                .filter(result -> notBlank(result.methodName()) || "method".equals(result.chunkType()))
                .findFirst()
                .orElse(results.get(0));
        String related = IntStream.range(0, Math.min(results.size(), 5))
                .mapToObj(index -> {
                    CodeSearchResult result = results.get(index);
                    return "- `" + result.filePath() + "` " + result.lineStart() + "-" + result.lineEnd()
                            + fallbackSymbolText(result)
                            + " [" + (index + 1) + "]";
                })
                .collect(Collectors.joining("\n"));
        return "LLM 답변 품질이 낮아 검색 근거 기준으로 메서드 후보를 설명합니다.\n\n"
                + "가장 직접적인 후보는 `" + primary.filePath() + "` " + primary.lineStart() + "-" + primary.lineEnd()
                + fallbackSymbolText(primary) + "입니다 [1]. "
                + "코드 발췌상 `" + safe(primary.methodName(), safe(primary.symbolName(), "해당 심볼"))
                + "` 주변에서 요청한 동작과 관련된 처리가 확인됩니다: "
                + trimInline(codeExcerpt(question, primary, FALLBACK_EXCERPT_CHARS)) + " [1]\n\n"
                + "함께 확인할 근거:\n" + related;
    }

    private String flowFallbackAnswer(List<CodeSearchResult> results) {
        List<CodeSearchResult> ordered = results.stream()
                .sorted(Comparator.comparingInt(this::flowRank).thenComparing(CodeSearchResult::filePath))
                .limit(6)
                .toList();
        String steps = IntStream.range(0, ordered.size())
                .mapToObj(index -> {
                    CodeSearchResult result = ordered.get(index);
                    int citation = results.indexOf(result) + 1;
                    return (index + 1) + ". " + flowLabel(result) + " `" + result.filePath()
                            + "` " + result.lineStart() + "-" + result.lineEnd()
                            + fallbackSymbolText(result)
                            + " [" + citation + "]";
                })
                .collect(Collectors.joining("\n"));
        return "LLM 답변 품질이 낮아 검색 근거 기준으로 호출 흐름 후보를 정리합니다.\n\n" + steps
                + "\n\n확인 한계: 실제 런타임 호출 순서는 검색된 조각만으로는 일부 누락될 수 있습니다. 컨트롤러/핸들러에서 서비스, 저장소 순으로 추가 확인하세요.";
    }

    private String uiEventFallbackAnswer(List<CodeSearchResult> results) {
        String events = IntStream.range(0, Math.min(results.size(), 6))
                .mapToObj(index -> {
                    CodeSearchResult result = results.get(index);
                    String eventText = nullable(" control=", result.controlName()) + nullable(" event=", result.eventName());
                    return "- `" + result.filePath() + "` " + result.lineStart() + "-" + result.lineEnd()
                            + (eventText.isBlank() ? fallbackSymbolText(result) : eventText)
                            + " [" + (index + 1) + "]";
                })
                .collect(Collectors.joining("\n"));
        return "LLM 답변 품질이 낮아 UI 이벤트 근거를 후보 중심으로 정리합니다.\n\n" + events;
    }

    private String impactFallbackAnswer(List<CodeSearchResult> results) {
        String areas = categoryEvidence(results).entrySet().stream()
                .map(entry -> {
                    CodeSearchResult result = entry.getValue();
                    return "- " + entry.getKey() + ": `" + result.filePath() + "` "
                            + result.lineStart() + "-" + result.lineEnd()
                            + fallbackSymbolText(result)
                            + " [" + (results.indexOf(result) + 1) + "]";
                })
                .collect(Collectors.joining("\n"));
        return "LLM 답변 품질이 낮아 검색 근거 기준으로 영향 가능 영역을 정리합니다.\n\n" + areas
                + "\n\n확인 한계: 영향도는 정적 검색 근거 기준입니다. 실제 변경 전에는 호출 흐름과 테스트 커버리지를 함께 확인해야 합니다.";
    }

    private List<CodeEvidence> buildEvidence(List<CodeSearchResult> results) {
        return IntStream.range(0, results.size())
                .mapToObj(index -> {
                    CodeSearchResult result = results.get(index);
                    return new CodeEvidence(
                            index + 1,
                            result.chunkId(),
                            result.repositoryId(),
                            result.fileId(),
                            result.repositoryName(),
                            result.filePath(),
                            result.chunkType(),
                            result.symbolName(),
                            result.className(),
                            result.methodName(),
                            result.controlName(),
                            result.eventName(),
                            result.lineStart(),
                            result.lineEnd(),
                            preview(result.content()),
                            result.score(),
                            evidenceRanker.responseMetadata(result.metadata())
                    );
                })
                .toList();
    }

    private String preview(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String compact = content.replaceAll("\\s+", " ").trim();
        return compact.length() <= 420 ? compact : compact.substring(0, 420) + "...";
    }

    private String confidence(List<CodeSearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "낮음";
        }
        double topScore = results.stream().mapToDouble(evidenceRanker::score).max().orElse(results.get(0).score());
        long distinctFiles = results.stream().map(CodeSearchResult::filePath).distinct().count();
        boolean hasStructuredEvidence = results.stream().anyMatch(result ->
                isStructured(result.chunkType()) || notBlank(result.methodName()) || notBlank(result.className()) || notBlank(result.symbolName())
        );
        CodeEvidenceRanker.GraphReliabilitySummary graph = evidenceRanker.summarizeGraph(results);
        boolean strongGraphEvidence = graph.strong() >= 2 || (graph.strong() >= 1 && graph.medium() >= 2);
        if ((hasStructuredEvidence && results.size() >= 4 && topScore >= 0.55 && distinctFiles <= 6)
                || (strongGraphEvidence && topScore >= 0.90 && distinctFiles <= 8)) {
            return "높음";
        }
        if (hasStructuredEvidence || results.size() >= 3 || topScore >= 0.35 || (graph.strong() + graph.medium()) >= 2) {
            return "보통";
        }
        return "낮음";
    }

    private String confidence(List<CodeSearchResult> results, RagPipelineService.EvidenceAssessment assessment) {
        String value = confidence(results);
        if (assessment != null && !assessment.sufficient() && "높음".equals(value)) {
            return "보통";
        }
        return value;
    }

    private List<String> diagnostics(
            CodeQuestionMode questionMode,
            List<CodeSearchResult> results,
            List<CodeSearchResult> answerResults,
            boolean llmUnavailable,
            boolean answerRewritten,
            boolean answerRetried,
            CodeRetrieval retrieval
    ) {
        List<String> notes = new ArrayList<>(diagnostics(results, answerResults, llmUnavailable, answerRewritten));
        if (questionMode == CodeQuestionMode.OVERVIEW || questionMode == CodeQuestionMode.CALL_FLOW || questionMode == CodeQuestionMode.IMPACT) {
            long projectContext = answerResults.stream().filter(result -> isProjectContext(result.chunkType())).count();
            long distinctFiles = answerResults.stream().map(CodeSearchResult::filePath).distinct().count();
            notes.add("Code question mode was classified as " + questionMode.name()
                    + "; answer context used " + projectContext + " project context chunks and "
                    + distinctFiles + " distinct files.");
        }
        if (retrieval != null && retrieval.iteration() > 1) {
            notes.add("RAG pipeline retried code retrieval once because the first evidence set was weak.");
        }
        if (retrieval != null && retrieval.queryPlan().rewriteUsed()) {
            notes.add("RAG pipeline used query rewrite as an auxiliary code retrieval signal.");
        }
        if (retrieval != null && retrieval.queryPlan().rewriteFailed()) {
            notes.add("RAG query rewrite failed, so deterministic hybrid code search was used.");
        }
        if (retrieval != null && !retrieval.assessment().sufficient()) {
            notes.add("Code evidence sufficiency check remained weak: " + String.join(", ", retrieval.assessment().reasons()));
        }
        if (answerResults.stream().anyMatch(this::isGraphExpanded)) {
            notes.add("Code GraphRAG expanded related evidence through indexed code relationships.");
            CodeEvidenceRanker.GraphReliabilitySummary graph = evidenceRanker.summarizeGraph(answerResults);
            notes.add("Graph evidence: " + graph.expanded() + " expanded chunks, "
                    + graph.strong() + " strong, "
                    + graph.medium() + " medium, "
                    + graph.partial() + " partial.");
            if (!graph.edgeSummary().isBlank()) {
                notes.add("Top graph edges: " + graph.edgeSummary() + ".");
            }
        }
        if (answerResults.stream().anyMatch(result -> result.metadata() != null && result.metadata().containsKey("evidenceScore"))) {
            notes.add("Code evidence was ranked with deterministic evidence scoring before answer context selection.");
            if (evidenceRanker.debug()) {
                String rankingDetails = answerResults.stream()
                        .limit(5)
                        .map(result -> {
                            Map<String, Object> metadata = result.metadata() == null ? Map.of() : result.metadata();
                            return result.filePath() + " score=" + evidenceRanker.score(result)
                                    + " reliability=" + String.valueOf(metadata.getOrDefault("graphReliability", "none"))
                                    + " reason=" + String.valueOf(metadata.getOrDefault("evidenceRankReason", ""));
                        })
                        .collect(Collectors.joining("; "));
                notes.add("Evidence ranking debug: " + rankingDetails);
            }
        }
        if (answerRetried) {
            notes.add("Answer self-check retried generation once before returning the final answer.");
        }
        return notes;
    }

    private List<String> diagnostics(
            List<CodeSearchResult> results,
            List<CodeSearchResult> answerResults,
            boolean llmUnavailable,
            boolean answerRewritten
    ) {
        long distinctFiles = results.stream().map(CodeSearchResult::filePath).distinct().count();
        List<String> notes = new ArrayList<>();
        notes.add("검색된 코드 근거 " + results.size() + "개, 파일 " + distinctFiles + "개 중 "
                + answerResults.size() + "개를 답변 컨텍스트로 사용했습니다.");
        if (llmUnavailable) {
            notes.add("LLM 호출이 실패해 검색 근거 기반 fallback 답변을 반환했습니다.");
        }
        if (answerRewritten) {
            notes.add("LLM 응답이 너무 짧거나 인용이 부족해, 검색 근거 기반 답변으로 대체했습니다.");
        }
        if ("낮음".equals(confidence(results))) {
            notes.add("직접적인 정의/호출 근거가 약하므로 후보 파일로 검토해야 합니다.");
        }
        return notes;
    }

    private boolean isLowQualityAnswer(String answer, CodeQuestionMode questionMode) {
        if (answer == null || answer.isBlank()) {
            return true;
        }
        String trimmed = answer.trim();
        if (trimmed.length() < 30) {
            return true;
        }
        if (!containsCitation(trimmed)) {
            return true;
        }
        return false;
    }

    private String qualityFailureReason(String answer, int evidenceCount) {
        return qualityFailureReason(answer, evidenceCount, null);
    }

    private String qualityFailureReason(String answer, int evidenceCount, String doneReason) {
        if (isLowQualityAnswer(answer, null)) {
            if (answer == null || answer.isBlank()) {
                return "blank";
            }
            if (answer.trim().length() < 30) {
                return "too short";
            }
            if (!containsCitation(answer)) {
                return "missing citation";
            }
            return "low quality";
        }
        RagPipelineService.AnswerAssessment assessment = pipelineService.assessAnswer(answer, evidenceCount, true, doneReason);
        return assessment.acceptable() ? null : assessment.reason();
    }

    private boolean containsCitation(String answer) {
        return answer != null && answer.matches("(?s).*\\[\\d+].*");
    }

    private List<String> primaryQuestionTerms(String question) {
        List<String> terms = new ArrayList<>();
        addTerms(terms, question);
        String normalized = normalizeCodeText(question);
        if (isLoginQuestion(question)) {
            terms.addAll(List.of("login", "signin", "auth", "authentication", "로그인", "인증"));
        }
        if (normalized.contains("인덱") || normalized.contains("index")) {
            terms.addAll(List.of("index", "indexing", "repository", "chunk", "embedding", "인덱싱"));
        }
        if (normalized.contains("오류") || normalized.contains("실패") || normalized.contains("error")) {
            terms.addAll(List.of("error", "exception", "failed", "failure", "실패", "오류"));
        }
        if (normalized.contains("관리자") || normalized.contains("admin")) {
            terms.addAll(List.of("admin", "관리자", "role", "authority"));
        }
        return terms.stream()
                .map(this::normalizeCodeText)
                .filter(term -> term.length() >= 2 && !isQuestionStopWord(term))
                .distinct()
                .toList();
    }

    private boolean isLoginQuestion(String question) {
        String normalized = normalizeCodeText(question);
        return normalized.contains("로그인") || normalized.contains("login") || normalized.contains("signin");
    }

    private boolean isQuestionStopWord(String term) {
        return List.of("관련", "파일", "어디", "있어", "있나요", "어떻게", "동작", "설명", "위치", "찾아", "찾기", "코드").contains(term);
    }

    private String codeExcerpt(String question, CodeSearchResult result, int maxChars) {
        String content = result == null ? "" : result.content();
        String compact = content == null ? "" : content.replaceAll("\\R{3,}", "\n\n").trim();
        if (compact.length() <= maxChars) {
            return compact;
        }

        List<String> lines = compact.lines()
                .map(String::stripTrailing)
                .filter(line -> !line.isBlank())
                .toList();
        List<String> terms = codeQueryTerms(question, result);
        Map<Integer, String> selected = new LinkedHashMap<>();
        for (int index = 0; index < lines.size(); index++) {
            String normalizedLine = normalizeCodeText(lines.get(index));
            boolean matches = terms.stream().anyMatch(normalizedLine::contains);
            if (!matches) {
                continue;
            }
            for (int offset = -1; offset <= 1; offset++) {
                int selectedIndex = index + offset;
                if (selectedIndex >= 0 && selectedIndex < lines.size()) {
                    selected.putIfAbsent(selectedIndex, lines.get(selectedIndex));
                }
            }
            if (selected.size() >= 18) {
                break;
            }
        }

        String excerpt = selected.isEmpty()
                ? compact.substring(0, Math.min(compact.length(), maxChars))
                : selected.values().stream().collect(Collectors.joining("\n"));
        if (excerpt.length() > maxChars) {
            excerpt = excerpt.substring(0, maxChars);
        }
        return excerpt.stripTrailing() + (excerpt.length() < compact.length() ? "\n..." : "");
    }

    private List<String> codeQueryTerms(String question, CodeSearchResult result) {
        List<String> terms = new ArrayList<>();
        addTerms(terms, question);
        if (result != null) {
            addTerms(terms, result.filePath());
            addTerms(terms, result.symbolName());
            addTerms(terms, result.className());
            addTerms(terms, result.methodName());
            addTerms(terms, result.controlName());
            addTerms(terms, result.eventName());
        }
        String normalized = normalizeCodeText(question);
        if (normalized.contains("로그인") || normalized.contains("login")) {
            terms.addAll(List.of("login", "signin", "auth", "authentication", "session", "token"));
        }
        if (normalized.contains("인덱") || normalized.contains("index")) {
            terms.addAll(List.of("index", "indexing", "repository", "chunk", "embedding", "job"));
        }
        if (normalized.contains("오류") || normalized.contains("실패") || normalized.contains("error")) {
            terms.addAll(List.of("error", "exception", "failed", "failure", "status", "message"));
        }
        if (normalized.contains("호출") || normalized.contains("흐름") || normalized.contains("flow")) {
            terms.addAll(List.of("controller", "service", "repository", "handler", "request", "response"));
        }
        return terms.stream()
                .map(this::normalizeCodeText)
                .filter(term -> term.length() >= 2)
                .distinct()
                .toList();
    }

    private void addTerms(List<String> terms, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String token : normalizeCodeText(value).split("\\s+")) {
            if (token.length() >= 2) {
                terms.add(token);
            }
        }
    }

    private String normalizeCodeText(String value) {
        return value == null
                ? ""
                : value.replaceAll("([a-z])([A-Z])", "$1 $2")
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{IsHangul}\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String fallbackSymbolText(CodeSearchResult result) {
        if (notBlank(result.methodName())) {
            return " / method `" + result.methodName() + "`";
        }
        if (notBlank(result.className())) {
            return " / class `" + result.className() + "`";
        }
        if (notBlank(result.symbolName())) {
            return " / symbol `" + result.symbolName() + "`";
        }
        return nullable(" / " + result.chunkType() + " ", result.symbolName());
    }

    private String evidenceSummary(CodeSearchResult result) {
        if (notBlank(result.methodName())) {
            return "`" + result.methodName() + "` 메서드 주변 코드가 검색되었습니다";
        }
        if (notBlank(result.className())) {
            return "`" + result.className() + "` 클래스 주변 코드가 검색되었습니다";
        }
        if (notBlank(result.chunkType())) {
            return result.chunkType() + " 코드 조각이 검색되었습니다";
        }
        return "관련 코드 조각이 검색되었습니다";
    }

    private String trimInline(String value) {
        String compact = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (compact.length() <= FALLBACK_EXCERPT_CHARS) {
            return compact;
        }
        return compact.substring(0, FALLBACK_EXCERPT_CHARS).trim() + "...";
    }

    private int flowRank(CodeSearchResult result) {
        String path = result.filePath() == null ? "" : result.filePath().toLowerCase(java.util.Locale.ROOT);
        if (path.startsWith("frontend/") || path.contains("/view/") || path.contains("/pages/")) {
            return 0;
        }
        if (path.contains("controller") || path.contains("/web/")) {
            return 1;
        }
        if (path.contains("/service/")) {
            return 2;
        }
        if (path.contains("/repository/")) {
            return 3;
        }
        if (path.contains("/config/") || path.contains("/security/")) {
            return 4;
        }
        return 5;
    }

    private String flowLabel(CodeSearchResult result) {
        return switch (flowRank(result)) {
            case 0 -> "화면/요청 진입 후보";
            case 1 -> "API 컨트롤러 후보";
            case 2 -> "서비스 처리 후보";
            case 3 -> "데이터 접근 후보";
            case 4 -> "설정/보안 처리 후보";
            default -> "관련 코드 후보";
        };
    }

    private String inferPurpose(List<CodeSearchResult> results) {
        String joinedPaths = results.stream()
                .map(CodeSearchResult::filePath)
                .collect(Collectors.joining(" "))
                .toLowerCase(java.util.Locale.ROOT);
        if (joinedPaths.contains("rag") || joinedPaths.contains("document") || joinedPaths.contains("index") || joinedPaths.contains("embedding")) {
            return "문서/코드 RAG, 저장소 인덱싱, 검색, 질문 답변을 다루는 애플리케이션 코드";
        }
        if (joinedPaths.contains("auth") || joinedPaths.contains("security") || joinedPaths.contains("admin")) {
            return "인증과 관리자 기능을 포함한 업무용 애플리케이션 코드";
        }
        if (joinedPaths.contains("frontend") || joinedPaths.contains("src/app")) {
            return "프론트엔드 화면과 API 연동을 포함한 애플리케이션 코드";
        }
        if (joinedPaths.contains("controller") || joinedPaths.contains("service") || joinedPaths.contains("repository")) {
            return "API, 서비스, 데이터 접근 계층으로 구성된 백엔드 애플리케이션 코드";
        }
        return "여러 모듈로 구성된 애플리케이션 코드";
    }

    private Map<String, CodeSearchResult> categoryEvidence(List<CodeSearchResult> results) {
        Map<String, CodeSearchResult> categories = new LinkedHashMap<>();
        for (CodeSearchResult result : results) {
            categories.putIfAbsent(category(result), result);
            if (categories.size() >= 6) {
                break;
            }
        }
        return categories;
    }

    private String category(CodeSearchResult result) {
        String path = result.filePath() == null ? "" : result.filePath().toLowerCase(java.util.Locale.ROOT);
        if (path.contains("/web/") || path.contains("controller")) {
            return "API/컨트롤러 계층";
        }
        if (path.contains("/service/")) {
            return "서비스 및 RAG 처리 계층";
        }
        if (path.contains("/repository/")) {
            return "DB 접근 계층";
        }
        if (path.contains("/security/") || path.contains("/config/")) {
            return "보안/설정 계층";
        }
        if (path.contains("/dto/")) {
            return "요청/응답 DTO 계층";
        }
        if (path.startsWith("frontend/")) {
            return "프론트엔드 화면 계층";
        }
        return "기타 코드 영역";
    }

    private void merge(Map<UUID, CodeSearchResult> merged, CodeSearchResult result) {
        CodeSearchResult current = merged.get(result.chunkId());
        if (current == null) {
            merged.put(result.chunkId(), result);
            return;
        }
        if (isConversationPinned(current) && !isConversationPinned(result)) {
            return;
        }
        if (isConversationPinned(result) && !isConversationPinned(current)) {
            merged.put(result.chunkId(), result);
            return;
        }
        if (result.score() > current.score()) {
            merged.put(result.chunkId(), result);
        }
    }

    private CodeSearchResult boost(CodeSearchResult result, double value) {
        return new CodeSearchResult(
                result.chunkId(),
                result.repositoryId(),
                result.fileId(),
                result.repositoryName(),
                result.filePath(),
                result.chunkType(),
                result.symbolName(),
                result.className(),
                result.methodName(),
                result.namespaceName(),
                result.controlName(),
                result.eventName(),
                result.chunkIndex(),
                result.lineStart(),
                result.lineEnd(),
                result.content(),
                result.score() + value,
                result.metadata()
        );
    }

    private boolean isStructured(String chunkType) {
        return "class".equals(chunkType)
                || "method".equals(chunkType)
                || "event_handler".equals(chunkType)
                || "xaml_event".equals(chunkType)
                || "xaml_view".equals(chunkType)
                || isProjectContext(chunkType);
    }

    private boolean isProjectContext(String chunkType) {
        return "project_structure".equals(chunkType)
                || "repository_summary".equals(chunkType)
                || "directory_summary".equals(chunkType)
                || "file_summary".equals(chunkType);
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String graphContext(CodeSearchResult result) {
        if (!isGraphExpanded(result)) {
            return "";
        }
        Object edgeType = result.metadata().get("graphEdgeType");
        Object graphPath = result.metadata().get("graphPath");
        Object edgeTypes = result.metadata().get("graphEdgeTypes");
        Object depth = result.metadata().get("graphDepth");
        return " graph=" + safe(edgeType == null ? null : String.valueOf(edgeType), "RELATED")
                + nullable(" edges=", edgeTypes == null ? null : String.valueOf(edgeTypes))
                + nullable(" depth=", depth == null ? null : String.valueOf(depth))
                + nullable(" path=", graphPath == null ? null : String.valueOf(graphPath));
    }

    private String evidenceRankingContext(CodeSearchResult result) {
        if (result == null || result.metadata() == null || !result.metadata().containsKey("evidenceScore")) {
            return "";
        }
        Object evidenceScore = result.metadata().get("evidenceScore");
        Object reason = result.metadata().get("evidenceRankReason");
        return nullable(" rank=", evidenceScore == null ? null : String.valueOf(evidenceScore))
                + (evidenceRanker.debug() && isGraphExpanded(result) ? nullable(" reason=", reason == null ? null : String.valueOf(reason)) : "");
    }

    private boolean isConversationPinned(CodeSearchResult result) {
        return result != null && result.metadata() != null && Boolean.TRUE.equals(result.metadata().get("conversationPinned"));
    }

    private boolean isRequiredConversationPinned(CodeSearchResult result) {
        return result != null && result.metadata() != null && Boolean.TRUE.equals(result.metadata().get("conversationRequired"));
    }

    private boolean previousAnswerExpansion(RagConversationContext conversationContext) {
        return conversationContext != null && conversationContext.previousAnswerExpansion();
    }

    private Set<UUID> requiredCodeChunkIds(RagConversationContext conversationContext) {
        if (conversationContext == null || conversationContext.requiredCodeChunkIds() == null) {
            return Set.of();
        }
        return new HashSet<>(conversationContext.requiredCodeChunkIds());
    }

    private String previousItemLabel(RagConversationContext conversationContext, UUID chunkId) {
        if (conversationContext == null || chunkId == null || conversationContext.previousAnswerItems() == null) {
            return "";
        }
        return conversationContext.previousAnswerItems().stream()
                .filter(item -> item.evidenceChunkIds().contains(chunkId))
                .map(PreviousAnswerItem::label)
                .filter(label -> !safe(label, "").isBlank())
                .findFirst()
                .orElse("");
    }

    private List<String> conversationDiagnostics(
            List<String> diagnostics,
            String originalQuestion,
            String effectiveQuestion,
            RagConversationContext conversationContext,
            CodeRetrieval retrieval
    ) {
        List<String> notes = new ArrayList<>(diagnostics == null ? List.of() : diagnostics);
        if (conversationContext == null || !conversationContext.contextual()) {
            return notes;
        }
        notes.add("대화 컨텍스트를 사용했습니다. 이전 코드 근거 "
                + (conversationContext.codeAnchors() == null ? 0 : conversationContext.codeAnchors().size())
                + "개 중 pinned 후보 " + retrieval.pinnedCandidateCount()
                + "개, 최종 답변 근거 " + retrieval.pinnedUsedCount() + "개를 반영했습니다.");
        if (!safe(originalQuestion, "").equals(safe(effectiveQuestion, ""))) {
            notes.add("후속 질문 검색용 독립 질문을 생성했습니다: " + trimInline(effectiveQuestion));
        }
        if (retrieval.pinnedCandidateCount() == 0 && conversationContext.codeAnchors() != null && !conversationContext.codeAnchors().isEmpty()) {
            notes.add("이전 코드 근거를 직접 조회하지 못해 일반 코드 검색으로 폴백했습니다.");
        }
        return notes;
    }

    private boolean isGraphExpanded(CodeSearchResult result) {
        return result != null && result.metadata() != null && Boolean.TRUE.equals(result.metadata().get("graphExpanded"));
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String nullable(String prefix, String value) {
        return value == null || value.isBlank() ? "" : prefix + value;
    }

    private long elapsedMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private void recordMetrics(
            String mode,
            CodeRetrieval retrieval,
            long retrievalMs,
            long contextMs,
            long llmMs,
            int contextChunkCount,
            int promptTokens,
            int outputTokens,
            boolean fallbackUsed,
            boolean llmUnavailable,
            long totalMs
    ) {
        if (ragMetricsService == null || retrieval == null) {
            return;
        }
        try {
            ragMetricsService.record(new AdminTuningMetricSample(
                    java.time.Instant.now(),
                    "code",
                    mode,
                    totalMs,
                    llmMs,
                    retrievalMs,
                    0,
                    0,
                    contextMs,
                    pipelineService.promptTokenBudgetBalanced(),
                    promptTokens,
                    outputTokens,
                    contextChunkCount,
                    retrieval.queryPlan().queries().size(),
                    fallbackUsed,
                    llmUnavailable,
                    ""
            ));
        } catch (RuntimeException ignored) {
            // Metrics must never block code answers.
        }
    }

    private record CodeRetrieval(
            List<CodeSearchResult> results,
            RagPipelineService.EvidenceAssessment assessment,
            RagPipelineService.QueryPlan queryPlan,
            int iteration,
            int candidateCount,
            int pinnedCandidateCount,
            int pinnedUsedCount
    ) {
    }

    private record CodeContextBundle(List<CodeSearchResult> results, String context) {
    }

    enum CodeQuestionMode {
        OVERVIEW("overview", "Synthesize search, definitions, references, and nearby chunks. Answer natural-language architecture questions with sections: summary, related files/methods, flow, evidence, and limitations."),
        LOCATE("locate", "Find where the requested feature or behavior is implemented. Prioritize files, classes, methods, and line ranges."),
        EXPLAIN_METHOD("method", "Explain the selected or named method. Cover inputs, side effects, called logic, and return/result behavior."),
        CALL_FLOW("flow", "Explain the call flow step by step using only cited code. Keep the sequence compact."),
        UI_EVENT("ui_event", "Explain WPF/WinForms UI event flow. Connect XAML controls/events to code-behind handlers when evidence exists."),
        IMPACT("impact", "Analyze likely impact areas. Separate confirmed evidence from uncertain areas and cite every claim.");

        private final String value;
        private final String instruction;

        CodeQuestionMode(String value, String instruction) {
            this.value = value;
            this.instruction = instruction;
        }

        static CodeQuestionMode from(String value) {
            if (value == null || value.isBlank()) {
                return LOCATE;
            }
            for (CodeQuestionMode mode : values()) {
                if (mode.value.equalsIgnoreCase(value.trim())) {
                    return mode;
                }
            }
            return LOCATE;
        }

        String value() {
            return value;
        }

        String instruction() {
            return instruction;
        }
    }
}
