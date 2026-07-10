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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class CodeRagService {
    private static final Logger log = LoggerFactory.getLogger(CodeRagService.class);
    private static final int OVERVIEW_CONTEXT_LIMIT = 12;
    private static final int DEFAULT_CONTEXT_LIMIT = 8;
    private static final int OVERVIEW_CONTEXT_CHARS = 620;
    private static final int DEFAULT_CONTEXT_CHARS = 1200;
    private static final int REASONING_CONTEXT_CHARS = 1000;
    private static final int FALLBACK_EXCERPT_CHARS = 180;
    private static final double CONVERSATION_PINNED_BOOST = 0.18;
    private static final Pattern LEADING_FILE_QUERY_PATTERN = Pattern.compile("(?i)\\b[\\w./\\\\-]+\\.(?:java|kt|cs|js|jsx|ts|tsx|py|go|rs|php|rb|scala|swift)\\s*:\\s*");
    private static final Pattern RESOURCE_IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{2,}(?:\\.[A-Za-z0-9_]+)?");
    private static final Set<String> COVERAGE_STOP_WORDS = Set.of(
            "the", "and", "for", "from", "with", "that", "this", "into", "onto", "where", "what", "when", "how",
            "does", "code", "source", "file", "files", "class", "method", "implementation", "implements", "logic",
            "flow", "pipeline", "service", "services", "request", "response", "result", "results", "objects",
            "locate", "find", "based", "current", "related", "using", "used", "user"
            , "backend", "frontend", "main", "java", "learnbot", "src"
    );

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
        ollamaClient.beginPrimaryRequest();
        try {
            return askPrioritized(repositoryId, selectedSpaceId, spaceIds, question, mode, limit, null, null);
        } finally {
            ollamaClient.finishPrimaryRequest();
        }
    }

    public CodeAskResponse askConversational(UUID repositoryId, UUID selectedSpaceId, List<UUID> spaceIds, String question, String mode, Integer limit, RagConversationContext conversationContext) {
        ollamaClient.beginPrimaryRequest();
        try {
            return askPrioritized(repositoryId, selectedSpaceId, spaceIds, question, mode, limit, conversationContext, null);
        } finally {
            ollamaClient.finishPrimaryRequest();
        }
    }

    public CodeAskResponse askStreaming(UUID repositoryId, UUID selectedSpaceId, List<UUID> spaceIds, String question, String mode, Integer limit, CodeAnswerStreamSink streamSink) {
        ollamaClient.beginPrimaryRequest();
        try {
            return askPrioritized(repositoryId, selectedSpaceId, spaceIds, question, mode, limit, null, streamSink);
        } finally {
            ollamaClient.finishPrimaryRequest();
        }
    }

    public CodeAskResponse askConversationalStreaming(UUID repositoryId, UUID selectedSpaceId, List<UUID> spaceIds, String question, String mode, Integer limit, RagConversationContext conversationContext, CodeAnswerStreamSink streamSink) {
        ollamaClient.beginPrimaryRequest();
        try {
            return askPrioritized(repositoryId, selectedSpaceId, spaceIds, question, mode, limit, conversationContext, streamSink);
        } finally {
            ollamaClient.finishPrimaryRequest();
        }
    }

    private CodeAskResponse askPrioritized(UUID repositoryId, UUID selectedSpaceId, List<UUID> spaceIds, String question, String mode, Integer limit, RagConversationContext conversationContext, CodeAnswerStreamSink streamSink) {
        long askStarted = System.nanoTime();
        String originalQuestion = safe(question, "");
        String effectiveQuestion = effectiveQuestion(originalQuestion, conversationContext);
        RagPipelineService.CodeRagRouteDecision routeDecision = routeCodeRagIntent(originalQuestion, mode, conversationContext);
        boolean commitFallbackUsed = false;
        if (routeDecision.route() == RagPipelineService.CodeRagRoute.COMMIT_DIFF && commitInsightService != null) {
            CodeAskResponse commitResponse = commitInsightService.answer(repositoryId, routedCommitQuestion(originalQuestion, routeDecision));
            if (!commitResponse.evidence().isEmpty()) {
                CodeAskResponse routed = withRouteDiagnostics(commitResponse, routeDecision, false);
                if (streamSink != null) {
                    streamSink.onReplace(routed.answer(), "commit_insight");
                    streamSink.onEvidence(routed.evidence());
                }
                return routed;
            }
            commitFallbackUsed = true;
        }
        effectiveQuestion = routedQuestion(effectiveQuestion, routeDecision);
        String effectiveMode = routedMode(mode, routeDecision);
        CodeQuestionMode questionMode = classifyCodeQuestion(effectiveQuestion, effectiveMode, conversationContext);
        int safeLimit = safeLimit(questionMode, limit);
        if (streamSink != null) {
            streamSink.onStatus("retrieval_started", "코드 근거를 검색하고 있습니다.");
        }
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
                When the user asks why code exists or whether an implementation makes sense, separate direct code evidence from inferred design intent.
                When a context item has graphEvidence=inferred, explain it as relationship-based graph evidence, not as a direct code statement.
                When context items include evidenceRole, separate retrieval/search expansion, graph traversal/storage, evidence ranking, and answer context/rendering responsibilities.
                When context items include evidencePhase, keep INDEXING, SEARCH_EXPANSION, RANKING, and ANSWER_GENERATION in that chronological order unless direct code evidence proves otherwise.
                Do not describe SEARCH_EXPANSION evidence as graph/index storage logic unless cited INDEXING or STORAGE evidence directly shows persistence.
                Do not describe RANKING evidence as retrieval or graph expansion logic unless cited code directly performs that work.
                Do not describe ANSWER_GENERATION evidence as indexing, storage, retrieval, or ranking logic unless cited code directly calls that phase.
                Treat citationKind=direct_code as direct source evidence and citationKind containing graph_relationship as relationship evidence; do not cite relationship evidence as if it were a direct method call.
                Use evidenceResponsibility to distinguish implementation_flow evidence from helper_check or data_structure evidence.
                When context items include fallbackScope, keep routing fallback, graph/index analysis fallback, search expansion fallback, and answer-generation fallback as separate mechanisms.
                fallbackScope=GRAPH_ANALYSIS is indexing or graph-build diagnostic evidence; it does not by itself prove answer generation fallback was used.
                fallbackScope=SEARCH_EXPANSION is retrieval or graph traversal fallback evidence; it does not by itself prove indexing analysis failed.
                fallbackScope=ANSWER_GENERATION is final answer generation or answer repair fallback evidence; only connect it to earlier phases when direct cited code shows that call or condition.
                Treat llmSupportedClaims as claims directly supported by that evidence.
                Treat llmNotSupportedClaims as explicit boundaries; do not state them as facts unless another selected citation directly supports them.
                Build the answer from selected evidence and these claim boundaries. Do not restore unselected candidate interpretations.
                When the question names a language or framework, prefer matching analysisDiagnosticLanguage, analysisDiagnosticStage, and analysisDiagnosticAnalyzer; mention non-matching diagnostics only as separate cross-language evidence.
                Treat rank, evidenceScore, and phase-level executionOrder as relevance/ordering hints, not as method call order.
                Treat guard clauses and early returns as failure handling only when the cited code or diagnostic metadata says failed, partial, skipped, unavailable, or exception.
                Do not describe a helper/metadata-check method as the component that performs retrieval or ranking unless the code evidence directly shows that responsibility.
                When context items include excerptKind/contentComplete/omittedByBudget, distinguish prompt excerpt compression from missing source code.
                If contentComplete=false or omittedByBudget=true, say "provided excerpt" when discussing limits; do not claim the real implementation is absent unless direct cited evidence proves absence.
                Prefer FULL_CHUNK direct evidence for method-level flow; use FOCUSED_EXCERPT/TRUNCATED_CHUNK as partial prompt evidence and avoid conclusions that require omitted lines.
                For code explanations, structure the answer as follows when applicable:
                1. Summary
                2. Detailed explanation
                3. Execution flow
                4. Related files, classes, and methods
                5. Important implementation details
                
                Use markdown headings.
                Start with the conclusion and core flow, then add details only when the evidence supports them.
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
                answerContextResults(questionMode, effectiveQuestion, results, retrieval.followUpPlan()),
                streamSink != null
        );
        List<CodeSearchResult> answerResults = contextBundle.results();
        String userPrompt = promptPrefix + "\n\nSource-code context:\n" + contextBundle.context();
        int contextBudgetDropped = contextBundle.droppedCount();
        long contextMs = elapsedMs(contextStarted);
        if (streamSink != null) {
            streamSink.onStatus("evidence_ready", "답변에 사용할 코드 근거를 정리했습니다.");
            streamSink.onEvidence(buildEvidence(answerResults));
        }
        if (shouldUseEvidenceFallback(retrieval)) {
            String answer = fallbackAnswer(questionMode, originalQuestion, answerResults);
            recordMetrics(questionMode.value(), retrieval, retrievalMs, contextMs, 0, answerResults.size(), 0, 0, true, false, elapsedMs(askStarted));
            return new CodeAskResponse(
                    questionMode.value(),
                    answer,
                    buildEvidence(answerResults),
                    confidence(answerResults, retrieval.assessment()),
                    conversationDiagnostics(
                            routeDiagnostics(diagnostics(questionMode, results, answerResults, answer, null, false, false, false, false, false, AnswerQualityTrace.empty(), retrieval, contextBudgetDropped), routeDecision, commitFallbackUsed),
                            originalQuestion,
                            effectiveQuestion,
                            conversationContext,
                            retrieval
                    )
            );
        }
        String answer;
        boolean llmUnavailable = false;
        boolean answerRewritten = false;
        boolean answerRetried = false;
        boolean answerContinued = false;
        boolean answerKeptAfterStreamValidation = false;
        String answerDoneReason = null;
        AnswerQualityTrace answerQualityTrace = AnswerQualityTrace.empty();
        OllamaClient.ChatResult finalChatResult = null;
        long llmMs = 0;
        StringBuilder streamedAnswer = new StringBuilder();
        try {
            long llmStarted = System.nanoTime();
            int maxOutputTokens = maxOutputTokens(questionMode);
            if (streamSink != null) {
                streamSink.onStatus("llm_started", "코드 답변 생성을 시작했습니다.");
            }
            OllamaClient.ChatResult chatResult = streamSink == null
                    ? chatWithLimit(systemPrompt, userPrompt, maxOutputTokens)
                    : stream(systemPrompt, userPrompt, streamSink, streamedAnswer, maxOutputTokens);
            llmMs += elapsedMs(llmStarted);
            finalChatResult = chatResult;
            answer = chatResult.content();
            answerDoneReason = chatResult.doneReason();
            if (isLengthStop(answerDoneReason)) {
                long continuationStarted = System.nanoTime();
                LengthContinuation continuation = streamSink == null
                        ? continueLengthLimitedAnswer(systemPrompt, userPrompt, answer, questionMode, answerResults.size())
                        : continueLengthLimitedAnswerStreaming(systemPrompt, userPrompt, streamedAnswer, questionMode, answerResults.size(), streamSink);
                llmMs += elapsedMs(continuationStarted);
                if (continuation.continued()) {
                    answer = continuation.answer();
                    answerDoneReason = continuation.doneReason();
                    finalChatResult = continuation.chatResult();
                    answerContinued = true;
                }
            }
            String qualityReason = qualityFailureReason(answer, answerResults.size(), answerDoneReason);
            answerQualityTrace = AnswerQualityTrace.fromInitial(answer, answerDoneReason, answerResults, qualityReason);
            if (qualityReason != null && pipelineService.maxIterations() > 1) {
                String retryPrompt = userPrompt
                        + "\n\nPrevious answer failed quality check: " + qualityReason + "."
                        + "\nRewrite the answer using only the cited code context. Cite every factual claim with [n].";
                long retryStarted = System.nanoTime();
                OllamaClient.ChatResult retryResult = chatWithLimit(systemPrompt + "\nBe concise and citation-strict.", retryPrompt, repairOutputTokens(maxOutputTokens));
                llmMs += elapsedMs(retryStarted);
                String retryAnswer = retryResult == null ? "" : retryResult.content();
                String retryDoneReason = retryResult == null ? null : retryResult.doneReason();
                String retryQualityReason = qualityFailureReason(retryAnswer, answerResults.size(), retryDoneReason);
                answerQualityTrace = answerQualityTrace.withRetry(retryAnswer, retryDoneReason, answerResults, retryQualityReason);
                if (retryQualityReason == null) {
                    answer = retryAnswer;
                    answerDoneReason = retryDoneReason;
                    finalChatResult = retryResult;
                    answerRetried = true;
                    if (streamSink != null) {
                        streamSink.onReplace(answer, "answer_repair");
                    }
                }
            }
        } catch (RuntimeException ex) {
            if (streamSink != null && streamedAnswer.length() > 0) {
                throw ex;
            }
            answer = fallbackAnswer(questionMode, originalQuestion, answerResults);
            answerDoneReason = null;
            llmUnavailable = true;
            answerQualityTrace = AnswerQualityTrace.unavailable(ex);
            if (streamSink != null) {
                streamSink.onReplace(answer, "llm_unavailable_fallback");
            }
        }
        String finalQualityReason = qualityFailureReason(answer, answerResults.size(), answerDoneReason);
        if (finalQualityReason != null) {
            answerRewritten = true;
            answerQualityTrace = answerQualityTrace.withFinalFailure(answer, answerDoneReason, answerResults, finalQualityReason);
            answer = questionMode == CodeQuestionMode.OVERVIEW
                    ? overviewFallbackAnswer(answerResults)
                    : fallbackAnswer(questionMode, originalQuestion, answerResults);
            if (streamSink != null) {
                streamSink.onReplace(answer, "quality_fallback");
            }
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
                        routeDiagnostics(diagnostics(questionMode, results, answerResults, answer, answerDoneReason, llmUnavailable, answerRewritten, answerRetried, answerContinued, answerKeptAfterStreamValidation, answerQualityTrace, retrieval, contextBudgetDropped), routeDecision, commitFallbackUsed),
                        originalQuestion,
                        effectiveQuestion,
                        conversationContext,
                        retrieval
                )
        );
    }

    private RagPipelineService.CodeRagRouteDecision routeCodeRagIntent(
            String originalQuestion,
            String requestedMode,
            RagConversationContext conversationContext
    ) {
        boolean releasedPrimarySlot = ollamaClient.hasPrimaryRequestInFlight();
        if (releasedPrimarySlot) {
            ollamaClient.finishPrimaryRequest();
        }
        RagPipelineService.CodeRagRouteDecision decision;
        try {
            decision = pipelineService.routeCodeRagIntent(
                    originalQuestion,
                    requestedMode,
                    conversationContext,
                    commitInsightService != null
            );
        } finally {
            if (releasedPrimarySlot) {
                ollamaClient.beginPrimaryRequest();
            }
        }
        return decision;
    }

    private String routedCommitQuestion(String originalQuestion, RagPipelineService.CodeRagRouteDecision routeDecision) {
        String commitRef = safe(routeDecision.commitRef(), "");
        return commitRef.isBlank() ? originalQuestion : originalQuestion + "\nCommit reference: " + commitRef;
    }

    private String routedQuestion(String fallback, RagPipelineService.CodeRagRouteDecision routeDecision) {
        if (routeDecision == null) {
            return fallback;
        }
        String query = routeDecision.queries().stream()
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
        if (!query.isBlank()) {
            return fallback + "\n\nRetrieval hints from route decision:\n" + query;
        }
        String symbol = safe(routeDecision.targetSymbol(), "");
        String file = safe(routeDecision.targetFile(), "");
        String combined = (file + " " + symbol).trim();
        return combined.isBlank() ? fallback : fallback + "\n\nRetrieval hints from route decision:\n" + combined;
    }

    private String routedMode(String requestedMode, RagPipelineService.CodeRagRouteDecision routeDecision) {
        if (routeDecision == null) {
            return requestedMode;
        }
        String mode = safe(routeDecision.mode(), "");
        if (!mode.isBlank() && !"auto".equalsIgnoreCase(mode)) {
            return mode;
        }
        return switch (routeDecision.route()) {
            case CODE_OVERVIEW_FLOW -> "flow";
            case LOCATE_SYMBOL -> "locate";
            case EXPLAIN_METHOD -> "method";
            case IMPACT_ANALYSIS -> "impact";
            case EXPAND_PREVIOUS_ANSWER, ANSWER_FROM_PRIOR -> safe(requestedMode, "").isBlank() ? "overview" : requestedMode;
            default -> requestedMode;
        };
    }

    private CodeAskResponse withRouteDiagnostics(
            CodeAskResponse response,
            RagPipelineService.CodeRagRouteDecision routeDecision,
            boolean commitFallbackUsed
    ) {
        return new CodeAskResponse(
                response.mode(),
                response.answer(),
                response.evidence(),
                response.confidence(),
                routeDiagnostics(response.diagnostics(), routeDecision, commitFallbackUsed),
                response.conversationId(),
                response.turnId(),
                response.rewrittenQuestion()
        );
    }

    private List<String> routeDiagnostics(List<String> diagnostics, RagPipelineService.CodeRagRouteDecision routeDecision) {
        return routeDiagnostics(diagnostics, routeDecision, false);
    }

    private List<String> routeDiagnostics(List<String> diagnostics, RagPipelineService.CodeRagRouteDecision routeDecision, boolean commitFallbackUsed) {
        List<String> notes = new ArrayList<>(diagnostics == null ? List.of() : diagnostics);
        if (routeDecision == null) {
            return notes;
        }
        notes.add("Agentic RAG route: route=" + routeDecision.route()
                + ", confidence=" + routeDecision.confidence()
                + ", mode=" + safe(routeDecision.mode(), "")
                + ", queries=" + routeDecision.queries().size()
                + ", attempted=" + routeDecision.attempted()
                + ", fallback=" + routeDecision.fallback()
                + ", commitFallback=" + commitFallbackUsed
                + ", reason=" + safe(routeDecision.reason(), "") + ".");
        return notes;
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
        boolean autoMode = mode == null || mode.isBlank() || "auto".equalsIgnoreCase(mode.trim());
        CodeQuestionMode requested = CodeQuestionMode.from(mode);
        if (!properties.getRag().getOverview().isEnabled()) {
            return autoMode ? CodeQuestionMode.OVERVIEW : requested;
        }
        if (autoMode && previousAnswerExpansion(conversationContext)) {
            return CodeQuestionMode.OVERVIEW;
        }
        boolean explicitMode = !autoMode;
        if (explicitMode && requested != CodeQuestionMode.OVERVIEW) {
            return requested;
        }
        String normalized = normalizeCodeText(question);
        if (isFlowIntent(normalized)) {
            return CodeQuestionMode.CALL_FLOW;
        }
        if (isImpactIntent(normalized)) {
            return CodeQuestionMode.IMPACT;
        }
        if (isReasoningIntent(normalized)) {
            return CodeQuestionMode.REASONING;
        }
        if (isLocateIntent(normalized)) {
            return CodeQuestionMode.LOCATE;
        }
        if (isOverviewIntent(normalized)) {
            return CodeQuestionMode.OVERVIEW;
        }
        if (autoMode && conversationContext != null && conversationContext.contextual()) {
            CodeQuestionMode previousMode = previousTurnMode(conversationContext);
            if (previousMode != null) {
                return previousMode;
            }
            return conversationAnchorFallbackMode(conversationContext);
        }
        return autoMode ? CodeQuestionMode.OVERVIEW : requested;
    }

    private boolean isFlowIntent(String normalized) {
        return containsAny(normalized, "flow", "workflow", "sequence", "request flow", "call flow", "흐름", "과정", "절차", "순서", "호출");
    }

    private boolean isImpactIntent(String normalized) {
        return containsAny(normalized, "impact", "effect", "affected", "test", "fix", "bug", "problem", "risk", "regression", "영향", "변경 영향", "테스트", "수정", "문제", "버그", "리스크", "회귀");
    }

    private boolean isReasoningIntent(String normalized) {
        return containsAny(normalized,
                "why", "reason", "rationale", "intent", "intention", "design intent", "design reason", "tradeoff", "appropriate", "makes sense",
                "왜", "이유", "의도", "설계 의도", "설계상", "구조상", "왜 이렇게", "이렇게 되어", "괜찮", "맞아", "적절", "애매", "타당", "판단");
    }

    private boolean isLocateIntent(String normalized) {
        return containsAny(normalized, "locate", "where", "file", "line", "path", "위치", "어디", "파일", "라인", "경로");
    }

    private boolean isOverviewIntent(String normalized) {
        return containsAny(normalized, "architecture", "structure", "overview", "module", "component", "summary", "아키텍처", "구조", "개요", "구성", "전체", "요약");
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
                || mode == CodeQuestionMode.UI_EVENT
                || mode == CodeQuestionMode.REASONING;
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
        int searchLimit = pipelineService.codeSearchLimit(questionMode == CodeQuestionMode.OVERVIEW ? limit + 6 : limit + 4);
        CodeQueryPlan deterministicPlan = codeQueryPlan(question, questionMode);
        collectEvidenceForQuery(repositoryId, selectedSpaceId, spaceIds, question, questionMode, searchLimit, merged);
        RagPipelineService.CodeEvidenceSearchPlan searchPlan = pipelineService.planCodeEvidenceSearch(
                question,
                questionMode.value(),
                repositoryMapContext(repositoryId, selectedSpaceId, spaceIds, questionMode),
                4
        );
        int plannedCandidateCount = collectSearchPlanEvidence(repositoryId, selectedSpaceId, spaceIds, questionMode, searchLimit, searchPlan, merged);
        boolean useServerFallback = !searchPlan.usable() || searchPlan.confidence() < 0.45 || merged.size() < Math.max(10, limit);
        if (useServerFallback) {
            for (String query : literalEvidenceQueries(question)) {
                collectEvidenceForQuery(repositoryId, selectedSpaceId, spaceIds, query, questionMode, searchLimit, merged);
            }
            for (String query : conversationAnchorQueries(question, conversationContext)) {
                collectEvidenceForQuery(repositoryId, selectedSpaceId, spaceIds, query, questionMode, searchLimit, merged);
            }
            for (String query : deterministicPlan.auxiliaryQueries()) {
                collectEvidenceForQuery(repositoryId, selectedSpaceId, spaceIds, query, questionMode, searchLimit, merged);
            }
            if (questionMode == CodeQuestionMode.OVERVIEW || questionMode == CodeQuestionMode.CALL_FLOW) {
                for (String query : codeOverviewQueries(question, questionMode)) {
                    collectEvidenceForQuery(repositoryId, selectedSpaceId, spaceIds, query, questionMode, searchLimit, merged);
                }
            }
        }
        log.info("Code RAG search plan attempted={} usable={} confidence={} queries={} candidatesAdded={} serverFallback={} reason={} question={}",
                searchPlan.attempted(), searchPlan.usable(), searchPlan.confidence(), searchPlan.queries(),
                plannedCandidateCount, useServerFallback, safe(searchPlan.reason(), ""), abbreviate(question, 180));
        List<CodeSearchResult> results = rankedCodeEvidence(question, questionMode, merged, limit, null);
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
                false,
                "initial search"
        );
        int iteration = 1;
        int followUpCandidateCount = 0;
        RagPipelineService.CodeEvidenceFollowUpPlan followUpPlan = pipelineService.planCodeEvidenceFollowUp(
                question,
                questionMode.value(),
                results,
                2,
                searchPlan.checklist()
        );
        int followUpQueriesUsed = 0;
        boolean followUpStoppedEarly = false;

        if (!followUpPlan.enough() && !followUpPlan.operations().isEmpty() && pipelineService.maxIterations() > 1) {
            for (RagPipelineService.CodeSearchOperation operation : followUpPlan.operations()) {
                followUpCandidateCount += executeFollowUpSearchOperation(
                        repositoryId, selectedSpaceId, spaceIds, operation, questionMode, searchLimit, merged);
                followUpQueriesUsed++;
            }
            iteration = 2;
            results = rankedCodeEvidence(question, questionMode, merged, limit, followUpPlan);
            assessment = pipelineService.assessCode(
                    question,
                    results,
                    minCodeEvidence(questionMode),
                    iteration
            );
            queryPlan = new RagPipelineService.QueryPlan(
                    RagPipelineService.Domain.CODE,
                    java.util.stream.Stream.concat(java.util.stream.Stream.of(question), followUpPlan.followUpQueries().stream()).distinct().toList(),
                    true,
                    true,
                    false,
                    "llm evidence follow-up: " + safe(followUpPlan.reason(), "")
            );
        }
        List<String> completenessQueries = followUpPlan.attempted()
                ? List.of()
                : contextCompletenessFollowUpQueries(question, questionMode, results);
        if (!completenessQueries.isEmpty() && pipelineService.maxIterations() > 1) {
            for (String query : completenessQueries) {
                followUpCandidateCount += collectFollowUpEvidenceForQuery(
                        repositoryId,
                        selectedSpaceId,
                        spaceIds,
                        question,
                        query,
                        query,
                        "",
                        questionMode,
                        searchLimit,
                        merged
                );
                followUpQueriesUsed++;
            }
            iteration = Math.max(iteration, 2);
            results = rankedCodeEvidence(question, questionMode, merged, limit, followUpPlan);
            assessment = pipelineService.assessCode(
                    question,
                    results,
                    minCodeEvidence(questionMode),
                    iteration
            );
            queryPlan = new RagPipelineService.QueryPlan(
                    RagPipelineService.Domain.CODE,
                    java.util.stream.Stream.concat(queryPlan.queries().stream(), completenessQueries.stream()).distinct().toList(),
                    true,
                    queryPlan.rewriteUsed(),
                    false,
                    appendReason(queryPlan.reason(), "context completeness follow-up")
            );
        }

        int graphExpansionAdded = expandGraphEvidenceOnce(repositoryId, question, questionMode, limit, merged);
        if (graphExpansionAdded > 0) {
            results = rankedCodeEvidence(question, questionMode, merged, limit, followUpPlan);
            assessment = pipelineService.assessCode(
                    question,
                    results,
                    minCodeEvidence(questionMode),
                    iteration
            );
        }

        int pinnedUsedCount = (int) results.stream().filter(this::isConversationPinned).count();
        long followUpSelectedCount = results.stream().filter(this::isLlmFollowUpEvidence).count();
        log.info("Code RAG evidence follow-up attempted={} enough={} queriesUsed={} candidatesAdded={} selected={} earlyStop={} missingAreas={} groups={} reason={} question={}",
                followUpPlan.attempted(),
                followUpPlan.enough(),
                followUpQueriesUsed,
                followUpCandidateCount,
                followUpSelectedCount,
                followUpStoppedEarly,
                followUpPlan.missingAreas(),
                followUpPlan.requiredEvidenceGroups(),
                safe(followUpPlan.reason(), ""),
                abbreviate(question, 180));
        if (followUpPlan.attempted()) {
            log.info("Code RAG evidence follow-up detail queryAreas={} groups={} queries={} selectedFiles={}",
                    followUpPlan.queryAreas(),
                    followUpPlan.requiredEvidenceGroups(),
                    followUpPlan.followUpQueries(),
                    selectedPathSummary(results));
        }
        return new CodeRetrieval(results, assessment, queryPlan, deterministicPlan, followUpPlan, followUpQueriesUsed, followUpCandidateCount, iteration, merged.size(), pinnedCandidateCount, pinnedUsedCount);
    }

    private int collectSearchPlanEvidence(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            CodeQuestionMode questionMode,
            int searchLimit,
            RagPipelineService.CodeEvidenceSearchPlan searchPlan,
            Map<UUID, CodeSearchResult> merged
    ) {
        if (searchPlan == null || !searchPlan.usable()
                || (searchPlanQueries(searchPlan).isEmpty() && searchPlan.checklist().isEmpty())) {
            return 0;
        }
        int before = merged.size();
        int perQueryLimit = Math.max(6, Math.min(searchLimit, 18));
        LinkedHashSet<String> groupedQueries = new LinkedHashSet<>();
        for (RagPipelineService.CodeEvidenceChecklistItem item : searchPlan.checklist()) {
            groupedQueries.addAll(collectSearchPlanChecklistEvidence(
                    repositoryId,
                    selectedSpaceId,
                    spaceIds,
                    questionMode,
                    perQueryLimit,
                    item,
                    merged
            ));
        }
        for (String query : searchPlanQueries(searchPlan)) {
            if (groupedQueries.contains(query)) {
                continue;
            }
            collectCheapEvidenceForQuery(repositoryId, selectedSpaceId, spaceIds, query, questionMode, perQueryLimit, merged);
        }
        return Math.max(0, merged.size() - before);
    }

    private Set<String> collectSearchPlanChecklistEvidence(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            CodeQuestionMode questionMode,
            int perQueryLimit,
            RagPipelineService.CodeEvidenceChecklistItem item,
            Map<UUID, CodeSearchResult> merged
    ) {
        LinkedHashSet<String> usedQueries = new LinkedHashSet<>();
        if (item == null) {
            return usedQueries;
        }
        String group = normalizeEvidenceGroupValue(item.evidenceGroup());
        if (group.isBlank() || "unknown".equals(group)) {
            return usedQueries;
        }
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        addAllNonBlank(queries, item.queries());
        if (queries.isEmpty() && notBlank(item.goal())) {
            queries.add(item.goal());
        }
        if (queries.isEmpty()) {
            return usedQueries;
        }
        int beforeGroup = merged.size();
        for (String query : queries.stream().limit(3).toList()) {
            usedQueries.add(query);
            List<CodeSearchResult> results = searchService.cheapSearch(repositoryId, query, perQueryLimit, spaceIds, selectedSpaceId);
            if (results == null) {
                results = collectEvidence(repositoryId, selectedSpaceId, spaceIds, query, questionMode, perQueryLimit);
            }
            int addedForQuery = 0;
            for (CodeSearchResult result : results) {
                if (addedForQuery >= 4) {
                    break;
                }
                merge(merged, markLlmSearchPlanGroupEvidence(result, item, query));
                addedForQuery++;
            }
        }
        if (merged.size() == beforeGroup && notBlank(item.goal())) {
            String fallbackQuery = item.goal() + " " + String.join(" ", evidenceGroupQueryTerms(group));
            usedQueries.add(fallbackQuery);
            List<CodeSearchResult> results = collectEvidence(repositoryId, selectedSpaceId, spaceIds, fallbackQuery, questionMode, Math.min(perQueryLimit, 10));
            int added = 0;
            for (CodeSearchResult result : results) {
                if (added >= 3) {
                    break;
                }
                merge(merged, markLlmSearchPlanGroupEvidence(result, item, fallbackQuery));
                added++;
            }
        }
        return usedQueries;
    }

    private List<String> searchPlanQueries(RagPipelineService.CodeEvidenceSearchPlan searchPlan) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        if (searchPlan != null) {
            addAllNonBlank(queries, searchPlan.queries());
            for (RagPipelineService.CodeEvidenceChecklistItem item : searchPlan.checklist()) {
                addAllNonBlank(queries, item.queries());
            }
        }
        return queries.stream().limit(8).toList();
    }

    private void addAllNonBlank(Set<String> output, List<String> values) {
        if (output == null || values == null) {
            return;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                output.add(value.trim());
            }
        }
    }

    private String repositoryMapContext(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            CodeQuestionMode questionMode
    ) {
        try {
            List<CodeSearchResult> context = collectEvidence(
                    repositoryId,
                    selectedSpaceId,
                    spaceIds,
                    CodeProjectContextBuilder.CONTEXT_FILE_PATH + " project_structure repository_summary directory_summary file_summary",
                    questionMode,
                    8
            );
            return context.stream()
                    .filter(result -> result != null && result.filePath() != null
                            && result.filePath().equals(CodeProjectContextBuilder.CONTEXT_FILE_PATH))
                    .limit(8)
                    .map(result -> safe(result.chunkType(), "") + " " + safe(result.symbolName(), "") + "\n"
                            + truncate(result.content(), 900))
                    .collect(Collectors.joining("\n\n"));
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private boolean shouldUseEvidenceFallback(CodeRetrieval retrieval) {
        if (retrieval == null || retrieval.assessment() == null || retrieval.assessment().sufficient()) {
            return false;
        }
        RagPipelineService.CodeEvidenceFollowUpPlan plan = retrieval.followUpPlan();
        return plan != null && plan.attempted() && !plan.enough()
                && (retrieval.followUpQueriesUsed() == 0 || retrieval.iteration() > 1);
    }

    private List<String> contextCompletenessFollowUpQueries(String question, CodeQuestionMode questionMode, List<CodeSearchResult> results) {
        if (!isImplementationFlowQuestion(question, questionMode) || results == null || results.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        for (CodeSearchResult result : results.stream().limit(4).toList()) {
            if (!isDirectCodeEvidence(result) || !isLikelyLongChunk(result)) {
                continue;
            }
            String identity = compactIdentity(result);
            if (!identity.isBlank()) {
                queries.add(identity + " implementation flow full method");
            }
            String symbol = firstNonBlank(result.methodName(), result.symbolName(), result.className(), result.controlName(), result.eventName());
            if (!symbol.isBlank()) {
                queries.add(symbol + " calls references implementation");
            }
            if (queries.size() >= 3) {
                break;
            }
        }
        return queries.stream().limit(3).toList();
    }

    private boolean isImplementationFlowQuestion(String question, CodeQuestionMode questionMode) {
        if (questionMode == CodeQuestionMode.CALL_FLOW || questionMode == CodeQuestionMode.REASONING
                || questionMode == CodeQuestionMode.IMPACT || questionMode == CodeQuestionMode.UI_EVENT) {
            return true;
        }
        String normalized = normalizeCodeText(question);
        return containsRoleTerms(normalized,
                "flow", "pipeline", "process", "through", "calls", "call", "ranking", "expansion",
                "generation", "handler", "binding", "transaction", "fallback", "complete", "entire");
    }

    private boolean isLikelyLongChunk(CodeSearchResult result) {
        String content = safe(result == null ? "" : result.content(), "");
        int lineSpan = result == null ? 0 : Math.max(0, result.lineEnd() - result.lineStart());
        return content.length() > DEFAULT_CONTEXT_CHARS || lineSpan > 35;
    }

    private String compactIdentity(CodeSearchResult result) {
        if (result == null) {
            return "";
        }
        return String.join(" ",
                safe(result.filePath(), ""),
                safe(firstNonBlank(result.methodName(), result.symbolName(), result.className(), result.controlName(), result.eventName()), "")
        ).trim();
    }

    private String appendReason(String existing, String addition) {
        String left = safe(existing, "").trim();
        String right = safe(addition, "").trim();
        if (left.isBlank()) {
            return right;
        }
        if (right.isBlank()) {
            return left;
        }
        return left + "; " + right;
    }

    private String firstNonBlank(String... values) {
        for (String value : values == null ? new String[0] : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private List<String> literalEvidenceQueries(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        Matcher quoted = Pattern.compile("[\"'`](.{3,120}?)[\"'`]").matcher(question);
        while (quoted.find() && queries.size() < 4) {
            String value = quoted.group(1).trim();
            if (!value.isBlank()) {
                queries.add(value);
            }
        }
        Matcher indexed = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\[[0-9]+]\\b").matcher(question);
        while (indexed.find() && queries.size() < 4) {
            queries.add(indexed.group());
        }
        Matcher codeToken = Pattern.compile("\\b[A-Z][A-Z0-9_]{2,}\\b").matcher(question);
        while (codeToken.find() && queries.size() < 4) {
            queries.add(codeToken.group());
        }
        return queries.stream().limit(4).toList();
    }

    private CodeQueryPlan codeQueryPlan(String question, CodeQuestionMode questionMode) {
        String base = safe(question, "").trim();
        if (base.isBlank()) {
            return new CodeQueryPlan("EMPTY", List.of(base), true);
        }
        List<String> queries = new ArrayList<>();
        queries.add(base);
        String normalized = normalizeCodeText(base);
        boolean patchIntent = containsAny(normalized,
                "fix", "change", "modify", "implement", "patch", "bug", "regression",
                "수정", "고쳐", "변경", "구현", "패치", "버그", "회귀");
        boolean impactIntent = questionMode == CodeQuestionMode.IMPACT || containsAny(normalized,
                "impact", "affected", "risk", "side effect", "test", "regression",
                "영향", "리스크", "테스트", "회귀");
        boolean flowIntent = questionMode == CodeQuestionMode.CALL_FLOW || isFlowIntent(normalized);
        if (patchIntent) {
            queries.add(base + " target files methods validation tests");
            queries.add(base + " bug cause fix location related callers");
        } else if (impactIntent) {
            queries.add(base + " affected callers dependencies tests side effects");
            queries.add(base + " related implementations usages graph impact");
        } else if (questionMode == CodeQuestionMode.REASONING) {
            queries.add(base + " design intent rationale dependencies callers");
        } else if (flowIntent) {
            queries.add(base + " entrypoint controller service repository call sequence");
        }
        List<String> planned = queries.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(3)
                .toList();
        String intent = patchIntent ? "PATCH_INTENT" : questionMode.name();
        return new CodeQueryPlan(intent, planned, planned.size() <= 1);
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
                ? "Previous-answer expansion mode: keep the previous answer item structure, expand each item only from current source-code context, cite every item, and mark insufficient items as \"추가 근거 부족\".\n"
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
                        + (item.evidenceChunkIds().isEmpty() ? " / 추가 근거 부족" : " / requiredChunks=" + item.evidenceChunkIds()))
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

    private void collectCheapEvidenceForQuery(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            String query,
            CodeQuestionMode questionMode,
            int limit,
            Map<UUID, CodeSearchResult> merged
    ) {
        List<CodeSearchResult> results = searchService.cheapSearch(repositoryId, query, limit, spaceIds, selectedSpaceId);
        if (results == null) {
            results = collectEvidence(repositoryId, selectedSpaceId, spaceIds, query, questionMode, limit);
        }
        for (CodeSearchResult result : results) {
            merge(merged, result);
        }
    }

    private int collectFollowUpEvidenceForQuery(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            String originalQuestion,
            String query,
            String queryArea,
            String evidenceGroup,
            CodeQuestionMode questionMode,
            int limit,
            Map<UUID, CodeSearchResult> merged
    ) {
        int before = merged.size();
        for (String groundedQuery : groundedFollowUpQueries(originalQuestion, query, queryArea, evidenceGroup)) {
            List<CodeSearchResult> results = searchService.cheapSearch(repositoryId, groundedQuery, limit, spaceIds, selectedSpaceId);
            if (results == null) {
                results = collectEvidence(repositoryId, selectedSpaceId, spaceIds, groundedQuery, questionMode, limit);
            }
            for (CodeSearchResult result : results) {
                merge(merged, markLlmFollowUpEvidence(result, groundedQuery));
            }
        }
        if (merged.size() == before) {
            String normalizedArea = normalizeQuestionText(splitIdentifierTerms(queryArea + " " + query + " " + evidenceGroup + " " + originalQuestion));
            for (CodeSearchResult result : llmAreaFollowUpEvidence(repositoryId, selectedSpaceId, spaceIds, normalizedArea, limit)) {
                merge(merged, markLlmFollowUpEvidence(result, "llm coverage area focused follow-up"));
            }
        }
        return Math.max(0, merged.size() - before);
    }

    private int executeFollowUpSearchOperation(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            RagPipelineService.CodeSearchOperation operation,
            CodeQuestionMode questionMode,
            int limit,
            Map<UUID, CodeSearchResult> merged
    ) {
        String query = safe(operation.query(), "").trim();
        if (query.isBlank()) {
            return 0;
        }
        int before = merged.size();
        try {
            List<CodeSearchResult> results;
            if ("reference_search".equals(operation.type())) {
                var references = referenceService.findReferences(repositoryId, selectedSpaceId, spaceIds, query, limit);
                results = java.util.stream.Stream.concat(references.definitions().stream(), references.references().stream()).toList();
            } else if ("hybrid_search".equals(operation.type())) {
                results = searchService.searchWithoutGraph(
                        repositoryId, query, limit, spaceIds, selectedSpaceId, graphSearchIntent(questionMode));
            } else {
                results = searchService.cheapSearch(repositoryId, query, limit, spaceIds, selectedSpaceId);
            }
            for (CodeSearchResult result : results == null ? List.<CodeSearchResult>of() : results) {
                merge(merged, markLlmFollowUpEvidence(result, operation.type() + ": " + query));
            }
            int added = Math.max(0, merged.size() - before);
            log.info("Code RAG follow-up operation type={} query={} status=completed candidatesAdded={}",
                    operation.type(), abbreviate(query, 160), added);
            return added;
        } catch (RuntimeException ex) {
            log.info("Code RAG follow-up operation type={} query={} status=failed reason={}",
                    operation.type(), abbreviate(query, 160), ex.getClass().getSimpleName());
            return 0;
        }
    }

    private int expandGraphEvidenceOnce(
            UUID repositoryId,
            String question,
            CodeQuestionMode questionMode,
            int limit,
            Map<UUID, CodeSearchResult> merged
    ) {
        if (merged == null || merged.isEmpty()) {
            return 0;
        }
        int before = merged.size();
        List<CodeSearchResult> seeds = evidenceRanker.rank(question, questionMode, List.copyOf(merged.values()))
                .stream()
                .limit(Math.max(4, Math.min(12, limit)))
                .toList();
        List<CodeSearchResult> expanded = searchService.expandGraph(
                repositoryId,
                question,
                seeds,
                Math.max(limit, pipelineService.codeSearchLimit(limit)),
                graphSearchIntent(questionMode)
        );
        for (CodeSearchResult result : expanded == null ? List.<CodeSearchResult>of() : expanded) {
            merge(merged, result);
        }
        return Math.max(0, merged.size() - before);
    }

    private GraphSearchIntent graphSearchIntent(CodeQuestionMode questionMode) {
        return switch (questionMode) {
            case CALL_FLOW -> GraphSearchIntent.FLOW;
            case IMPACT -> GraphSearchIntent.IMPACT;
            case OVERVIEW -> GraphSearchIntent.OVERVIEW;
            default -> GraphSearchIntent.LOCATE;
        };
    }

    private List<CodeSearchResult> llmAreaFollowUpEvidence(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            String normalizedArea,
            int limit
    ) {
        String domainPattern = llmAreaPattern(normalizedArea);
        String behaviorPattern = llmAreaPattern(normalizedArea + " flow pipeline process");
        if (domainPattern.isBlank() || behaviorPattern.isBlank()) {
            return List.of();
        }
        return searchService.runtimeRoleSearch(
                repositoryId,
                domainPattern,
                behaviorPattern,
                Math.max(12, Math.min(24, limit * 3)),
                spaceIds,
                selectedSpaceId
        );
    }

    private String llmAreaPattern(String normalizedArea) {
        List<String> terms = coverageTerms(normalizedArea).stream()
                .filter(term -> term.length() >= 4)
                .limit(12)
                .toList();
        return terms.isEmpty() ? "" : "(" + String.join("|", terms) + ")";
    }

    private List<String> groundedFollowUpQueries(String originalQuestion, String query, String queryArea, String evidenceGroup) {
        String sanitized = sanitizeFollowUpQuery(query);
        if (sanitized.isBlank()) {
            return List.of();
        }
        List<String> queries = new ArrayList<>();
        queries.add(sanitized);
        String normalizedArea = normalizeQuestionText(splitIdentifierTerms(queryArea + " " + sanitized + " " + evidenceGroup + " " + originalQuestion));
        String areaTerms = String.join(" ", coverageTerms(normalizedArea));
        if (!areaTerms.isBlank()) {
            queries.add(sanitized + " " + areaTerms);
        }
        List<String> groupTerms = evidenceGroupQueryTerms(evidenceGroup);
        if (!groupTerms.isEmpty()) {
            String groupQuery = String.join(" ", groupTerms);
            queries.add(sanitized + " " + groupQuery);
            queries.add(groupQuery + " " + String.join(" ", resourceIdentifierQueries(queryArea + " " + sanitized + " " + originalQuestion)));
        }
        List<String> resources = resourceIdentifierQueries(queryArea + " " + sanitized + " " + originalQuestion);
        if (!resources.isEmpty()) {
            String resourceQuery = String.join(" ", resources);
            queries.add(resourceQuery);
        }
        return queries.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(3)
                .toList();
    }

    private String followUpEvidenceGroup(RagPipelineService.CodeEvidenceFollowUpPlan plan, int index, String text) {
        if (plan != null && plan.requiredEvidenceGroups() != null && !plan.requiredEvidenceGroups().isEmpty()) {
            if (index < plan.requiredEvidenceGroups().size()) {
                return plan.requiredEvidenceGroups().get(index);
            }
            List<String> groups = plan.requiredEvidenceGroups();
            String normalized = normalizeQuestionText(splitIdentifierTerms(text));
            for (String group : groups) {
                if (groupMatchesArea(group, normalized)) {
                    return group;
                }
            }
            return groups.get(Math.min(index, groups.size() - 1));
        }
        return inferEvidenceGroup(text);
    }

    private String inferEvidenceGroup(String text) {
        String normalized = normalizeQuestionText(splitIdentifierTerms(text));
        if (normalized.isBlank()) {
            return "";
        }
        if (containsRoleTerms(normalized, "code graph nodes", "code graph edges", "schema", "migration", "table", "ddl", "edge type")) {
            return "graph_schema";
        }
        if (containsRoleTerms(normalized, "websocket", "web socket", "sse", "stream", "message", "event", "event bus", "publish", "subscribe", "push", "heartbeat")) {
            return "async_transport";
        }
        if (containsRoleTerms(normalized, "claim", "claimed", "poll", "polling", "next", "pending", "lease", "dequeue", "queue", "work item", "fetch", "take", "가져", "가져오")) {
            return "queue_claim";
        }
        if (containsRoleTerms(normalized, "callback", "response", "result", "output", "complete", "completion", "finished", "ack", "acknowledge", "응답", "결과", "완료")) {
            return "response_intake";
        }
        if (containsRoleTerms(normalized, "store", "stored", "save", "saved", "persist", "persistence", "insert", "update", "delete", "upsert", "status", "state", "저장", "상태")) {
            return "persistence_update";
        }
        if (containsRoleTerms(normalized, "storage", "persist", "persistence", "insert", "update", "delete", "merge", "replace", "activate", "save")) {
            return "graph_persistence";
        }
        if (containsRoleTerms(normalized, "traversal", "neighbor", "neighbors", "hop", "direction", "path", "related chunks", "expand")) {
            return "graph_traversal";
        }
        if (containsRoleTerms(normalized, "rank", "ranking", "score", "weight", "evidence score")) {
            return "evidence_ranking";
        }
        if (containsRoleTerms(normalized, "answer", "context", "prompt", "citation", "generation", "llm")) {
            return "answer_context";
        }
        if (containsRoleTerms(normalized, "builder", "analyzer", "analysis", "diagnostic", "nodes", "edges")) {
            return "graph_build";
        }
        if (containsRoleTerms(normalized, "controller", "endpoint", "route", "handler", "request mapping")) {
            return "request_intake";
        }
        if (containsRoleTerms(normalized, "service", "orchestrate", "pipeline", "flow", "delegate")) {
            return "orchestration";
        }
        if (containsRoleTerms(normalized, "transaction", "repository", "entity", "annotation", "framework", "spring", "wpf", "winforms")) {
            return "framework_semantics";
        }
        return "";
    }

    private boolean groupMatchesArea(String group, String normalizedArea) {
        if (group == null || group.isBlank() || normalizedArea == null || normalizedArea.isBlank()) {
            return false;
        }
        return evidenceGroupQueryTerms(group).stream().anyMatch(term -> normalizedArea.contains(term.toLowerCase(Locale.ROOT)));
    }

    private boolean requiredEvidenceGroupsSatisfied(
            RagPipelineService.CodeEvidenceFollowUpPlan plan,
            Collection<CodeSearchResult> candidates
    ) {
        if (plan == null || plan.enough() || plan.requiredEvidenceGroups() == null || plan.requiredEvidenceGroups().isEmpty()
                || candidates == null || candidates.isEmpty()) {
            return false;
        }
        List<CodeSearchResult> candidateList = candidates.stream()
                .filter(result -> !isProjectContext(result.chunkType()))
                .toList();
        if (candidateList.isEmpty()) {
            return false;
        }
        for (String group : plan.requiredEvidenceGroups()) {
            if (!followUpEvidenceGroupSatisfied(group, candidateList)) {
                return false;
            }
        }
        return true;
    }

    private boolean followUpEvidenceGroupSatisfied(String group, List<CodeSearchResult> candidates) {
        List<String> terms = evidenceGroupQueryTerms(group);
        if (terms.isEmpty()) {
            return false;
        }
        int requiredMatches = switch (safe(group, "")) {
            case "queue_claim", "response_intake", "persistence_update", "async_transport", "request_intake" -> 2;
            default -> 1;
        };
        return candidates.stream().anyMatch(result -> evidenceGroupMatchCount(terms, result) >= requiredMatches);
    }

    private int evidenceGroupMatchCount(List<String> terms, CodeSearchResult result) {
        if (terms == null || terms.isEmpty() || result == null) {
            return 0;
        }
        String text = normalizeQuestionText(splitIdentifierTerms(String.join(" ",
                safe(result.filePath(), ""),
                safe(result.symbolName(), ""),
                safe(result.className(), ""),
                safe(result.methodName(), ""),
                safe(result.namespaceName(), ""),
                safe(result.content(), "")
        )));
        int matched = 0;
        for (String term : terms) {
            String normalizedTerm = normalizeQuestionText(splitIdentifierTerms(term));
            if (!normalizedTerm.isBlank() && text.contains(normalizedTerm)) {
                matched++;
            }
        }
        return matched;
    }

    private List<String> evidenceGroupQueryTerms(String group) {
        return switch (safe(group, "")) {
            case "entrypoint" -> List.of("controller", "route", "endpoint", "handler", "request", "mapping");
            case "request_intake" -> List.of("controller", "route", "endpoint", "handler", "request", "input", "receive", "submit", "create", "enqueue");
            case "orchestration" -> List.of("service", "orchestrate", "pipeline", "flow", "calls", "delegates");
            case "queue_claim" -> List.of("queue", "pending", "next", "claim", "claimed", "poll", "lease", "dequeue", "work item", "running");
            case "response_intake" -> List.of("response", "result", "output", "complete", "completion", "callback", "ack", "acknowledge", "handle response");
            case "persistence_update" -> List.of("repository", "save", "update", "insert", "delete", "upsert", "persist", "status", "output", "finished", "complete");
            case "async_transport" -> List.of("websocket", "sse", "message", "event", "stream", "queue", "push", "poll", "heartbeat", "ack");
            case "graph_build" -> List.of("graph", "builder", "analyzer", "nodes", "edges", "diagnostics");
            case "graph_persistence" -> List.of("graph", "nodes", "edges", "insert", "update", "delete", "merge", "replace", "active", "persistence");
            case "graph_schema" -> List.of("schema", "table", "migration", "code_graph_nodes", "code_graph_edges", "edge_type", "metadata");
            case "graph_traversal" -> List.of("graph", "traversal", "neighbors", "hop", "direction", "path", "related chunks");
            case "evidence_ranking" -> List.of("evidence", "rank", "score", "weight", "ranking", "reason");
            case "answer_context" -> List.of("answer", "context", "prompt", "citation", "generation", "llm");
            case "framework_semantics" -> List.of("annotation", "framework", "transaction", "controller", "service", "repository", "entity");
            case "data_structure" -> List.of("record", "dto", "class", "type", "metadata", "node", "edge");
            default -> List.of();
        };
    }

    private List<String> resourceIdentifierQueries(String text) {
        List<String> values = new ArrayList<>();
        Matcher matcher = RESOURCE_IDENTIFIER_PATTERN.matcher(safe(text, ""));
        while (matcher.find()) {
            String value = matcher.group().trim();
            if (value.contains("_") || value.contains(".") || value.length() >= 12) {
                values.add(value);
            }
        }
        return values.stream().distinct().limit(6).toList();
    }

    private String sanitizeFollowUpQuery(String query) {
        String safeQuery = safe(query, "").trim();
        if (safeQuery.isBlank()) {
            return "";
        }
        String withoutLeadingFile = LEADING_FILE_QUERY_PATTERN.matcher(safeQuery).replaceAll("");
        return withoutLeadingFile
                .replaceAll("\\s+", " ")
                .trim();
    }

    private List<CodeSearchResult> rankedCodeEvidence(
            String question,
            CodeQuestionMode questionMode,
            Map<UUID, CodeSearchResult> merged,
            int limit,
        RagPipelineService.CodeEvidenceFollowUpPlan followUpPlan
    ) {
        List<CodeSearchResult> ranked = evidenceRanker.rank(question, questionMode, List.copyOf(merged.values()));
        ranked = applyAnalysisDiagnosticAffinityRanking(question, ranked);
        int selectionLimit = candidateSlateLimit(limit);
        List<CodeSearchResult> selected = ranked.stream()
                .limit(selectionLimit)
                .toList();
        selected = ensureMarkedChecklistGroupCoverage(ranked, selected, selectionLimit);
        return ensureLlmPlannedCoverage(question, followUpPlan, ranked, selected, selectionLimit);
    }

    private int candidateSlateLimit(int answerLimit) {
        int configured = properties == null
                ? 40
                : properties.getRag().getPipeline().getCodeEvidenceAdjudicationMaxCandidates();
        return Math.max(1, Math.min(Math.max(answerLimit, configured), 40));
    }

    private int minCodeEvidence(CodeQuestionMode questionMode) {
        return switch (questionMode) {
            case OVERVIEW, IMPACT, REASONING -> 4;
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
        int searchLimit = questionMode == CodeQuestionMode.OVERVIEW ? Math.min(24, limit + 6) : Math.min(20, limit + 4);
        List<CodeSearchResult> searchResults = searchService.searchWithoutGraph(repositoryId, question, searchLimit, spaceIds, selectedSpaceId, graphSearchIntent(questionMode));
        if (searchResults == null || searchResults.isEmpty()) {
            searchResults = searchService.search(repositoryId, question, searchLimit, spaceIds, selectedSpaceId);
        }
        for (CodeSearchResult result : searchResults) {
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
        List<CodeSearchResult> ranked = evidenceRanker.rank(question, questionMode, List.copyOf(merged.values()));
        ranked = applyAnalysisDiagnosticAffinityRanking(question, ranked);
        List<CodeSearchResult> selected = ranked.stream()
                .limit(limit)
                .toList();
        return limitedMutable(selected, limit);
    }

    private List<CodeSearchResult> answerContextResults(
            CodeQuestionMode questionMode,
            String question,
            List<CodeSearchResult> results,
            RagPipelineService.CodeEvidenceFollowUpPlan followUpPlan
    ) {
        int limit = pipelineService.codeContextLimit(questionMode == CodeQuestionMode.OVERVIEW ? OVERVIEW_CONTEXT_LIMIT : DEFAULT_CONTEXT_LIMIT);
        List<CodeSearchResult> ranked = evidenceRanker.rank(question, questionMode, results);
        ranked = applyAnalysisDiagnosticAffinityRanking(question, ranked);
        List<RagPipelineService.CodeEvidenceChecklistItem> checklist = followUpPlan == null ? List.of() : followUpPlan.checklist();
        RagPipelineService.CodeEvidenceAdjudication adjudication = pipelineService.adjudicateCodeEvidence(question, questionMode.value(), ranked, limit, checklist);
        if (adjudication.used()) {
            ranked = adjudication.results();
            List<CodeSearchResult> selected = preservePinnedEvidence(ranked, llmEvidenceSlateSelection(ranked, limit), limit);
            selected = selected.stream()
                    .sorted(Comparator.comparingInt(this::llmEvidenceSlateRank)
                            .thenComparing((CodeSearchResult result) -> -evidenceRanker.score(result)))
                    .toList();
            log.info("Code RAG LLM evidence slate selected={} final={} llmSelectedFiles={} finalFiles={} question={}",
                    ranked.stream().filter(this::isLlmEvidenceAdjudicationSelected).count(),
                    selected.size(),
                    selectedPathSummary(ranked.stream().filter(this::isLlmEvidenceAdjudicationSelected).toList()),
                    selectedPathSummary(selected),
                    abbreviate(question, 180));
            return selected;
        }
        if (adjudication.attempted()) {
            ranked = adjudication.results();
        }
        List<CodeSearchResult> selected;
        if (questionMode == CodeQuestionMode.CALL_FLOW) {
            selected = ranked.stream()
                    .sorted(Comparator.comparingDouble((CodeSearchResult result) -> evidenceRanker.score(result)).reversed()
                            .thenComparingInt(this::flowRank))
                    .limit(limit)
                    .toList();
            selected = sourceAwareEvidenceSelection(questionMode, question, ranked, selected, limit);
            selected = ensureLlmPlannedCoverage(question, followUpPlan, ranked, selected, limit);
            selected = preferStructuredEvidence(questionMode, question, ranked, selected, limit);
            return orderLlmClassifiedEvidence(orderLlmPlannedEvidence(followUpPlan, preservePinnedEvidence(ranked, selected, limit)));
        }
        if (questionMode == CodeQuestionMode.OVERVIEW || questionMode == CodeQuestionMode.IMPACT || questionMode == CodeQuestionMode.REASONING) {
            selected = diverseByCategory(ranked, limit);
            selected = sourceAwareEvidenceSelection(questionMode, question, ranked, selected, limit);
            selected = ensureLlmPlannedCoverage(question, followUpPlan, ranked, selected, limit);
            selected = preferStructuredEvidence(questionMode, question, ranked, selected, limit);
            return orderLlmClassifiedEvidence(orderLlmPlannedEvidence(followUpPlan, preservePinnedEvidence(ranked, selected, limit)));
        }
        selected = ranked.stream().limit(limit).toList();
        selected = sourceAwareEvidenceSelection(questionMode, question, ranked, selected, limit);
        selected = ensureLlmPlannedCoverage(question, followUpPlan, ranked, selected, limit);
        selected = preferStructuredEvidence(questionMode, question, ranked, selected, limit);
        return orderLlmClassifiedEvidence(orderLlmPlannedEvidence(followUpPlan, preservePinnedEvidence(ranked, selected, limit)));
    }

    private List<CodeSearchResult> orderLlmClassifiedEvidence(List<CodeSearchResult> selected) {
        if (selected == null || selected.size() <= 1 || selected.stream().noneMatch(this::hasLlmEvidenceClassification)) {
            return selected == null ? List.of() : selected;
        }
        Map<UUID, Integer> originalOrder = new LinkedHashMap<>();
        for (int index = 0; index < selected.size(); index++) {
            originalOrder.putIfAbsent(selected.get(index).chunkId(), index);
        }
        return selected.stream()
                .sorted(Comparator
                        .comparingInt((CodeSearchResult result) -> {
                            List<String> phases = evidencePhases(result);
                            return phases.isEmpty() ? 99 : phaseOrder(phases.get(0));
                        })
                        .thenComparing((CodeSearchResult result) -> "direct_code".equals(citationKind(result)) ? 0 : 1)
                        .thenComparing((CodeSearchResult result) -> -evidenceRanker.score(result))
                        .thenComparingInt(result -> originalOrder.getOrDefault(result.chunkId(), Integer.MAX_VALUE)))
                .toList();
    }

    private List<CodeSearchResult> llmEvidenceSlateSelection(List<CodeSearchResult> ranked, int limit) {
        if (ranked == null || ranked.isEmpty()) {
            return List.of();
        }
        int selectedCount = (int) ranked.stream().filter(this::isLlmEvidenceAdjudicationSelected).count();
        int safeLimit = Math.max(Math.max(1, limit), selectedCount);
        List<CodeSearchResult> selected = new ArrayList<>();
        ranked.stream()
                .filter(this::isLlmEvidenceAdjudicationSelected)
                .sorted(Comparator
                        .comparingInt(this::llmEvidenceSlateRank)
                        .thenComparing((CodeSearchResult result) -> Boolean.TRUE.equals(metadataBoolean(result, "llmEvidenceSlateMustUse")) ? 0 : 1)
                        .thenComparing((CodeSearchResult result) -> -evidenceRanker.score(result)))
                .forEach(result -> addIfAbsent(selected, result, safeLimit));
        return limitedMutable(selected, safeLimit);
    }

    private List<CodeSearchResult> ensureLlmChecklistGroupCoverage(
            RagPipelineService.CodeEvidenceFollowUpPlan followUpPlan,
            List<CodeSearchResult> ranked,
            List<CodeSearchResult> selected,
            int limit
    ) {
        List<String> requiredGroups = llmChecklistGroups(followUpPlan);
        if (requiredGroups.isEmpty() || ranked == null || ranked.isEmpty() || selected == null || selected.isEmpty()) {
            return selected == null ? List.of() : selected;
        }
        List<CodeSearchResult> adjusted = new ArrayList<>(selected);
        int coverageLimit = Math.max(Math.max(1, limit), requiredGroups.size());
        for (String group : requiredGroups) {
            if (selectedHasLlmCoverageGroup(adjusted, group)) {
                continue;
            }
            CodeSearchResult replacement = ranked.stream()
                    .filter(result -> !containsChunk(adjusted, result))
                    .filter(result -> group.equals(llmCoverageGroup(result)))
                    .sorted(Comparator
                            .comparing((CodeSearchResult result) -> isLlmEvidenceAdjudicationSelected(result) ? 0 : 1)
                            .thenComparing((CodeSearchResult result) -> Boolean.TRUE.equals(metadataBoolean(result, "llmEvidenceSlateMustUse")) ? 0 : 1)
                            .thenComparingInt(this::llmEvidenceSlateRank)
                            .thenComparing((CodeSearchResult result) -> -evidenceRanker.score(result)))
                    .findFirst()
                    .orElse(null);
            if (replacement == null) {
                continue;
            }
            CodeSearchResult marked = markLlmChecklistGroupRequired(replacement, group);
            if (adjusted.size() < coverageLimit) {
                adjusted.add(marked);
                continue;
            }
            int replaceIndex = weakestNonRequiredGroupIndex(adjusted, requiredGroups);
            if (replaceIndex >= 0) {
                adjusted.set(replaceIndex, marked);
            }
        }
        return limitedMutable(orderLlmClassifiedEvidence(adjusted), coverageLimit);
    }

    private List<String> llmChecklistGroups(RagPipelineService.CodeEvidenceFollowUpPlan followUpPlan) {
        if (followUpPlan == null) {
            return List.of();
        }
        LinkedHashSet<String> groups = new LinkedHashSet<>();
        addLlmChecklistGroups(groups, followUpPlan.requiredEvidenceGroups());
        for (RagPipelineService.CodeEvidenceChecklistItem item : followUpPlan.checklist()) {
            if (item != null) {
                addLlmChecklistGroups(groups, List.of(item.evidenceGroup()));
            }
        }
        return groups.stream().limit(8).toList();
    }

    private void addLlmChecklistGroups(Set<String> groups, List<String> values) {
        for (String value : values == null ? List.<String>of() : values) {
            String group = normalizeEvidenceGroupValue(value);
            if (!group.isBlank() && !"unknown".equals(group)) {
                groups.add(group);
            }
        }
    }

    private String normalizeEvidenceGroupValue(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private boolean selectedHasLlmCoverageGroup(List<CodeSearchResult> selected, String group) {
        return selected != null && selected.stream().anyMatch(result -> group.equals(llmCoverageGroup(result)));
    }

    private List<CodeSearchResult> ensureMarkedChecklistGroupCoverage(
            List<CodeSearchResult> ranked,
            List<CodeSearchResult> selected,
            int limit
    ) {
        if (ranked == null || ranked.isEmpty() || selected == null || selected.isEmpty()) {
            return selected == null ? List.of() : selected;
        }
        LinkedHashSet<String> groups = ranked.stream()
                .filter(result -> Boolean.TRUE.equals(metadataBoolean(result, "llmChecklistGroupRequired")))
                .map(this::llmCoverageGroup)
                .filter(group -> !group.isBlank() && !"unknown".equals(group))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (groups.isEmpty()) {
            return selected;
        }
        List<CodeSearchResult> adjusted = new ArrayList<>(selected);
        int coverageLimit = Math.max(Math.max(1, limit), groups.size());
        for (String group : groups) {
            if (selectedHasLlmCoverageGroup(adjusted, group)) {
                continue;
            }
            CodeSearchResult replacement = ranked.stream()
                    .filter(result -> !containsChunk(adjusted, result))
                    .filter(result -> group.equals(llmCoverageGroup(result)))
                    .max(Comparator
                            .comparingDouble((CodeSearchResult result) -> Boolean.TRUE.equals(metadataBoolean(result, "llmChecklistGroupRequired")) ? 1.0 : 0.0)
                            .thenComparingDouble(evidenceRanker::score))
                    .orElse(null);
            if (replacement == null) {
                continue;
            }
            if (adjusted.size() < coverageLimit) {
                adjusted.add(replacement);
                continue;
            }
            int replaceIndex = weakestNonRequiredGroupIndex(adjusted, groups.stream().toList());
            if (replaceIndex >= 0) {
                adjusted.set(replaceIndex, replacement);
            }
        }
        return limitedMutable(adjusted, coverageLimit);
    }

    private String llmCoverageGroup(CodeSearchResult result) {
        return normalizeEvidenceGroupValue(metadataString(result, "llmEvidenceCoverageGroup", "llmChecklistGroup"));
    }

    private CodeSearchResult markLlmChecklistGroupRequired(CodeSearchResult result, String group) {
        if (result == null || group == null || group.isBlank()) {
            return result;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
        metadata.put("llmChecklistGroupRequired", true);
        metadata.put("llmChecklistGroup", group);
        metadata.putIfAbsent("llmEvidenceCoverageGroup", group);
        return withMetadata(result, metadata);
    }

    private CodeSearchResult withMetadata(CodeSearchResult result, Map<String, Object> metadata) {
        if (result == null) {
            return null;
        }
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
                result.score(),
                metadata == null ? Map.of() : Map.copyOf(metadata)
        );
    }

    private int weakestNonRequiredGroupIndex(List<CodeSearchResult> selected, List<String> requiredGroups) {
        Set<String> groups = new LinkedHashSet<>(requiredGroups == null ? List.of() : requiredGroups);
        for (int index = selected.size() - 1; index >= 0; index--) {
            CodeSearchResult result = selected.get(index);
            if (isRequiredConversationPinned(result)
                    || Boolean.TRUE.equals(metadataBoolean(result, "llmEvidenceSlateMustUse"))
                    || Boolean.TRUE.equals(metadataBoolean(result, "llmChecklistGroupRequired"))) {
                continue;
            }
            String group = llmCoverageGroup(result);
            if (groups.contains(group) && selected.stream().filter(item -> group.equals(llmCoverageGroup(item))).count() <= 1) {
                continue;
            }
            return index;
        }
        return -1;
    }

    private CodeSearchResult markLlmSearchPlanGroupEvidence(
            CodeSearchResult result,
            RagPipelineService.CodeEvidenceChecklistItem item,
            String query
    ) {
        if (result == null || item == null) {
            return result;
        }
        String group = normalizeEvidenceGroupValue(item.evidenceGroup());
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
        metadata.put("llmSearchPlanEvidence", true);
        metadata.put("llmChecklistGroupRequired", true);
        metadata.put("llmChecklistGroup", group);
        metadata.put("llmEvidenceCoverageGroup", group);
        metadata.put("llmChecklistClaimId", safe(item.claimId(), ""));
        metadata.put("llmChecklistGoal", safe(item.goal(), ""));
        metadata.put("llmSearchPlanQuery", safe(query, ""));
        metadata.put("evidenceRankReason", String.valueOf(metadata.getOrDefault("evidenceRankReason", ""))
                + (metadata.containsKey("evidenceRankReason") ? "; " : "")
                + "Selected by LLM evidence search plan group=" + group);
        return boost(withMetadata(result, metadata), 0.10);
    }

    private void addIfAbsent(List<CodeSearchResult> results, CodeSearchResult candidate, int limit) {
        if (candidate == null || results.size() >= limit || containsChunk(results, candidate)) {
            return;
        }
        results.add(candidate);
    }

    private boolean isLlmEvidenceAdjudicationSelected(CodeSearchResult result) {
        return result != null
                && result.metadata() != null
                && Boolean.TRUE.equals(result.metadata().get("llmEvidenceAdjudicationSelected"));
    }

    private int llmEvidenceSlateRank(CodeSearchResult result) {
        if (result == null || result.metadata() == null) {
            return Integer.MAX_VALUE;
        }
        Object value = result.metadata().get("llmEvidenceSlateRank");
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? Integer.MAX_VALUE : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private Boolean metadataBoolean(CodeSearchResult result, String key) {
        if (result == null || result.metadata() == null || key == null) {
            return false;
        }
        Object value = result.metadata().get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private List<CodeSearchResult> preferStructuredEvidence(
            CodeQuestionMode questionMode,
            String question,
            List<CodeSearchResult> ranked,
            List<CodeSearchResult> selected,
            int limit
    ) {
        List<CodeSearchResult> adjusted = new ArrayList<>(selected == null ? List.of() : selected);
        if (ranked == null || ranked.isEmpty() || adjusted.isEmpty()) {
            return adjusted;
        }
        int targetStructured = structuredEvidenceTarget(questionMode, question, limit);
        while (structuredEvidenceCount(adjusted) < targetStructured) {
            CodeSearchResult replacement = ranked.stream()
                    .filter(result -> !containsChunk(adjusted, result))
                    .filter(result -> isStructuredEvidenceCandidate(result, question))
                    .findFirst()
                    .orElse(null);
            if (replacement == null) {
                break;
            }
            int replaceIndex = weakestLineWindowIndex(adjusted);
            if (replaceIndex < 0 && (questionMode == CodeQuestionMode.CALL_FLOW || isFlowIntent(normalizeQuestionText(question)))) {
                replaceIndex = weakestAuxiliaryIndex(adjusted);
            }
            if (replaceIndex < 0) {
                if (adjusted.size() < limit) {
                    adjusted.add(replacement);
                    continue;
                }
                break;
            }
            adjusted.set(replaceIndex, replacement);
        }
        return limitedMutable(adjusted, limit);
    }

    private int structuredEvidenceTarget(CodeQuestionMode questionMode, String question, int limit) {
        int safeLimit = Math.max(1, limit);
        if (questionMode == CodeQuestionMode.CALL_FLOW || isFlowIntent(normalizeQuestionText(question))) {
            return Math.min(safeLimit, Math.max(4, safeLimit / 2));
        }
        return switch (questionMode) {
            case OVERVIEW, CALL_FLOW, REASONING, IMPACT -> Math.min(safeLimit, Math.max(2, safeLimit / 3));
            default -> Math.min(safeLimit, 1);
        };
    }

    private long structuredEvidenceCount(List<CodeSearchResult> results) {
        return results == null ? 0 : results.stream().filter(result -> isStructuredEvidenceCandidate(result, "")).count();
    }

    private boolean isStructuredEvidenceCandidate(CodeSearchResult result, String question) {
        if (result == null || isProjectContext(result.chunkType()) || isLineWindowEvidence(result)) {
            return false;
        }
        if (!isMainImplementationEvidence(result, asksForLocalAgent(question))) {
            return false;
        }
        return isStructured(result.chunkType())
                || notBlank(result.methodName())
                || notBlank(result.className())
                || notBlank(result.symbolName());
    }

    private int weakestLineWindowIndex(List<CodeSearchResult> selected) {
        for (int index = selected.size() - 1; index >= 0; index--) {
            CodeSearchResult result = selected.get(index);
            if (!isRequiredConversationPinned(result) && isLineWindowEvidence(result)) {
                return index;
            }
        }
        return -1;
    }

    private List<CodeSearchResult> orderLlmPlannedEvidence(RagPipelineService.CodeEvidenceFollowUpPlan followUpPlan, List<CodeSearchResult> selected) {
        List<String> areas = llmCoverageAreas(followUpPlan);
        if (areas.isEmpty() || selected == null || selected.size() <= 1) {
            return selected == null ? List.of() : selected;
        }
        if (selected.stream().anyMatch(result -> isConversationPinned(result) || isRequiredConversationPinned(result))) {
            return selected;
        }
        Map<UUID, Integer> originalOrder = new LinkedHashMap<>();
        for (int index = 0; index < selected.size(); index++) {
            originalOrder.putIfAbsent(selected.get(index).chunkId(), index);
        }
        return selected.stream()
                .sorted(Comparator
                        .comparingDouble((CodeSearchResult result) -> isRequiredConversationPinned(result) ? -1 : -bestAreaCoverageScore(areas, result))
                        .thenComparing((CodeSearchResult result) -> -evidenceRanker.score(result))
                        .thenComparingInt(result -> originalOrder.getOrDefault(result.chunkId(), Integer.MAX_VALUE)))
                .toList();
    }

    private double bestAreaCoverageScore(List<String> areas, CodeSearchResult result) {
        return areas.stream().mapToDouble(area -> areaCoverageScore(area, result)).max().orElse(0);
    }

    private List<CodeSearchResult> applyAnalysisDiagnosticAffinityRanking(String question, List<CodeSearchResult> ranked) {
        Set<String> requiredLanguages = requiredAnalysisDiagnosticLanguages(question);
        if (requiredLanguages.isEmpty() || ranked == null || ranked.size() <= 1) {
            return ranked == null ? List.of() : ranked;
        }
        Map<UUID, Integer> originalOrder = new LinkedHashMap<>();
        for (int index = 0; index < ranked.size(); index++) {
            originalOrder.putIfAbsent(ranked.get(index).chunkId(), index);
        }
        return ranked.stream()
                .sorted(Comparator
                        .comparingDouble((CodeSearchResult result) -> -analysisDiagnosticSelectionScore(question, requiredLanguages, result))
                        .thenComparingInt(result -> originalOrder.getOrDefault(result.chunkId(), Integer.MAX_VALUE)))
                .toList();
    }

    private double analysisDiagnosticSelectionScore(String question, Set<String> requiredLanguages, CodeSearchResult result) {
        double score = evidenceRanker.score(result);
        if (requiredLanguages == null || requiredLanguages.isEmpty() || !"GRAPH_ANALYSIS".equals(fallbackScope(result))) {
            return score;
        }
        String language = analysisDiagnosticLanguage(result);
        if (requiredLanguages.contains(language)) {
            return score + 0.75 + Math.max(0, areaCoverageScore(question, result) * 0.05);
        }
        if (!language.isBlank()) {
            return score - 0.25;
        }
        return score;
    }

    private List<CodeSearchResult> ensureLlmPlannedCoverage(
            String question,
            RagPipelineService.CodeEvidenceFollowUpPlan followUpPlan,
            List<CodeSearchResult> ranked,
            List<CodeSearchResult> selected,
            int limit
    ) {
        List<CodeSearchResult> adjusted = new ArrayList<>(selected == null ? List.of() : selected);
        List<String> areas = llmCoverageAreas(followUpPlan);
        LinkedHashSet<String> requiredFallbackScopes = new LinkedHashSet<>(requiredFallbackScopes(question, areas));
        boolean analysisAffinityRequired = suggestsAnalysisDiagnosticFallback(question, areas);
        if (requiredFallbackScopes.isEmpty() && analysisAffinityRequired) {
            requiredFallbackScopes.add("GRAPH_ANALYSIS");
        }
        if (!llmEvidenceCoverageEnabled() || ranked == null || ranked.isEmpty() || adjusted.isEmpty()
                || (areas.isEmpty() && requiredFallbackScopes.isEmpty() && !analysisAffinityRequired)) {
            return adjusted;
        }
        LinkedHashSet<String> requiredRoles = new LinkedHashSet<>();
        for (String area : areas) {
            requiredRoles.addAll(requiredEvidenceRoles(area));
        }
        Set<String> requiredFallbackScopesForSelection = Set.copyOf(requiredFallbackScopes);
        for (String area : areas) {
            for (String role : requiredEvidenceRoles(area)) {
                if (selectedHasRoleCoverage(role, adjusted)) {
                    continue;
                }
                int existingRoleIndex = existingRoleCoverageIndex(role, adjusted);
                if (existingRoleIndex >= 0) {
                    adjusted.set(existingRoleIndex, markLlmPlannedCoverage(adjusted.get(existingRoleIndex), area, role));
                    continue;
                }
                CodeSearchResult replacement = ranked.stream()
                        .filter(result -> !containsChunk(adjusted, result))
                        .filter(result -> evidenceRoles(result).contains(role))
                        .max(Comparator
                                .comparingDouble((CodeSearchResult result) -> roleCoverageScore(area, role, result))
                                .thenComparingDouble(evidenceRanker::score))
                        .orElse(null);
                if (replacement == null) {
                    continue;
                }
                int replaceIndex = weakestRequiredCoverageIndex(requiredRoles, requiredFallbackScopesForSelection, areas, adjusted);
                if (replaceIndex >= 0) {
                    adjusted.set(replaceIndex, markLlmPlannedCoverage(replacement, area, role));
                } else if (adjusted.size() < limit) {
                    adjusted.add(markLlmPlannedCoverage(replacement, area, role));
                }
            }
            if (selectedHasAreaCoverage(area, adjusted)) {
                continue;
            }
            CodeSearchResult replacement = ranked.stream()
                    .filter(result -> !containsChunk(adjusted, result))
                    .filter(result -> areaCoverageScore(area, result) > 0)
                    .max(Comparator
                            .comparingDouble((CodeSearchResult result) -> areaCoverageScore(area, result))
                            .thenComparingDouble(evidenceRanker::score))
                    .orElse(null);
            if (replacement == null) {
                continue;
            }
            int replaceIndex = weakestLlmCoverageIndex(areas, requiredFallbackScopesForSelection, adjusted);
            if (replaceIndex >= 0) {
                adjusted.set(replaceIndex, markLlmPlannedCoverage(replacement, area));
            } else if (adjusted.size() < limit) {
                adjusted.add(markLlmPlannedCoverage(replacement, area));
            }
        }
        for (String scope : requiredFallbackScopes) {
            if (selectedHasFallbackScopeCoverage(scope, question + " " + String.join(" ", areas), adjusted)) {
                continue;
            }
            CodeSearchResult replacement = ranked.stream()
                    .filter(result -> !containsChunk(adjusted, result))
                    .filter(result -> scope.equals(fallbackScope(result)))
                    .max(Comparator
                            .comparingDouble((CodeSearchResult result) -> fallbackScopeCoverageScore(String.join(" ", areas), scope, result))
                            .thenComparingDouble(evidenceRanker::score))
                    .orElse(null);
            if (replacement == null) {
                continue;
            }
            int replaceIndex = weakestRequiredCoverageIndex(requiredRoles, requiredFallbackScopesForSelection, areas, adjusted);
            if (replaceIndex >= 0) {
                adjusted.set(replaceIndex, markLlmPlannedFallbackScopeCoverage(replacement, String.join(" ", areas), scope));
            } else if (adjusted.size() < limit) {
                adjusted.add(markLlmPlannedFallbackScopeCoverage(replacement, String.join(" ", areas), scope));
            }
        }
        for (String role : requiredRoles) {
            if (selectedHasAnyRoleCoverage(role, adjusted)) {
                continue;
            }
            CodeSearchResult replacement = ranked.stream()
                    .filter(result -> !containsChunk(adjusted, result))
                    .filter(result -> evidenceRoles(result).contains(role))
                    .max(Comparator
                            .comparingDouble((CodeSearchResult result) -> roleCoverageScore(String.join(" ", areas), role, result))
                            .thenComparingDouble(evidenceRanker::score))
                    .orElse(null);
            if (replacement == null) {
                continue;
            }
            int replaceIndex = weakestRequiredCoverageIndex(requiredRoles, requiredFallbackScopesForSelection, areas, adjusted);
            if (replaceIndex >= 0) {
                adjusted.set(replaceIndex, markLlmPlannedCoverage(replacement, String.join(" ", areas), role));
            } else if (adjusted.size() < limit) {
                adjusted.add(markLlmPlannedCoverage(replacement, String.join(" ", areas), role));
            }
        }
        List<CodeSearchResult> affinityAdjusted = ensureAnalysisDiagnosticAffinityCoverage(question + " " + String.join(" ", areas), ranked, adjusted, limit);
        return limitedMutable(affinityAdjusted, limit);
    }

    private List<CodeSearchResult> ensureAnalysisDiagnosticAffinityCoverage(
            String question,
            List<CodeSearchResult> ranked,
            List<CodeSearchResult> selected,
            int limit
    ) {
        Set<String> requiredLanguages = requiredAnalysisDiagnosticLanguages(question);
        if (requiredLanguages.isEmpty() || ranked == null || ranked.isEmpty() || selected == null || selected.isEmpty()) {
            return selected == null ? List.of() : selected;
        }
        boolean hasMatchingDiagnostic = selected.stream().anyMatch(result ->
                "GRAPH_ANALYSIS".equals(fallbackScope(result))
                        && requiredLanguages.contains(analysisDiagnosticLanguage(result)));
        if (hasMatchingDiagnostic) {
            return selected;
        }
        CodeSearchResult replacement = ranked.stream()
                .filter(result -> !containsChunk(selected, result))
                .filter(result -> "GRAPH_ANALYSIS".equals(fallbackScope(result)))
                .filter(result -> requiredLanguages.contains(analysisDiagnosticLanguage(result)))
                .max(Comparator
                        .comparingDouble((CodeSearchResult result) -> fallbackScopeCoverageScore(question, "GRAPH_ANALYSIS", result))
                        .thenComparingDouble(evidenceRanker::score))
                .orElse(null);
        if (replacement == null) {
            return selected;
        }
        List<CodeSearchResult> adjusted = new ArrayList<>(selected);
        int replaceIndex = nonMatchingAnalysisDiagnosticIndex(requiredLanguages, adjusted);
        if (replaceIndex < 0) {
            replaceIndex = weakestLlmCoverageIndex(List.of(question), Set.of("GRAPH_ANALYSIS"), adjusted);
        }
        CodeSearchResult marked = markLlmPlannedFallbackScopeCoverage(replacement, question, "GRAPH_ANALYSIS");
        if (replaceIndex >= 0) {
            adjusted.set(replaceIndex, marked);
        } else if (adjusted.size() < limit) {
            adjusted.add(marked);
        }
        return limitedMutable(adjusted, limit);
    }

    private int nonMatchingAnalysisDiagnosticIndex(Set<String> requiredLanguages, List<CodeSearchResult> selected) {
        for (int index = selected.size() - 1; index >= 0; index--) {
            CodeSearchResult result = selected.get(index);
            if (!isRequiredConversationPinned(result)
                    && "GRAPH_ANALYSIS".equals(fallbackScope(result))
                    && !requiredLanguages.contains(analysisDiagnosticLanguage(result))) {
                return index;
            }
        }
        return -1;
    }

    private List<String> llmCoverageAreas(RagPipelineService.CodeEvidenceFollowUpPlan followUpPlan) {
        if (followUpPlan == null || !followUpPlan.attempted() || followUpPlan.enough()) {
            return List.of();
        }
        LinkedHashSet<String> areas = new LinkedHashSet<>();
        addCoverageAreas(areas, followUpPlan.queryAreas());
        addCoverageAreas(areas, followUpPlan.missingAreas());
        addCoverageAreas(areas, followUpPlan.followUpQueries());
        addCoverageAreas(areas, followUpPlan.requiredEvidenceGroups());
        return areas.stream().limit(6).toList();
    }

    private void addCoverageAreas(Set<String> areas, List<String> values) {
        for (String value : values == null ? List.<String>of() : values) {
            String normalized = normalizeQuestionText(splitIdentifierTerms(value));
            if (!normalized.isBlank()) {
                areas.add(normalized);
            }
        }
    }

    private boolean selectedHasAreaCoverage(String area, List<CodeSearchResult> selected) {
        List<String> requiredRoles = requiredEvidenceRoles(area);
        if (!requiredRoles.isEmpty()) {
            return requiredRoles.stream().allMatch(role -> selectedHasRoleCoverage(role, selected));
        }
        return selected.stream().anyMatch(result -> hasAreaCoverage(area, result));
    }

    private boolean selectedHasRoleCoverage(String role, List<CodeSearchResult> selected) {
        return selected.stream().anyMatch(result -> hasLlmPlannedCoverageRole(role, result));
    }

    private boolean selectedHasAnyRoleCoverage(String role, List<CodeSearchResult> selected) {
        return selected.stream().anyMatch(result ->
                hasLlmPlannedCoverageRole(role, result) || evidenceRoles(result).contains(role));
    }

    private int existingRoleCoverageIndex(String role, List<CodeSearchResult> selected) {
        for (int index = 0; index < selected.size(); index++) {
            CodeSearchResult result = selected.get(index);
            if (!isRequiredConversationPinned(result) && evidenceRoles(result).contains(role)) {
                return index;
            }
        }
        return -1;
    }

    private boolean hasAreaCoverage(String area, CodeSearchResult result) {
        if (hasLlmPlannedCoverageArea(area, result)) {
            return true;
        }
        Set<String> areaTerms = coverageTerms(area);
        int requiredMatches = Math.max(1, Math.min(2, areaTerms.size()));
        return areaMatchedTermCount(area, result) >= requiredMatches && areaCoverageScore(area, result) >= 5.0;
    }

    private boolean hasLlmPlannedCoverageArea(String area, CodeSearchResult result) {
        if (result == null || result.metadata() == null) {
            return false;
        }
        Object plannedArea = result.metadata().get("llmCoverageArea");
        if (plannedArea == null) {
            return false;
        }
        Set<String> requestedTerms = coverageTerms(area);
        Set<String> plannedTerms = coverageTerms(String.valueOf(plannedArea));
        if (requestedTerms.isEmpty() || plannedTerms.isEmpty()) {
            return false;
        }
        long overlap = requestedTerms.stream().filter(plannedTerms::contains).count();
        int requiredMatches = Math.max(1, Math.min(2, requestedTerms.size()));
        return overlap >= requiredMatches;
    }

    private boolean hasLlmPlannedCoverageRole(String role, CodeSearchResult result) {
        if (role == null || role.isBlank() || result == null || result.metadata() == null) {
            return false;
        }
        Object plannedRole = result.metadata().get("llmCoverageRole");
        return role.equals(String.valueOf(plannedRole));
    }

    private int areaMatchedTermCount(String area, CodeSearchResult result) {
        if (result == null || area == null || area.isBlank()) {
            return 0;
        }
        Set<String> areaTerms = coverageTerms(area);
        Set<String> identityTerms = coverageTerms(String.join(" ",
                safe(result.filePath(), ""),
                safe(result.symbolName(), ""),
                safe(result.className(), ""),
                safe(result.methodName(), ""),
                safe(result.namespaceName(), "")
        ));
        Set<String> contentTerms = coverageTerms(safe(result.content(), ""));
        int matched = 0;
        for (String term : areaTerms) {
            if (identityTerms.contains(term) || contentTerms.contains(term)) {
                matched++;
            }
        }
        return matched;
    }

    private double areaCoverageScore(String area, CodeSearchResult result) {
        if (result == null || area == null || area.isBlank()) {
            return 0;
        }
        Set<String> areaTerms = coverageTerms(area);
        if (areaTerms.isEmpty()) {
            return 0;
        }
        Set<String> identityTerms = coverageTerms(String.join(" ",
                safe(result.filePath(), ""),
                safe(result.symbolName(), ""),
                safe(result.className(), ""),
                safe(result.methodName(), ""),
                safe(result.namespaceName(), "")
        ));
        Set<String> contentTerms = coverageTerms(safe(result.content(), ""));
        double score = 0;
        for (String term : areaTerms) {
            if (identityTerms.contains(term)) {
                score += 3.0;
            } else if (contentTerms.contains(term)) {
                score += 1.0;
            }
        }
        if (score <= 0) {
            return 0;
        }
        if (isMainImplementationEvidence(result, false)) {
            score += 1.5;
        }
        if (isStructuredEvidenceCandidate(result, "")) {
            score += 1.0;
        }
        String sourceRole = CodeSourceClassifier.sourceRole(result);
        if (CodeSourceClassifier.SOURCE_TEST.equals(sourceRole)
                || CodeSourceClassifier.SOURCE_DOCS.equals(sourceRole)
                || CodeSourceClassifier.SOURCE_GENERATED.equals(sourceRole)) {
            score -= 2.0;
        }
        return Math.max(0, score);
    }

    private double roleCoverageScore(String area, String role, CodeSearchResult result) {
        if (result == null || role == null || role.isBlank() || !evidenceRoles(result).contains(role)) {
            return 0;
        }
        double score = 8.0 + areaCoverageScore(area, result);
        if (isMainImplementationEvidence(result, false)) {
            score += 1.5;
        }
        if (isStructuredEvidenceCandidate(result, "")) {
            score += 1.0;
        }
        return score;
    }

    private List<String> requiredFallbackScopes(String question, List<String> areas) {
        String normalized = normalizeQuestionText(splitIdentifierTerms(String.join(" ",
                safe(question, ""),
                String.join(" ", areas == null ? List.of() : areas)
        )));
        if (normalized.isBlank()) {
            return List.of();
        }
        boolean fallbackOrFailure = containsRoleTerms(normalized,
                "fallback", "fallbacks", "fail", "failed", "failure", "partial", "partially",
                "skip", "skipped", "unavailable", "exception", "catch", "recover", "degrade", "degraded");
        if (!fallbackOrFailure) {
            return List.of();
        }
        LinkedHashSet<String> scopes = new LinkedHashSet<>();
        if (containsRoleTerms(normalized, "route", "routing", "router", "intent", "classify", "decision", "clarify")) {
            scopes.add("ROUTING");
        }
        if (containsRoleTerms(normalized, "graph", "semantic", "analysis", "analyzer", "diagnostic", "diagnostics", "indexing", "index", "build", "parser", "parse")) {
            scopes.add("GRAPH_ANALYSIS");
        }
        if (containsRoleTerms(normalized, "search", "retrieve", "retrieval", "expand", "expansion", "traversal", "related", "ranked results", "query")) {
            scopes.add("SEARCH_EXPANSION");
        }
        if (containsRoleTerms(normalized, "answer", "generation", "response", "citation", "prompt", "model", "llm", "repair", "rewrite")) {
            scopes.add("ANSWER_GENERATION");
        }
        return scopes.stream().limit(4).toList();
    }

    private boolean suggestsAnalysisDiagnosticFallback(String question, List<String> areas) {
        String normalized = normalizeQuestionText(splitIdentifierTerms(String.join(" ",
                safe(question, ""),
                String.join(" ", areas == null ? List.of() : areas)
        )));
        if (normalized.isBlank() || requiredAnalysisDiagnosticLanguages(normalized).isEmpty()) {
            return false;
        }
        boolean analysisTerm = containsRoleTerms(normalized,
                "graph", "semantic", "analysis", "analyzer", "diagnostic", "diagnostics", "parser", "parse");
        boolean fallbackTerm = containsRoleTerms(normalized,
                "fallback", "fallbacks", "fail", "failed", "failure", "partial", "skipped", "unavailable", "exception");
        return analysisTerm && fallbackTerm;
    }

    private boolean selectedHasFallbackScopeCoverage(String scope, String area, List<CodeSearchResult> selected) {
        Set<String> requiredLanguages = requiredAnalysisDiagnosticLanguages(area);
        return selected.stream().anyMatch(result -> {
            boolean scopeMatches = scope.equals(fallbackScope(result)) || hasLlmPlannedFallbackScope(scope, result);
            if (!scopeMatches) {
                return false;
            }
            if (!"GRAPH_ANALYSIS".equals(scope) || requiredLanguages.isEmpty()) {
                return true;
            }
            return requiredLanguages.contains(analysisDiagnosticLanguage(result));
        });
    }

    private boolean hasLlmPlannedFallbackScope(String scope, CodeSearchResult result) {
        if (scope == null || scope.isBlank() || result == null || result.metadata() == null) {
            return false;
        }
        Object plannedScope = result.metadata().get("llmCoverageFallbackScope");
        return scope.equals(String.valueOf(plannedScope));
    }

    private boolean hasAnyRequiredFallbackScope(CodeSearchResult result, Set<String> requiredFallbackScopes) {
        if (requiredFallbackScopes == null || requiredFallbackScopes.isEmpty()) {
            return false;
        }
        String scope = fallbackScope(result);
        return requiredFallbackScopes.contains(scope)
                || requiredFallbackScopes.stream().anyMatch(requiredScope -> hasLlmPlannedFallbackScope(requiredScope, result));
    }

    private double fallbackScopeCoverageScore(String area, String scope, CodeSearchResult result) {
        if (result == null || scope == null || scope.isBlank() || !scope.equals(fallbackScope(result))) {
            return 0;
        }
        double score = 10.0 + areaCoverageScore(area, result);
        if (isMainImplementationEvidence(result, false)) {
            score += 1.5;
        }
        if (isStructuredEvidenceCandidate(result, "")) {
            score += 1.0;
        }
        String status = analysisDiagnosticStatus(result);
        if ("FAILED".equals(status) || "PARTIAL".equals(status) || "SKIPPED".equals(status)) {
            score += 1.0;
        }
        score += analysisDiagnosticAffinityScore(area, result);
        return score;
    }

    private double analysisDiagnosticAffinityScore(String area, CodeSearchResult result) {
        Set<String> requiredLanguages = requiredAnalysisDiagnosticLanguages(area);
        if (requiredLanguages.isEmpty() || !"GRAPH_ANALYSIS".equals(fallbackScope(result))) {
            return 0;
        }
        String language = analysisDiagnosticLanguage(result);
        if (language.isBlank()) {
            return 0;
        }
        return requiredLanguages.contains(language) ? 3.0 : -0.75;
    }

    private Set<String> requiredAnalysisDiagnosticLanguages(String text) {
        String normalized = normalizeQuestionText(splitIdentifierTerms(text));
        if (normalized.isBlank()) {
            return Set.of();
        }
        Set<String> terms = java.util.Arrays.stream(normalized.split("\\s+"))
                .map(String::trim)
                .filter(term -> !term.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean javascript = terms.contains("javascript")
                || containsRoleTerms(normalized, "java script", "typescript", "node js", "nodejs");
        LinkedHashSet<String> languages = new LinkedHashSet<>();
        if (!javascript && (terms.contains("java")
                || containsRoleTerms(normalized, "spring", "javaparser", "java parser", "jpa", "hibernate"))) {
            languages.add("java");
        }
        if (terms.contains("csharp")
                || terms.contains("dotnet")
                || containsRoleTerms(normalized, "c#", "c sharp", "roslyn", "wpf", "winforms", "windows forms", "xaml", "designer cs")) {
            languages.add("csharp");
        }
        return languages;
    }

    private List<String> requiredEvidenceRoles(String area) {
        String normalized = normalizeQuestionText(splitIdentifierTerms(area));
        if (normalized.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> roles = new LinkedHashSet<>();
        if (containsRoleTerms(normalized, "indexing", "index", "chunk generation", "chunks", "file scan", "scan", "parser", "parse", "embedding")) {
            roles.add("indexing/pipeline");
        }
        if (containsRoleTerms(normalized, "search", "retrieval", "retrieve", "query", "expand", "expansion", "related chunks")) {
            roles.add("retrieval/search-expansion");
        }
        boolean graphTerm = containsRoleTerms(normalized, "graph");
        boolean edgeTraversalTerm = containsRoleTerms(normalized, "path", "depth", "traversal", "neighbor", "neighbors", "hop", "direction");
        if ((graphTerm && edgeTraversalTerm) || containsRoleTerms(normalized, "graph traversal", "graph path", "path score", "graph depth")) {
            roles.add("graph-traversal/expansion");
        }
        if (containsRoleTerms(normalized, "storage", "persist", "merge", "save", "table", "graph storage", "code graph storage")) {
            roles.add("graph-storage");
        }
        if (graphTerm && containsRoleTerms(normalized, "build", "builder", "analyze", "analysis", "diagnostic", "diagnostics", "parser", "parse")) {
            roles.add("graph-build/analysis");
        }
        if (containsRoleTerms(normalized, "ranking", "rank", "rerank", "score", "scoring", "weight", "evidence ranking")) {
            roles.add("evidence-ranking");
        }
        if (containsRoleTerms(normalized, "answer", "context", "generation", "citation", "prompt", "response")) {
            roles.add("answer-context/generation");
        }
        return roles.stream().limit(3).toList();
    }

    private Set<String> coverageTerms(String text) {
        String normalized = normalizeQuestionText(splitIdentifierTerms(text));
        if (normalized.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(normalized.split("\\s+"))
                .map(String::trim)
                .filter(term -> term.length() >= 3)
                .filter(term -> !COVERAGE_STOP_WORDS.contains(term))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String splitIdentifierTerms(String value) {
        return safe(value, "")
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replace('_', ' ')
                .replace('-', ' ')
                .replace('/', ' ')
                .replace('.', ' ');
    }

    private int weakestLlmCoverageIndex(List<String> areas, List<CodeSearchResult> selected) {
        return weakestLlmCoverageIndex(areas, Set.of(), selected);
    }

    private int weakestLlmCoverageIndex(List<String> areas, Set<String> requiredFallbackScopes, List<CodeSearchResult> selected) {
        for (int index = selected.size() - 1; index >= 0; index--) {
            CodeSearchResult result = selected.get(index);
            if (!isRequiredConversationPinned(result)
                    && !hasAnyRequiredFallbackScope(result, requiredFallbackScopes)
                    && isLowValueLlmCoverageEvidence(areas, result)) {
                return index;
            }
        }
        for (int index = selected.size() - 1; index >= 0; index--) {
            CodeSearchResult result = selected.get(index);
            if (!isRequiredConversationPinned(result)
                    && !hasAnyRequiredFallbackScope(result, requiredFallbackScopes)) {
                return index;
            }
        }
        return -1;
    }

    private int weakestRequiredRoleCoverageIndex(Set<String> requiredRoles, List<String> areas, List<CodeSearchResult> selected) {
        return weakestRequiredCoverageIndex(requiredRoles, Set.of(), areas, selected);
    }

    private int weakestRequiredCoverageIndex(
            Set<String> requiredRoles,
            Set<String> requiredFallbackScopes,
            List<String> areas,
            List<CodeSearchResult> selected
    ) {
        for (int index = selected.size() - 1; index >= 0; index--) {
            CodeSearchResult result = selected.get(index);
            if (!isRequiredConversationPinned(result)
                    && !hasAnyLlmCoverageRole(result)
                    && !hasAnyRequiredFallbackScope(result, requiredFallbackScopes)
                    && evidenceRoles(result).stream().noneMatch(requiredRoles::contains)) {
                return index;
            }
        }
        return weakestLlmCoverageIndex(areas, requiredFallbackScopes, selected);
    }

    private boolean hasAnyLlmCoverageRole(CodeSearchResult result) {
        return result != null && result.metadata() != null && result.metadata().containsKey("llmCoverageRole");
    }

    private boolean isLowValueLlmCoverageEvidence(List<String> areas, CodeSearchResult result) {
        if (result == null) {
            return true;
        }
        String sourceRole = CodeSourceClassifier.sourceRole(result);
        if (CodeSourceClassifier.SOURCE_TEST.equals(sourceRole)
                || CodeSourceClassifier.SOURCE_DOCS.equals(sourceRole)
                || CodeSourceClassifier.SOURCE_GENERATED.equals(sourceRole)
                || CodeSourceClassifier.isLocalAgentEvidence(result)) {
            return true;
        }
        if (areas.stream().anyMatch(area -> hasLlmPlannedCoverageArea(area, result))) {
            return false;
        }
        if (result.metadata() != null && result.metadata().containsKey("llmCoverageRole")) {
            return false;
        }
        return areas.stream().noneMatch(area -> hasAreaCoverage(area, result));
    }

    private CodeSearchResult markLlmPlannedCoverage(CodeSearchResult result, String area) {
        return markLlmPlannedCoverage(result, area, null);
    }

    private CodeSearchResult markLlmPlannedCoverage(CodeSearchResult result, String area, String role) {
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
        metadata.put("llmCoverageArea", area);
        metadata.put("llmCoverageRequired", true);
        metadata.put("llmCoverageReason", "llm evidence coverage plan");
        if (role != null && !role.isBlank()) {
            metadata.put("llmCoverageRole", role);
        }
        String fallbackScope = fallbackScope(result);
        if (!fallbackScope.isBlank()) {
            metadata.put("llmCoverageFallbackScope", fallbackScope);
        }
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
                result.score(),
                metadata
        );
    }

    private CodeSearchResult markLlmPlannedFallbackScopeCoverage(CodeSearchResult result, String area, String scope) {
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
        metadata.put("llmCoverageArea", area);
        metadata.put("llmCoverageRequired", true);
        metadata.put("llmCoverageReason", "llm evidence coverage plan");
        metadata.put("llmCoverageFallbackScope", scope);
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
                result.score(),
                metadata
        );
    }

    private boolean llmEvidenceCoverageEnabled() {
        return properties.getCode().getGraph().isLlmEvidenceCoverageEnabled();
    }

    private List<CodeSearchResult> sourceAwareEvidenceSelection(
            CodeQuestionMode questionMode,
            String question,
            List<CodeSearchResult> ranked,
            List<CodeSearchResult> selected,
            int limit
    ) {
        List<CodeSearchResult> adjusted = new ArrayList<>(selected == null ? List.of() : selected);
        if (ranked == null || ranked.isEmpty() || adjusted.isEmpty() || asksForTests(question)) {
            return adjusted;
        }
        boolean localAgentQuestion = asksForLocalAgent(question);
        if (!localAgentQuestion) {
            adjusted.removeIf(result -> !isRequiredConversationPinned(result)
                    && CodeSourceClassifier.isLocalAgentEvidence(result)
                    && replacementAvailable(ranked, adjusted, question, localAgentQuestion));
        }
        int requiredMain = switch (questionMode) {
            case OVERVIEW, CALL_FLOW, REASONING, IMPACT -> Math.min(2, Math.max(1, limit));
            default -> 1;
        };
        while (mainImplementationCount(adjusted, localAgentQuestion) < requiredMain) {
            CodeSearchResult replacement = ranked.stream()
                    .filter(result -> !containsChunk(adjusted, result))
                    .filter(result -> isMainImplementationEvidence(result, localAgentQuestion))
                    .findFirst()
                    .orElse(null);
            if (replacement == null) {
                break;
            }
            int replaceIndex = weakestAuxiliaryIndex(adjusted);
            if (replaceIndex < 0) {
                if (adjusted.size() < limit) {
                    adjusted.add(replacement);
                    continue;
                }
                break;
            }
            adjusted.set(replaceIndex, replacement);
        }
        return limitedMutable(adjusted, limit);
    }

    private List<CodeSearchResult> limitedMutable(List<CodeSearchResult> results, int limit) {
        return results.stream().limit(limit).collect(Collectors.toCollection(ArrayList::new));
    }

    private boolean replacementAvailable(List<CodeSearchResult> ranked, List<CodeSearchResult> selected, String question, boolean localAgentQuestion) {
        return ranked.stream()
                .anyMatch(result -> !containsChunk(selected, result) && isMainImplementationEvidence(result, localAgentQuestion));
    }

    private int mainImplementationCount(List<CodeSearchResult> results, boolean localAgentQuestion) {
        return (int) results.stream()
                .filter(result -> isMainImplementationEvidence(result, localAgentQuestion))
                .count();
    }

    private boolean isMainImplementationEvidence(CodeSearchResult result, boolean localAgentQuestion) {
        if (result == null || isProjectContext(result.chunkType())) {
            return false;
        }
        if (!localAgentQuestion && CodeSourceClassifier.isLocalAgentEvidence(result)) {
            return false;
        }
        return CodeSourceClassifier.SOURCE_MAIN.equals(CodeSourceClassifier.sourceRole(result));
    }

    private int weakestAuxiliaryIndex(List<CodeSearchResult> selected) {
        for (int index = selected.size() - 1; index >= 0; index--) {
            CodeSearchResult result = selected.get(index);
            if (!isRequiredConversationPinned(result)
                    && (CodeSourceClassifier.SOURCE_TEST.equals(CodeSourceClassifier.sourceRole(result))
                    || CodeSourceClassifier.SOURCE_DOCS.equals(CodeSourceClassifier.sourceRole(result))
                    || CodeSourceClassifier.SOURCE_GENERATED.equals(CodeSourceClassifier.sourceRole(result))
                    || CodeSourceClassifier.SOURCE_VENDOR.equals(CodeSourceClassifier.sourceRole(result))
                    || CodeSourceClassifier.isLocalAgentEvidence(result))) {
                return index;
            }
        }
        for (int index = selected.size() - 1; index >= 0; index--) {
            if (!isRequiredConversationPinned(selected.get(index)) && !isMainImplementationEvidence(selected.get(index), false)) {
                return index;
            }
        }
        return -1;
    }

    private boolean containsChunk(List<CodeSearchResult> results, CodeSearchResult candidate) {
        return candidate != null && results.stream().anyMatch(result -> result.chunkId().equals(candidate.chunkId()));
    }

    private boolean asksForTests(String question) {
        String normalized = normalizeQuestionText(question);
        return normalized.contains("test")
                || normalized.contains("테스트")
                || normalized.contains("spec")
                || normalized.contains("coverage")
                || normalized.contains("검증");
    }

    private boolean asksForLocalAgent(String question) {
        String normalized = normalizeQuestionText(question);
        return normalized.contains("local agent")
                || normalized.contains("localagent")
                || normalized.contains("agent")
                || normalized.contains("에이전트")
                || normalized.contains("patch")
                || normalized.contains("tool")
                || normalized.contains("mutation");
    }

    private String normalizeQuestionText(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsHangul}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
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
        if (questionMode == CodeQuestionMode.REASONING) {
            return List.of(
                    base + " design intent rationale responsibility tradeoff",
                    "implementation reason design intent responsibility related callers dependencies",
                    "구현 의도 설계 이유 책임 관계 호출 영향 근거"
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
        return buildContext(question, questionMode, results, true);
    }

    private String buildContext(String question, CodeQuestionMode questionMode, List<CodeSearchResult> results, boolean allowFullCoreEvidence) {
        if (results.isEmpty()) {
            return "No source-code context retrieved.";
        }
        int maxChars = questionMode == CodeQuestionMode.OVERVIEW
                ? OVERVIEW_CONTEXT_CHARS
                : questionMode == CodeQuestionMode.REASONING ? REASONING_CONTEXT_CHARS : DEFAULT_CONTEXT_CHARS;
        String evidenceValidation = evidenceValidationContext(results);
        String context = IntStream.range(0, results.size())
                .mapToObj(index -> {
                    CodeSearchResult result = results.get(index);
                    CodeExcerpt excerpt = codeExcerptInfo(question, result, contextCharsFor(question, questionMode, result, index, maxChars, allowFullCoreEvidence));
                    return "[" + (index + 1) + "] "
                            + result.filePath() + ":" + result.lineStart() + "-" + result.lineEnd()
                            + " type=" + result.chunkType()
                            + nullable(" class=", result.className())
                            + nullable(" method=", result.methodName())
                            + nullable(" control=", result.controlName())
                            + nullable(" event=", result.eventName())
                            + evidenceRoleContext(result)
                            + evidencePhaseContext(result)
                            + evidenceResponsibilityContext(result)
                            + fallbackScopeContext(result)
                            + citationKindContext(result)
                            + executionOrderContext(result)
                            + analysisDiagnosticContext(result)
                            + graphContext(result)
                            + evidenceRankingContext(result)
                            + adjudicationClaimContext(result)
                            + excerptContext(result, excerpt)
                            + "\n" + excerpt.text();
                })
                .collect(Collectors.joining("\n\n"));
        return evidenceValidation.isBlank() ? context : evidenceValidation + "\n\n" + context;
    }

    private OllamaClient.ChatResult chatWithLimit(String systemPrompt, String userPrompt, int maxOutputTokens) {
        OllamaClient.ChatResult result = ollamaClient.chatResult(systemPrompt, userPrompt, maxOutputTokens);
        return result == null ? ollamaClient.chatResult(systemPrompt, userPrompt) : result;
    }

    private LengthContinuation continueLengthLimitedAnswer(
            String systemPrompt,
            String originalUserPrompt,
            String partialAnswer,
            CodeQuestionMode questionMode,
            int evidenceCount
    ) {
        StringBuilder combined = new StringBuilder(safe(partialAnswer, "").trim());
        OllamaClient.ChatResult lastResult = null;
        String doneReason = "length";
        int attempts = 0;
        while (attempts < 2 && isLengthStop(doneReason)) {
            attempts++;
            String continuationPrompt = continuationPrompt(originalUserPrompt, combined.toString(), attempts);
            OllamaClient.ChatResult continuation = chatWithLimit(
                    systemPrompt + "\nContinue incomplete answers instead of restarting them. Keep citations valid and finish the answer.",
                    continuationPrompt,
                    continuationOutputTokens(questionMode, attempts)
            );
            lastResult = mergeChatResults(lastResult, continuation, combined.toString());
            String addition = safe(continuation.content(), "").trim();
            if (addition.isBlank()) {
                break;
            }
            appendContinuation(combined, addition);
            doneReason = continuation.doneReason();
            if (qualityFailureReason(combined.toString(), evidenceCount, doneReason) == null) {
                break;
            }
        }
        if (lastResult == null || combined.toString().trim().equals(safe(partialAnswer, "").trim())) {
            return new LengthContinuation(partialAnswer, "length", null, false);
        }
        return new LengthContinuation(combined.toString().trim(), doneReason, lastResult, true);
    }

    private LengthContinuation continueLengthLimitedAnswerStreaming(
            String systemPrompt,
            String originalUserPrompt,
            StringBuilder streamedAnswer,
            CodeQuestionMode questionMode,
            int evidenceCount,
            CodeAnswerStreamSink streamSink
    ) {
        StringBuilder combined = new StringBuilder(safe(streamedAnswer.toString(), "").trim());
        OllamaClient.ChatResult lastResult = null;
        String doneReason = "length";
        int attempts = 0;
        while (attempts < 2 && isLengthStop(doneReason)) {
            attempts++;
            streamSink.onStatus("continuation_started", "답변이 길어 이어서 생성합니다.");
            String continuationPrompt = continuationPrompt(originalUserPrompt, combined.toString(), attempts);
            OllamaClient.ChatResult continuation = streamContinuation(
                    systemPrompt + "\nContinue incomplete answers instead of restarting them. Keep citations valid and finish the answer.",
                    continuationPrompt,
                    continuationOutputTokens(questionMode, attempts),
                    combined,
                    streamedAnswer,
                    streamSink
            );
            lastResult = mergeChatResults(lastResult, continuation, combined.toString());
            doneReason = continuation.doneReason();
            if (qualityFailureReason(combined.toString(), evidenceCount, doneReason) == null) {
                break;
            }
        }
        if (lastResult == null || combined.toString().trim().isBlank()) {
            return new LengthContinuation(streamedAnswer.toString().trim(), "length", null, false);
        }
        return new LengthContinuation(combined.toString().trim(), doneReason, lastResult, true);
    }

    private String continuationPrompt(String originalUserPrompt, String partialAnswer, int attempt) {
        String tail = continuationTail(partialAnswer);
        return originalUserPrompt
                + "\n\nThe previous answer was cut off because the model reached the output limit."
                + "\nDo not repeat completed sections. Continue from the exact point where it stopped."
                + "\nFinish the remaining explanation with citations such as [1]."
                + "\nThis is continuation attempt " + attempt + " of 2."
                + "\n\nOnly the tail of the partial answer is shown below. Continue after it; do not summarize or restart it:\n" + tail;
    }

    private String continuationTail(String partialAnswer) {
        String clean = safe(partialAnswer, "").trim();
        if (clean.length() <= 1800) {
            return clean;
        }
        return clean.substring(clean.length() - 1800);
    }

    private int continuationOutputTokens(CodeQuestionMode questionMode, int attempt) {
        int base = switch (questionMode) {
            case OVERVIEW, REASONING -> 1000;
            case CALL_FLOW, EXPLAIN_METHOD -> 900;
            case UI_EVENT, IMPACT -> 800;
            case LOCATE -> 600;
        };
        return attempt == 1 ? base : Math.max(500, base / 2);
    }

    private void appendContinuation(StringBuilder answer, String addition) {
        if (answer.isEmpty()) {
            answer.append(addition);
            return;
        }
        String cleanAddition = removeContinuationOverlap(answer.toString(), addition);
        if (cleanAddition.isBlank()) {
            return;
        }
        if (!answer.toString().endsWith("\n") && !cleanAddition.startsWith("\n")) {
            answer.append("\n\n");
        }
        answer.append(cleanAddition);
    }

    private String removeContinuationOverlap(String existing, String addition) {
        String cleanAddition = safe(addition, "").trim();
        String cleanExisting = safe(existing, "").trim();
        if (cleanAddition.isBlank() || cleanExisting.isBlank()) {
            return cleanAddition;
        }
        int max = Math.min(Math.min(cleanExisting.length(), cleanAddition.length()), 800);
        for (int length = max; length >= 40; length--) {
            String suffix = cleanExisting.substring(cleanExisting.length() - length);
            String prefix = cleanAddition.substring(0, length);
            if (normalizeContinuationBoundary(suffix).equals(normalizeContinuationBoundary(prefix))) {
                return cleanAddition.substring(length).trim();
            }
        }
        return cleanAddition;
    }

    private String normalizeContinuationBoundary(String value) {
        return safe(value, "").replaceAll("\\s+", " ").trim();
    }

    private OllamaClient.ChatResult mergeChatResults(OllamaClient.ChatResult previous, OllamaClient.ChatResult current, String existingContent) {
        if (current == null) {
            return previous;
        }
        if (previous == null) {
            return current;
        }
        return new OllamaClient.ChatResult(
                safe(existingContent, "") + "\n\n" + safe(current.content(), ""),
                current.doneReason(),
                current.done(),
                previous.promptEvalCount() + current.promptEvalCount(),
                previous.evalCount() + current.evalCount(),
                current.baseUrl(),
                current.model(),
                current.role(),
                previous.fallbackUsed() || current.fallbackUsed()
        );
    }

    private boolean isLengthStop(String doneReason) {
        return "length".equalsIgnoreCase(safe(doneReason, ""));
    }

    private OllamaClient.ChatResult stream(String systemPrompt, String userPrompt, CodeAnswerStreamSink streamSink, StringBuilder streamedAnswer, int maxOutputTokens) {
        AtomicReference<OllamaClient.ChatStreamDelta> finalDelta = new AtomicReference<>();
        ollamaClient.streamChat(systemPrompt, userPrompt, maxOutputTokens)
                .bufferTimeout(256, java.time.Duration.ofMillis(35))
                .filter(batch -> !batch.isEmpty())
                .doOnNext(batch -> {
                    StringBuilder next = new StringBuilder();
                    for (OllamaClient.ChatStreamDelta delta : batch) {
                        if (delta.done()) {
                            finalDelta.set(delta);
                        }
                        if (!delta.content().isEmpty()) {
                            streamedAnswer.append(delta.content());
                            next.append(delta.content());
                        }
                    }
                    if (!next.isEmpty()) {
                        streamSink.onDelta(next.toString());
                    }
                })
                .blockLast();
        OllamaClient.ChatStreamDelta done = finalDelta.get();
        return new OllamaClient.ChatResult(
                streamedAnswer.toString().trim(),
                done == null ? null : done.doneReason(),
                done == null || done.done(),
                done == null ? 0 : done.promptEvalCount(),
                done == null ? 0 : done.evalCount(),
                done == null ? "" : done.baseUrl(),
                done == null ? "" : done.model(),
                done == null ? "primary" : done.role(),
                done != null && done.fallbackUsed()
        );
    }

    private OllamaClient.ChatResult streamContinuation(
            String systemPrompt,
            String userPrompt,
            int maxOutputTokens,
            StringBuilder combined,
            StringBuilder streamedAnswer,
            CodeAnswerStreamSink streamSink
    ) {
        AtomicReference<OllamaClient.ChatStreamDelta> finalDelta = new AtomicReference<>();
        StringBuilder rawContinuation = new StringBuilder();
        StringBuilder visibleContinuation = new StringBuilder();
        String baseAnswer = combined.toString();
        ollamaClient.streamChat(systemPrompt, userPrompt, maxOutputTokens)
                .bufferTimeout(256, java.time.Duration.ofMillis(35))
                .filter(batch -> !batch.isEmpty())
                .doOnNext(batch -> {
                    StringBuilder next = new StringBuilder();
                    for (OllamaClient.ChatStreamDelta delta : batch) {
                        if (delta.done()) {
                            finalDelta.set(delta);
                        }
                        if (!delta.content().isEmpty()) {
                            rawContinuation.append(delta.content());
                            next.append(delta.content());
                        }
                    }
                    if (!next.isEmpty()) {
                        String visible = removeContinuationOverlap(baseAnswer, rawContinuation.toString());
                        if (visible.length() > visibleContinuation.length()) {
                            String delta = visible.substring(visibleContinuation.length());
                            if (!delta.isBlank()) {
                                String emittedDelta = delta;
                                if (visibleContinuation.isEmpty()) {
                                    if (!combined.toString().endsWith("\n") && !delta.startsWith("\n")) {
                                        emittedDelta = "\n\n" + delta;
                                    }
                                    appendContinuation(combined, delta);
                                } else {
                                    combined.append(delta);
                                }
                                visibleContinuation.append(delta);
                                streamedAnswer.setLength(0);
                                streamedAnswer.append(combined);
                                streamSink.onDelta(emittedDelta);
                            }
                        }
                    }
                })
                .blockLast();
        OllamaClient.ChatStreamDelta done = finalDelta.get();
        return new OllamaClient.ChatResult(
                rawContinuation.toString().trim(),
                done == null ? null : done.doneReason(),
                done == null || done.done(),
                done == null ? 0 : done.promptEvalCount(),
                done == null ? 0 : done.evalCount(),
                done == null ? "" : done.baseUrl(),
                done == null ? "" : done.model(),
                done == null ? "primary" : done.role(),
                done != null && done.fallbackUsed()
        );
    }

    private CodeContextBundle buildBudgetedContext(
            String question,
            CodeQuestionMode questionMode,
            String systemPrompt,
            String promptPrefix,
            List<CodeSearchResult> results,
            boolean compactForStreaming
    ) {
        List<CodeSearchResult> selected = new ArrayList<>(results == null ? List.of() : results);
        boolean allowFullCoreEvidence = true;
        String context = compactForStreaming
                ? buildStreamingContext(question, questionMode, selected, allowFullCoreEvidence)
                : buildContext(question, questionMode, selected, allowFullCoreEvidence);
        int budget = promptTokenBudget();
        int requiredCount = (int) selected.stream().filter(this::isRequiredConversationPinned).count();
        int minResults = Math.min(selected.size(), Math.max(requiredCount, isConversationPinned(selected) ? 1 : Math.min(2, selected.size())));
        if (allowFullCoreEvidence
                && estimateTokens(systemPrompt) + estimateTokens(promptPrefix) + estimateTokens(context) > budget) {
            allowFullCoreEvidence = false;
            context = compactForStreaming ? buildStreamingContext(question, questionMode, selected, false) : buildContext(question, questionMode, selected, false);
        }
        while (selected.size() > minResults
                && estimateTokens(systemPrompt) + estimateTokens(promptPrefix) + estimateTokens(context) > budget) {
            removeBudgetCandidate(selected);
            context = compactForStreaming ? buildStreamingContext(question, questionMode, selected, false) : buildContext(question, questionMode, selected, false);
        }
        int droppedCount = Math.max(0, (results == null ? 0 : results.size()) - selected.size());
        return new CodeContextBundle(List.copyOf(selected), context, droppedCount);
    }

    private String buildStreamingContext(String question, CodeQuestionMode questionMode, List<CodeSearchResult> results) {
        return buildStreamingContext(question, questionMode, results, true);
    }

    private String buildStreamingContext(String question, CodeQuestionMode questionMode, List<CodeSearchResult> results, boolean allowFullCoreEvidence) {
        if (results.isEmpty()) {
            return "No source-code context retrieved.";
        }
        int detailedLimit = Math.min(results.size(), detailedStreamingContextLimit(questionMode, results));
        int detailedChars = streamingDetailedContextChars(questionMode);
        int compactChars = streamingCompactContextChars(questionMode);
        String evidenceValidation = evidenceValidationContext(results);
        String context = IntStream.range(0, results.size())
                .mapToObj(index -> {
                    CodeSearchResult result = results.get(index);
                    boolean detailed = index < detailedLimit || isRequiredConversationPinned(result);
                    return detailed
                            ? streamingDetailedContextLine(question, questionMode, result, index, detailedChars, allowFullCoreEvidence)
                            : streamingCompactContextLine(question, result, index + 1, compactChars);
                })
                .collect(Collectors.joining("\n\n"));
        return evidenceValidation.isBlank() ? context : evidenceValidation + "\n\n" + context;
    }

    private int detailedStreamingContextLimit(CodeQuestionMode questionMode, List<CodeSearchResult> results) {
        int requiredCount = (int) results.stream().filter(this::isRequiredConversationPinned).count();
        int base = switch (questionMode) {
            case LOCATE -> 3;
            case OVERVIEW, REASONING, CALL_FLOW -> 5;
            case EXPLAIN_METHOD, UI_EVENT, IMPACT -> 4;
        };
        return Math.max(base, requiredCount);
    }

    private int streamingDetailedContextChars(CodeQuestionMode questionMode) {
        return switch (questionMode) {
            case LOCATE -> 520;
            case OVERVIEW -> 620;
            case REASONING, CALL_FLOW -> 900;
            case EXPLAIN_METHOD, UI_EVENT, IMPACT -> 820;
        };
    }

    private int streamingCompactContextChars(CodeQuestionMode questionMode) {
        return switch (questionMode) {
            case LOCATE -> 180;
            case OVERVIEW -> 220;
            case REASONING, CALL_FLOW -> 320;
            case EXPLAIN_METHOD, UI_EVENT, IMPACT -> 280;
        };
    }

    private String streamingDetailedContextLine(String question, CodeQuestionMode questionMode, CodeSearchResult result, int index, int maxChars, boolean allowFullCoreEvidence) {
        CodeExcerpt excerpt = codeExcerptInfo(question, result, contextCharsFor(question, questionMode, result, index, maxChars, allowFullCoreEvidence));
        return "[" + (index + 1) + "] " + compactCodeHeader(result)
                + evidenceRoleContext(result)
                + evidencePhaseContext(result)
                + evidenceResponsibilityContext(result)
                + fallbackScopeContext(result)
                + citationKindContext(result)
                + executionOrderContext(result)
                + analysisDiagnosticContext(result)
                + graphContext(result)
                + evidenceRankingContext(result)
                + adjudicationClaimContext(result)
                + excerptContext(result, excerpt)
                + "\n" + excerpt.text();
    }

    private String streamingCompactContextLine(String question, CodeSearchResult result, int citationNumber, int maxChars) {
        CodeExcerpt excerpt = codeExcerptInfo(question, result, maxChars);
        return "[" + citationNumber + "] " + compactCodeHeader(result)
                + excerptContext(result, excerpt)
                + "\nKey excerpt: " + excerpt.text();
    }

    private String compactCodeHeader(CodeSearchResult result) {
        return result.filePath() + ":" + result.lineStart() + "-" + result.lineEnd()
                + " type=" + result.chunkType()
                + nullable(" class=", result.className())
                + nullable(" method=", result.methodName())
                + nullable(" control=", result.controlName())
                + nullable(" event=", result.eventName());
    }

    private boolean isConversationPinned(List<CodeSearchResult> results) {
        return results != null && results.stream().anyMatch(this::isConversationPinned);
    }

    private void removeBudgetCandidate(List<CodeSearchResult> selected) {
        for (int index = selected.size() - 1; index >= 0; index--) {
            CodeSearchResult candidate = selected.get(index);
            if (!isConversationPinned(candidate)
                    && !isRequiredConversationPinned(candidate)
                    && !Boolean.TRUE.equals(metadataBoolean(candidate, "llmEvidenceSlateMustUse"))
                    && !Boolean.TRUE.equals(metadataBoolean(candidate, "llmChecklistGroupRequired"))) {
                selected.remove(index);
                return;
            }
        }
        for (int index = selected.size() - 1; index >= 0; index--) {
            CodeSearchResult candidate = selected.get(index);
            if (!isRequiredConversationPinned(candidate)
                    && !Boolean.TRUE.equals(metadataBoolean(candidate, "llmEvidenceSlateMustUse"))
                    && !Boolean.TRUE.equals(metadataBoolean(candidate, "llmChecklistGroupRequired"))) {
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

    private int maxOutputTokens(CodeQuestionMode questionMode) {
        int configured = pipelineService.maxOutputTokens();
        if (configured > 0) {
            return configured;
        }
        return 0;
    }

    private int repairOutputTokens(int maxOutputTokens) {
        return maxOutputTokens > 0 ? Math.min(maxOutputTokens, 700) : 700;
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
            case REASONING -> reasoningFallbackAnswer(results);
            case OVERVIEW -> overviewFallbackAnswer(results);
        };
    }

    private String reasoningFallbackAnswer(List<CodeSearchResult> results) {
        StringBuilder builder = new StringBuilder("검색된 코드 근거 기준으로 구현 의도/이유를 보수적으로 정리합니다.\n\n");
        builder.append("## 구현 의도 추정\n");
        for (int index = 0; index < Math.min(results.size(), 5); index++) {
            CodeSearchResult result = results.get(index);
            builder.append("- ").append(result.filePath()).append(":")
                    .append(result.lineStart()).append("-").append(result.lineEnd())
                    .append(fallbackSymbolText(result))
                    .append(" 근거상 이 코드는 `").append(category(result)).append("` 역할의 일부입니다 [")
                    .append(index + 1).append("].\n");
        }
        builder.append("\n## 주의점\n");
        builder.append("- 실제 설계 의도 문서나 커밋 메시지는 근거에 포함되지 않았으므로, 위 내용은 코드 구조에서 확인되는 범위의 추정입니다.\n");
        builder.append("- 더 정확한 판단이 필요하면 관련 호출 흐름이나 변경 영향 질문으로 범위를 좁혀 확인하세요.\n");
        return builder.toString();
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
                            responseEvidenceMetadata(result)
                    );
                })
                .toList();
    }

    private Map<String, Object> responseEvidenceMetadata(CodeSearchResult result) {
        Map<String, Object> metadata = new LinkedHashMap<>(evidenceRanker.responseMetadata(result.metadata()));
        if (hasLlmEvidenceClassification(result)) {
            String role = llmEvidenceResponsibility(result);
            if (!role.isBlank()) {
                metadata.put("evidenceRole", role);
            }
            List<String> phases = evidencePhases(result);
            if (!phases.isEmpty()) {
                metadata.put("evidencePhase", String.join("|", phases));
                metadata.put("executionOrder", executionOrder(phases));
            }
            metadata.put("citationKind", citationKind(result));
            metadata.put("evidenceResponsibility", role.isBlank() ? "unknown" : role);
        } else {
            List<String> heuristicRoles = evidenceRoles(result);
            if (!heuristicRoles.isEmpty()) {
                metadata.put("debugHeuristicEvidenceRole", String.join("|", heuristicRoles));
            }
            List<String> heuristicPhases = heuristicEvidencePhases(result);
            if (!heuristicPhases.isEmpty()) {
                metadata.put("debugHeuristicEvidencePhase", String.join("|", heuristicPhases));
                metadata.put("debugHeuristicExecutionOrder", executionOrder(heuristicPhases));
            }
            metadata.put("debugHeuristicCitationKind", heuristicCitationKind(result));
            metadata.put("debugHeuristicEvidenceResponsibility", heuristicEvidenceResponsibility(result));
            String fallbackScope = fallbackScope(result);
            if (!fallbackScope.isBlank()) {
                metadata.put("debugFallbackScope", fallbackScope);
            }
        }
        copyMetadata(result.metadata(), metadata,
                "llmCoverageArea",
                "llmCoverageRequired",
                "llmCoverageReason",
                "llmCoverageRole",
                "llmCoverageFallbackScope");
        String analysisDiagnosticStatus = directAnalysisDiagnosticStatus(result);
        if (!analysisDiagnosticStatus.isBlank()) {
            metadata.put("analysisDiagnosticStatus", analysisDiagnosticStatus);
            metadata.put("analysisDiagnosticScope", directAnalysisDiagnosticScope(result));
            String stage = directAnalysisDiagnosticStage(result);
            if (!stage.isBlank()) {
                metadata.put("analysisDiagnosticStage", stage);
            }
            String language = directAnalysisDiagnosticLanguage(result);
            if (!language.isBlank()) {
                metadata.put("analysisDiagnosticLanguage", language);
            }
            String analyzer = directAnalysisDiagnosticAnalyzer(result);
            if (!analyzer.isBlank()) {
                metadata.put("analysisDiagnosticAnalyzer", analyzer);
            }
        }
        return Map.copyOf(metadata);
    }

    private void copyMetadata(Map<String, Object> source, Map<String, Object> target, String... keys) {
        if (source == null || target == null || keys == null) {
            return;
        }
        for (String key : keys) {
            if (key != null && source.containsKey(key)) {
                target.put(key, source.get(key));
            }
        }
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
            String answer,
            String doneReason,
            boolean llmUnavailable,
            boolean answerRewritten,
            boolean answerRetried,
            boolean answerContinued,
            boolean answerKeptAfterStreamValidation,
            AnswerQualityTrace answerQualityTrace,
            CodeRetrieval retrieval,
            int contextBudgetDropped
    ) {
        List<String> notes = new ArrayList<>(diagnostics(results, answerResults, llmUnavailable, answerRewritten && !answerKeptAfterStreamValidation));
        CitationQuality citationQuality = citationQuality(answer, answerResults);
        notes.add("RAG quality trace: answerChars=" + safe(answer, "").length()
                + ", citedReferences=" + citationQuality.referencedCount()
                + ", invalidCitationRefs=" + citationQuality.invalidCount()
                + ", citationCoverage=" + citationQuality.coveragePercent() + "%"
                + ", fallback=" + (llmUnavailable || (answerRewritten && !answerKeptAfterStreamValidation))
                + ", retry=" + answerRetried
                + ", continuation=" + answerContinued
                + ", doneReason=" + safe(doneReason, "none") + ".");
        if (answerQualityTrace != null && answerQualityTrace.observed()) {
            notes.add(answerQualityTrace.summary());
        }
        if (!citationQuality.summary().isBlank()) {
            notes.add("Citation support: " + citationQuality.summary());
        }
        notes.add(codeEvidenceSelectionSummary(answerResults, contextBudgetDropped));
        if (retrieval != null && retrieval.deterministicPlan() != null) {
            CodeQueryPlan plan = retrieval.deterministicPlan();
            notes.add("Code query planner: intent=" + plan.intent()
                    + ", queryCount=" + plan.queries().size()
                    + ", auxiliaryQueries=" + Math.max(0, plan.queries().size() - 1)
                    + ", originalOnlyFallback=" + plan.originalOnlyFallback() + ".");
        }
        if (questionMode == CodeQuestionMode.OVERVIEW || questionMode == CodeQuestionMode.CALL_FLOW || questionMode == CodeQuestionMode.IMPACT || questionMode == CodeQuestionMode.REASONING) {
            long projectContext = answerResults.stream().filter(result -> isProjectContext(result.chunkType())).count();
            long distinctFiles = answerResults.stream().map(CodeSearchResult::filePath).distinct().count();
            notes.add("Code question mode was classified as " + questionMode.name()
                    + "; answer context used " + projectContext + " project context chunks and "
                    + distinctFiles + " distinct files.");
        }
        if (retrieval != null && retrieval.iteration() > 1) {
            notes.add("RAG pipeline ran one LLM-planned follow-up retrieval and merged it with the initial evidence.");
        }
        if (retrieval != null && retrieval.followUpPlan() != null) {
            RagPipelineService.CodeEvidenceFollowUpPlan plan = retrieval.followUpPlan();
            notes.add("Code evidence follow-up planner: attempted=" + plan.attempted()
                    + ", enough=" + plan.enough()
                    + ", followUpQueriesUsed=" + retrieval.followUpQueriesUsed()
                    + ", followUpCandidatesAdded=" + retrieval.followUpCandidateCount()
                    + ", followUpSelected=" + answerResults.stream().filter(this::isLlmFollowUpEvidence).count()
                    + ", missingAreas=" + plan.missingAreas()
                    + ", queryAreas=" + plan.queryAreas()
                    + ", reason=" + safe(plan.reason(), "") + ".");
        }
        if (retrieval != null && retrieval.queryPlan() != null) {
            RagPipelineService.QueryPlan plan = retrieval.queryPlan();
            notes.add("Code query rewrite status: attempted=" + plan.rewriteAttempted()
                    + ", used=" + plan.rewriteUsed()
                    + ", failed=" + plan.rewriteFailed()
                    + ", reason=" + plan.reason()
                    + ", queryCount=" + plan.queries().size() + ".");
        }
        if (retrieval != null && retrieval.queryPlan().rewriteUsed()) {
            notes.add("RAG pipeline used LLM-planned query expansion as an auxiliary code retrieval signal.");
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
        if (answerContinued) {
            notes.add("Answer generation reached the model output limit and was automatically continued before returning.");
        }
        if (answerKeptAfterStreamValidation) {
            notes.add("Streaming answer was kept after self-check flagged the final text; review citations and confidence before relying on it.");
        }
        return notes;
    }

    private String codeEvidenceSelectionSummary(List<CodeSearchResult> answerResults, int contextBudgetDropped) {
        List<CodeSearchResult> safeResults = answerResults == null ? List.of() : answerResults;
        Map<String, Long> typeCounts = safeResults.stream()
                .map(result -> safe(result.chunkType(), "unknown"))
                .collect(Collectors.groupingBy(type -> type.isBlank() ? "unknown" : type, LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> sourceRoles = safeResults.stream()
                .map(CodeSourceClassifier::sourceRole)
                .collect(Collectors.groupingBy(role -> role == null || role.isBlank() ? "unknown" : role, LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> runtimeRoles = safeResults.stream()
                .map(CodeSourceClassifier::runtimeRole)
                .collect(Collectors.groupingBy(role -> role == null || role.isBlank() ? "unknown" : role, LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> parsers = safeResults.stream()
                .map(this::parserName)
                .collect(Collectors.groupingBy(parser -> parser.isBlank() ? "unknown" : parser, LinkedHashMap::new, Collectors.counting()));
        long structured = safeResults.stream().filter(result -> isStructured(result.chunkType())).count();
        long fallbackLineWindows = safeResults.stream().filter(this::isLineWindowEvidence).count();
        int structuredPercent = safeResults.isEmpty() ? 0 : (int) Math.round((structured * 100.0) / safeResults.size());
        long graphExpanded = safeResults.stream().filter(this::isGraphExpanded).count();
        long required = safeResults.stream().filter(this::isRequiredConversationPinned).count();
        long llmAdjudicated = safeResults.stream()
                .filter(result -> result.metadata() != null && Boolean.TRUE.equals(result.metadata().get("llmEvidenceAdjudicationSelected")))
                .count();
        long llmFollowUp = safeResults.stream().filter(this::isLlmFollowUpEvidence).count();
        return "Evidence selection: selected=" + safeResults.size()
                + ", budgetDropped=" + Math.max(0, contextBudgetDropped)
                + ", chunkTypes=" + typeCounts
                + ", sourceRoles=" + sourceRoles
                + ", runtimeRoles=" + runtimeRoles
                + ", parsers=" + parsers
                + ", structured=" + structured + "/" + safeResults.size() + " (" + structuredPercent + "%)"
                + ", lineWindowFallback=" + fallbackLineWindows
                + ", graphExpanded=" + graphExpanded
                + ", requiredPinned=" + required
                + ", llmAdjudicated=" + llmAdjudicated
                + ", llmFollowUp=" + llmFollowUp + ".";
    }

    private String parserName(CodeSearchResult result) {
        if (result == null || result.metadata() == null) {
            return "";
        }
        Object parser = result.metadata().getOrDefault("parser", result.metadata().get("strategy"));
        return parser == null ? "" : String.valueOf(parser);
    }

    private boolean isLineWindowEvidence(CodeSearchResult result) {
        return "line_window".equals(parserName(result));
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

    private CitationQuality citationQuality(String answer, List<CodeSearchResult> evidence) {
        String safeAnswer = safe(answer, "");
        List<CodeSearchResult> safeEvidence = evidence == null ? List.of() : evidence;
        Set<Integer> referenced = citationReferences(safeAnswer);
        long invalid = referenced.stream()
                .filter(index -> index < 1 || index > safeEvidence.size())
                .count();
        List<String> claims = claimSegments(safeAnswer);
        long citedClaims = claims.stream().filter(this::containsCitation).count();
        long weakSupport = claims.stream()
                .filter(this::containsCitation)
                .filter(claim -> !citationClaimSupported(claim, safeEvidence))
                .count();
        int coverage = claims.isEmpty() ? (referenced.isEmpty() ? 0 : 100) : (int) Math.round((100.0 * citedClaims) / claims.size());
        StringBuilder summary = new StringBuilder();
        if (invalid > 0) {
            summary.append(invalid).append(" citation reference(s) point outside returned evidence.");
        }
        if (weakSupport > 0) {
            if (!summary.isEmpty()) {
                summary.append(" ");
            }
            summary.append(weakSupport).append(" cited claim(s) have weak lexical support in their cited code evidence.");
        }
        if (summary.isEmpty() && !referenced.isEmpty()) {
            summary.append("All cited references point to returned evidence; weakSupport=").append(weakSupport).append(".");
        }
        return new CitationQuality(referenced.size(), (int) invalid, coverage, summary.toString());
    }

    private Set<Integer> citationReferences(String answer) {
        Set<Integer> values = new HashSet<>();
        Matcher matcher = Pattern.compile("\\[(\\d+)]").matcher(safe(answer, ""));
        while (matcher.find()) {
            try {
                values.add(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // Regex keeps this numeric, but keep parsing defensive.
            }
        }
        return values;
    }

    private List<String> claimSegments(String answer) {
        String normalized = safe(answer, "").replace('\r', '\n');
        return Pattern.compile("[\\n.!?]+")
                .splitAsStream(normalized)
                .map(String::trim)
                .filter(segment -> segment.length() >= 18)
                .filter(segment -> segment.matches("(?s).*[\\p{L}\\p{N}].*"))
                .limit(40)
                .toList();
    }

    private boolean citationClaimSupported(String claim, List<CodeSearchResult> evidence) {
        Set<Integer> refs = citationReferences(claim);
        if (refs.isEmpty()) {
            return false;
        }
        Set<String> claimTerms = supportTerms(claim);
        if (claimTerms.isEmpty()) {
            return true;
        }
        for (Integer ref : refs) {
            if (ref == null || ref < 1 || ref > evidence.size()) {
                return false;
            }
            Set<String> evidenceTerms = supportTerms(evidence.get(ref - 1).content());
            long overlap = claimTerms.stream().filter(evidenceTerms::contains).count();
            if (overlap >= Math.min(2, claimTerms.size())) {
                return true;
            }
        }
        return false;
    }

    private Set<String> supportTerms(String value) {
        Set<String> terms = new HashSet<>();
        String normalized = safe(value, "").toLowerCase(Locale.ROOT).replaceAll("\\[\\d+]", " ");
        Matcher matcher = Pattern.compile("[\\p{L}\\p{N}_-]{3,}").matcher(normalized);
        while (matcher.find() && terms.size() < 32) {
            String term = matcher.group();
            if (!isCitationStopWord(term)) {
                terms.add(term);
            }
        }
        return terms;
    }

    private boolean isCitationStopWord(String term) {
        return Set.of(
                "the", "and", "for", "that", "this", "with", "from", "into", "also", "then",
                "when", "where", "what", "how", "why", "are", "was", "were", "has", "have",
                "public", "private", "class", "void", "return", "string", "있습니다", "합니다"
        ).contains(term);
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

    private int contextCharsFor(String question, CodeQuestionMode questionMode, CodeSearchResult result, int index, int defaultMaxChars, boolean allowFullCoreEvidence) {
        if (!allowFullCoreEvidence || index > 0 || !isCoreFullContextCandidate(question, questionMode, result)) {
            return defaultMaxChars;
        }
        String content = safe(result == null ? "" : result.content(), "");
        if (content.isBlank()) {
            return defaultMaxChars;
        }
        return Math.max(defaultMaxChars, content.length());
    }

    private boolean isCoreFullContextCandidate(String question, CodeQuestionMode questionMode, CodeSearchResult result) {
        if (result == null || !isDirectCodeEvidence(result) || !isImplementationFlowQuestion(question, questionMode)) {
            return false;
        }
        String symbol = firstNonBlank(result.methodName(), result.symbolName(), result.className(), result.controlName(), result.eventName());
        if (symbol.isBlank()) {
            return false;
        }
        String identity = normalizeCodeText(splitIdentifierTerms(String.join(" ", symbol, safe(result.filePath(), ""))));
        String query = normalizeCodeText(splitIdentifierTerms(question));
        Set<String> identityTerms = coverageTerms(identity);
        Set<String> queryTerms = coverageTerms(query);
        return identityTerms.stream().anyMatch(query::contains) || queryTerms.stream().anyMatch(identity::contains);
    }

    private String excerptContext(CodeSearchResult result, CodeExcerpt excerpt) {
        if (excerpt == null) {
            return "";
        }
        return " excerptKind=" + excerpt.kind()
                + " contentComplete=" + excerpt.contentComplete()
                + " omittedByBudget=" + excerpt.omittedByBudget()
                + " sourceLines=" + resultLineStart(result) + "-" + resultLineEnd(result)
                + " excerptLines=" + excerpt.excerptLineStart() + "-" + excerpt.excerptLineEnd();
    }

    private String codeExcerpt(String question, CodeSearchResult result, int maxChars) {
        return codeExcerptInfo(question, result, maxChars).text();
    }

    private CodeExcerpt codeExcerptInfo(String question, CodeSearchResult result, int maxChars) {
        EvidenceExcerptSelector.Excerpt excerpt = EvidenceExcerptSelector.select(question, result, maxChars);
        return new CodeExcerpt(excerpt.text(), excerpt.kind(), excerpt.contentComplete(), excerpt.omittedByBudget(),
                excerpt.lineStart(), excerpt.lineEnd());
    }

    private int resultLineStart(CodeSearchResult result) {
        return result == null ? 0 : Math.max(0, result.lineStart());
    }

    private int resultLineEnd(CodeSearchResult result) {
        return result == null ? 0 : Math.max(resultLineStart(result), result.lineEnd());
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
        if (isReasoningIntent(normalized)) {
            terms.addAll(List.of("design", "intent", "reason", "rationale", "service", "controller", "repository", "config", "의도", "이유", "설계"));
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

    private CodeSearchResult markLlmFollowUpEvidence(CodeSearchResult result, String query) {
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
        metadata.put("llmFollowUpEvidence", true);
        metadata.put("llmFollowUpQuery", safe(query, ""));
        metadata.put("evidenceRankReason", String.valueOf(metadata.getOrDefault("evidenceRankReason", ""))
                + (metadata.containsKey("evidenceRankReason") ? "; " : "")
                + "Selected by LLM-planned follow-up retrieval");
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
                result.score() + 0.12,
                Map.copyOf(metadata)
        );
    }

    private boolean isLlmFollowUpEvidence(CodeSearchResult result) {
        return result != null
                && result.metadata() != null
                && Boolean.TRUE.equals(result.metadata().get("llmFollowUpEvidence"));
    }

    private String selectedPathSummary(List<CodeSearchResult> results) {
        return (results == null ? List.<CodeSearchResult>of() : results).stream()
                .limit(12)
                .map(result -> safe(result.filePath(), "")
                        + (safe(result.methodName(), "").isBlank() ? "" : "#" + result.methodName())
                        + (isLlmEvidenceAdjudicationSelected(result) ? "[llm-rank=" + llmEvidenceSlateRank(result) + "]" : "")
                        + (isLlmFollowUpEvidence(result) ? "[follow-up]" : ""))
                .collect(Collectors.joining("; "));
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
                || "function".equals(chunkType)
                || "constructor".equals(chunkType)
                || "record".equals(chunkType)
                || "enum".equals(chunkType)
                || "component".equals(chunkType)
                || "event_handler".equals(chunkType)
                || "xaml_event".equals(chunkType)
                || "xaml_view".equals(chunkType)
                || "xaml_binding".equals(chunkType)
                || "xaml_control".equals(chunkType)
                || "winforms_control".equals(chunkType)
                || "view_model".equals(chunkType)
                || "command".equals(chunkType)
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
        Object evidenceKind = result.metadata().get("graphEvidenceKind");
        Object graphConfidence = result.metadata().get("graphConfidence");
        Object confidenceReason = result.metadata().get("confidenceReason");
        Object sourceDetail = result.metadata().get("sourceDetail");
        String kind = safe(evidenceKind == null ? null : String.valueOf(evidenceKind), "direct");
        return " graphEvidence=" + kind
                + " graphEdge=" + safe(edgeType == null ? null : String.valueOf(edgeType), "RELATED")
                + nullable(" edges=", edgeTypes == null ? null : String.valueOf(edgeTypes))
                + nullable(" depth=", depth == null ? null : String.valueOf(depth))
                + nullable(" confidence=", graphConfidence == null ? null : String.valueOf(graphConfidence))
                + nullable(" confidenceReason=", confidenceReason == null ? null : String.valueOf(confidenceReason))
                + nullable(" sourceDetail=", sourceDetail == null ? null : truncate(String.valueOf(sourceDetail), 120))
                + nullable(" path=", graphPath == null ? null : String.valueOf(graphPath));
    }

    private String evidenceRoleContext(CodeSearchResult result) {
        String role = llmEvidenceResponsibility(result);
        return role.isBlank() ? "" : " evidenceRole=" + role;
    }

    private String evidencePhaseContext(CodeSearchResult result) {
        List<String> phases = evidencePhases(result);
        return phases.isEmpty() ? "" : " evidencePhase=" + String.join("|", phases);
    }

    private String evidenceResponsibilityContext(CodeSearchResult result) {
        String responsibility = llmEvidenceResponsibility(result);
        return responsibility.isBlank() ? "" : " evidenceResponsibility=" + responsibility;
    }

    private String fallbackScopeContext(CodeSearchResult result) {
        return "";
    }

    private String citationKindContext(CodeSearchResult result) {
        return hasLlmEvidenceClassification(result) ? " citationKind=" + citationKind(result) : "";
    }

    private String executionOrderContext(CodeSearchResult result) {
        List<String> phases = evidencePhases(result);
        return phases.isEmpty() ? "" : " executionOrder=" + executionOrder(phases);
    }

    private String analysisDiagnosticContext(CodeSearchResult result) {
        String status = directAnalysisDiagnosticStatus(result);
        if (status.isBlank()) {
            return "";
        }
        return " analysisDiagnosticStatus=" + status
                + " analysisDiagnosticScope=" + directAnalysisDiagnosticScope(result)
                + nullable(" analysisDiagnosticStage=", directAnalysisDiagnosticStage(result))
                + nullable(" analysisDiagnosticLanguage=", directAnalysisDiagnosticLanguage(result))
                + nullable(" analysisDiagnosticAnalyzer=", directAnalysisDiagnosticAnalyzer(result));
    }

    private String evidenceValidationContext(List<CodeSearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        boolean hasClassification = results.stream().anyMatch(this::hasLlmEvidenceClassification);
        boolean hasDiagnostics = results.stream().anyMatch(result -> !directAnalysisDiagnosticStatus(result).isBlank());
        if (!hasClassification && !hasDiagnostics) {
            return "";
        }
        List<String> checks = new ArrayList<>();
        checks.add("Evidence validation: LLM evidence classification describes what each chunk can prove; do not use unclassified chunks as proof for phase-specific claims.");
        checks.add("rank/evidenceScore are relevance signals, not execution order.");
        checks.add("Guard clauses and early returns are failure handling only when cited code or diagnostic metadata reports failed, partial, skipped, unavailable, or exception.");
        checks.add("retrievalSource=graph_expansion means the chunk was found through graph traversal; it is not graph persistence evidence.");
        checks.add("GRAPH_STORAGE evidence requires code/schema that creates, inserts, updates, deletes, replaces, or activates graph nodes/edges.");
        if (hasDiagnostics) {
            checks.add("analysisDiagnosticStatus is diagnostic metadata for graph/index analysis evidence, not proof of runtime answer-generation fallback.");
            checks.add("If analysisDiagnosticLanguage or analysisDiagnosticStage does not match a language/framework named in the question, treat it as cross-language supporting evidence rather than the primary answer basis.");
        }
        return String.join(" ", checks);
    }

    private List<String> evidencePhases(CodeSearchResult result) {
        if (result == null) {
            return List.of();
        }
        String llmPhase = llmImplementationPhase(result);
        if (!llmPhase.isBlank() && !"UNKNOWN".equals(llmPhase)) {
            return List.of(llmPhase);
        }
        return List.of();
    }

    private List<String> heuristicEvidencePhases(CodeSearchResult result) {
        if (result == null) {
            return List.of();
        }
        LinkedHashSet<String> phases = new LinkedHashSet<>();
        List<String> roles = evidenceRoles(result);
        if (roles.contains("indexing/pipeline")
                || roles.contains("graph-build/analysis")
                || roles.contains("graph-storage")
                || isIndexingChunk(result)) {
            phases.add("INDEXING");
        }
        if (roles.contains("retrieval/search-expansion")
                || roles.contains("graph-traversal/expansion")
                || roles.contains("graph-expanded-result")) {
            phases.add("SEARCH_EXPANSION");
        }
        if (roles.contains("evidence-ranking")) {
            phases.add("RANKING");
        }
        if (roles.contains("answer-context/generation")) {
            phases.add("ANSWER_GENERATION");
        }
        if (phases.isEmpty() && isGraphExpanded(result)) {
            phases.add("SEARCH_EXPANSION");
        }
        return phases.stream()
                .sorted(Comparator.comparingInt(this::phaseOrder))
                .limit(4)
                .toList();
    }

    private String heuristicEvidenceResponsibility(CodeSearchResult result) {
        if (result == null) {
            return "unknown";
        }
        String type = safe(result.chunkType(), "");
        String text = normalizeQuestionText(splitIdentifierTerms(String.join(" ",
                safe(result.filePath(), ""),
                type,
                safe(result.symbolName(), ""),
                safe(result.className(), ""),
                safe(result.methodName(), ""),
                safe(result.content(), "")
        )));
        String fallbackScope = fallbackScope(result);
        if ("ROUTING".equals(fallbackScope)) {
            return "route_decision";
        }
        if ("GRAPH_ANALYSIS".equals(fallbackScope)) {
            return "analysis_diagnostic";
        }
        if ("SEARCH_EXPANSION".equals(fallbackScope)) {
            return "search_fallback";
        }
        if ("ANSWER_GENERATION".equals(fallbackScope)) {
            return "answer_fallback";
        }
        if ("record".equals(type) || "enum".equals(type) || type.endsWith("_summary")
                || containsRoleTerms(text, "dto", "record", "response object", "request object", "data carrier")) {
            return "data_structure";
        }
        if (containsRoleTerms(text, "is graph", "is expanded", "is enabled", "has ", "check", "validate", "predicate", "filter", "helper")
                && !containsRoleTerms(text, "retrieves", "searches", "persists", "stores", "calls", "builds", "generates", "executes")) {
            return "helper_check";
        }
        List<String> roles = evidenceRoles(result);
        if (!roles.isEmpty()) {
            return "implementation_flow";
        }
        if (isStructured(type) || notBlank(result.methodName()) || notBlank(result.className()) || notBlank(result.symbolName())) {
            return "implementation_code";
        }
        return "supporting_context";
    }

    private String fallbackScope(CodeSearchResult result) {
        if (result == null) {
            return "";
        }
        String text = normalizeQuestionText(splitIdentifierTerms(String.join(" ",
                safe(result.filePath(), ""),
                safe(result.chunkType(), ""),
                safe(result.symbolName(), ""),
                safe(result.className(), ""),
                safe(result.methodName(), ""),
                safe(result.namespaceName(), ""),
                safe(result.content(), "")
        )));
        boolean fallbackTerm = containsRoleTerms(text, "fallback", "failed", "failure", "skipped", "partial", "unavailable", "catch runtime exception", "catches runtime exception", "catch exception", "catches exception");
        if (!fallbackTerm) {
            return "";
        }
        if (containsRoleTerms(text, "route", "router", "route decision", "unknown route", "code search route", "clarify")) {
            return "ROUTING";
        }
        if (containsRoleTerms(text, "semantic graph", "symbol solver", "analyzer", "analysis diagnostic", "code analysis diagnostics", "base graph", "chunk graph", "classpath", "build with diagnostics", "parse files", "parsed files")) {
            return "GRAPH_ANALYSIS";
        }
        if (containsRoleTerms(text, "expand graph", "graph related chunks", "keyword search remain", "embedding unavailable", "return ranked", "search expansion", "related chunks")) {
            return "SEARCH_EXPANSION";
        }
        if (containsRoleTerms(text, "fallback answer", "reasoning fallback", "method fallback", "locate fallback", "answer rewritten", "missing citation", "llm unavailable", "model unavailable", "low quality answer")) {
            return "ANSWER_GENERATION";
        }
        return "";
    }

    private String analysisDiagnosticStatus(CodeSearchResult result) {
        if (result == null || !"GRAPH_ANALYSIS".equals(fallbackScope(result))) {
            return "";
        }
        if (result.metadata() != null) {
            for (String key : List.of("analysisDiagnosticStatus", "diagnosticStatus", "analysisStatus", "status")) {
                Object value = result.metadata().get(key);
                String normalized = normalizeDiagnosticStatus(value == null ? "" : String.valueOf(value));
                if (!normalized.isBlank()) {
                    return normalized;
                }
            }
        }
        String text = normalizeQuestionText(splitIdentifierTerms(String.join(" ",
                safe(result.filePath(), ""),
                safe(result.chunkType(), ""),
                safe(result.symbolName(), ""),
                safe(result.className(), ""),
                safe(result.methodName(), ""),
                safe(result.namespaceName(), ""),
                safe(result.content(), "")
        )));
        if (containsRoleTerms(text, "failed", "failure", "error", "exception")) {
            return "FAILED";
        }
        if (containsRoleTerms(text, "partial", "partially", "incomplete")) {
            return "PARTIAL";
        }
        if (containsRoleTerms(text, "skipped", "skip", "unavailable", "disabled")) {
            return "SKIPPED";
        }
        if (containsRoleTerms(text, "success", "succeeded", "complete", "completed")) {
            return "SUCCESS";
        }
        return "";
    }

    private String directAnalysisDiagnosticStatus(CodeSearchResult result) {
        if (result == null || result.metadata() == null) {
            return "";
        }
        for (String key : List.of("analysisDiagnosticStatus", "diagnosticStatus", "analysisStatus")) {
            Object value = result.metadata().get(key);
            String normalized = normalizeDiagnosticStatus(value == null ? "" : String.valueOf(value));
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private String directAnalysisDiagnosticScope(CodeSearchResult result) {
        String scope = metadataString(result, "analysisDiagnosticScope", "diagnosticScope");
        return scope.isBlank() && !directAnalysisDiagnosticStatus(result).isBlank() ? "GRAPH_ANALYSIS" : scope;
    }

    private String directAnalysisDiagnosticStage(CodeSearchResult result) {
        String stage = metadataString(result, "analysisDiagnosticStage", "diagnosticStage", "stage");
        return stage.isBlank() ? "" : stage.toUpperCase(Locale.ROOT);
    }

    private String directAnalysisDiagnosticLanguage(CodeSearchResult result) {
        return normalizeDiagnosticLanguage(metadataString(result,
                "analysisDiagnosticLanguage", "diagnosticLanguage", "language"));
    }

    private String directAnalysisDiagnosticAnalyzer(CodeSearchResult result) {
        return metadataString(result, "analysisDiagnosticAnalyzer", "diagnosticAnalyzer", "analyzer");
    }

    private String normalizeDiagnosticStatus(String value) {
        String normalized = normalizeQuestionText(splitIdentifierTerms(value));
        if (normalized.isBlank()) {
            return "";
        }
        if (containsRoleTerms(normalized, "failed", "failure", "error", "exception")) {
            return "FAILED";
        }
        if (containsRoleTerms(normalized, "partial", "partially", "incomplete")) {
            return "PARTIAL";
        }
        if (containsRoleTerms(normalized, "skipped", "skip", "unavailable", "disabled")) {
            return "SKIPPED";
        }
        if (containsRoleTerms(normalized, "success", "succeeded", "complete", "completed")) {
            return "SUCCESS";
        }
        return "";
    }

    private String analysisDiagnosticScope(CodeSearchResult result) {
        return analysisDiagnosticStatus(result).isBlank() ? "" : "GRAPH_ANALYSIS";
    }

    private String analysisDiagnosticStage(CodeSearchResult result) {
        if (result == null || !"GRAPH_ANALYSIS".equals(fallbackScope(result))) {
            return "";
        }
        String metadataStage = metadataString(result, "analysisDiagnosticStage", "diagnosticStage", "stage");
        if (!metadataStage.isBlank()) {
            return metadataStage.toUpperCase(Locale.ROOT);
        }
        String text = diagnosticIdentityText(result);
        if (containsRoleTerms(text, "java semantic", "javaparser", "java parser", "symbol solver")) {
            return "JAVA_SEMANTIC";
        }
        if (containsRoleTerms(text, "csharp roslyn", "c sharp roslyn", "roslyn", "wpf", "winforms", "xaml")) {
            return "CSHARP_ROSLYN";
        }
        return "";
    }

    private String analysisDiagnosticLanguage(CodeSearchResult result) {
        if (result == null || !"GRAPH_ANALYSIS".equals(fallbackScope(result))) {
            return "";
        }
        String metadataLanguage = normalizeDiagnosticLanguage(metadataString(result,
                "analysisDiagnosticLanguage", "diagnosticLanguage", "language"));
        if (!metadataLanguage.isBlank()) {
            return metadataLanguage;
        }
        String stage = analysisDiagnosticStage(result);
        if ("JAVA_SEMANTIC".equals(stage)) {
            return "java";
        }
        if ("CSHARP_ROSLYN".equals(stage)) {
            return "csharp";
        }
        String parser = normalizeQuestionText(splitIdentifierTerms(metadataString(result, "parser", "strategy")));
        if (containsRoleTerms(parser, "javaparser", "java parser")) {
            return "java";
        }
        if (containsRoleTerms(parser, "roslyn", "semantic model")) {
            return "csharp";
        }
        String text = diagnosticIdentityText(result);
        if (containsRoleTerms(text, "javaparser", "java parser", "java semantic") || safe(result.filePath(), "").endsWith(".java")) {
            return "java";
        }
        if (containsRoleTerms(text, "roslyn", "csharp", "c sharp", "wpf", "winforms", "xaml") || safe(result.filePath(), "").endsWith(".cs")) {
            return "csharp";
        }
        return "";
    }

    private String analysisDiagnosticAnalyzer(CodeSearchResult result) {
        if (result == null || !"GRAPH_ANALYSIS".equals(fallbackScope(result))) {
            return "";
        }
        String metadataAnalyzer = metadataString(result, "analysisDiagnosticAnalyzer", "diagnosticAnalyzer", "analyzer");
        if (!metadataAnalyzer.isBlank()) {
            return metadataAnalyzer;
        }
        String stage = analysisDiagnosticStage(result);
        if ("JAVA_SEMANTIC".equals(stage)) {
            return "JavaParser Symbol Solver";
        }
        if ("CSHARP_ROSLYN".equals(stage)) {
            return "Roslyn";
        }
        String parser = metadataString(result, "parser", "strategy");
        return parser.isBlank() ? "" : parser;
    }

    private String metadataString(CodeSearchResult result, String... keys) {
        if (result == null || result.metadata() == null) {
            return "";
        }
        for (String key : keys) {
            Object value = result.metadata().get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    private String normalizeDiagnosticLanguage(String value) {
        String normalized = normalizeQuestionText(splitIdentifierTerms(value));
        if (normalized.isBlank()) {
            return "";
        }
        if (containsRoleTerms(normalized, "java") && !containsRoleTerms(normalized, "javascript", "java script")) {
            return "java";
        }
        if (containsRoleTerms(normalized, "csharp", "c sharp", "c#", "dotnet", "roslyn")) {
            return "csharp";
        }
        return "";
    }

    private String diagnosticIdentityText(CodeSearchResult result) {
        return normalizeQuestionText(splitIdentifierTerms(String.join(" ",
                safe(result.filePath(), ""),
                safe(result.chunkType(), ""),
                safe(result.symbolName(), ""),
                safe(result.className(), ""),
                safe(result.methodName(), ""),
                safe(result.namespaceName(), ""),
                metadataString(result, "analysisDiagnosticStage", "diagnosticStage", "stage"),
                metadataString(result, "analysisDiagnosticAnalyzer", "diagnosticAnalyzer", "analyzer"),
                metadataString(result, "analysisDiagnosticLanguage", "diagnosticLanguage", "language"),
                metadataString(result, "parser", "strategy"),
                safe(result.content(), "")
        )));
    }

    private boolean isIndexingChunk(CodeSearchResult result) {
        String text = normalizeQuestionText(splitIdentifierTerms(String.join(" ",
                safe(result.filePath(), ""),
                safe(result.chunkType(), ""),
                safe(result.symbolName(), ""),
                safe(result.className(), ""),
                safe(result.methodName(), ""),
                safe(result.content(), "")
        )));
        return containsRoleTerms(text, "index", "indexing", "chunk parser", "parse", "embedding", "graph build");
    }

    private String citationKind(CodeSearchResult result) {
        if (result == null) {
            return "unknown";
        }
        String llmKind = llmEvidenceKind(result);
        if (!llmKind.isBlank()) {
            return switch (llmKind) {
                case "graph_relationship" -> "graph_relationship";
                case "supporting_context" -> "supporting_context";
                default -> "direct_code";
            };
        }
        return "unknown";
    }

    private boolean hasLlmEvidenceClassification(CodeSearchResult result) {
        return result != null
                && result.metadata() != null
                && "llm_adjudication".equals(String.valueOf(result.metadata().get("llmEvidenceClassificationSource")));
    }

    private String llmEvidenceKind(CodeSearchResult result) {
        if (!hasLlmEvidenceClassification(result)) {
            return "";
        }
        String kind = metadataString(result, "llmEvidenceKind");
        return switch (kind) {
            case "graph_relationship", "supporting_context", "direct_code" -> kind;
            default -> "";
        };
    }

    private String llmImplementationPhase(CodeSearchResult result) {
        if (!hasLlmEvidenceClassification(result)) {
            return "";
        }
        String phase = metadataString(result, "llmImplementationPhase");
        return switch (phase) {
            case "INDEXING", "GRAPH_STORAGE", "SEARCH_EXPANSION", "RANKING", "ANSWER_GENERATION", "UNKNOWN" -> phase;
            default -> "";
        };
    }

    private String llmEvidenceResponsibility(CodeSearchResult result) {
        if (!hasLlmEvidenceClassification(result)) {
            return "";
        }
        String responsibility = metadataString(result, "llmEvidenceResponsibility");
        return responsibility.isBlank() ? "unknown" : responsibility;
    }

    private String heuristicCitationKind(CodeSearchResult result) {
        if (result == null) {
            return "unknown";
        }
        if (isGraphExpanded(result)) {
            Object kind = result.metadata().get("graphEvidenceKind");
            String graphKind = safe(kind == null ? null : String.valueOf(kind), "inferred");
            return "direct_code+graph_relationship:" + graphKind;
        }
        List<String> roles = evidenceRoles(result);
        if (roles.contains("graph-build/analysis") || roles.contains("graph-storage")) {
            return "direct_code";
        }
        if ("record".equals(result.chunkType()) || result.chunkType() != null && result.chunkType().endsWith("_summary")) {
            return "direct_code_support";
        }
        return "direct_code";
    }

    private boolean isDirectCodeEvidence(CodeSearchResult result) {
        if (hasLlmEvidenceClassification(result)) {
            return citationKind(result).startsWith("direct_code");
        }
        return heuristicCitationKind(result).startsWith("direct_code");
    }

    private String executionOrder(List<String> phases) {
        if (phases == null || phases.isEmpty()) {
            return "unknown";
        }
        return phases.stream()
                .map(phase -> {
                    int order = phaseOrder(phase);
                    String previous = phaseByOrder(order - 1);
                    String next = phaseByOrder(order + 1);
                    return phase
                            + (previous.isBlank() ? "" : ".happensAfter=" + previous)
                            + (next.isBlank() ? "" : ".happensBefore=" + next);
                })
                .collect(Collectors.joining("|"));
    }

    private int phaseOrder(String phase) {
        return switch (safe(phase, "")) {
            case "INDEXING" -> 0;
            case "GRAPH_STORAGE" -> 1;
            case "SEARCH_EXPANSION" -> 2;
            case "RANKING" -> 3;
            case "ANSWER_GENERATION" -> 4;
            default -> 99;
        };
    }

    private String phaseByOrder(int order) {
        return switch (order) {
            case 0 -> "INDEXING";
            case 1 -> "GRAPH_STORAGE";
            case 2 -> "SEARCH_EXPANSION";
            case 3 -> "RANKING";
            case 4 -> "ANSWER_GENERATION";
            default -> "";
        };
    }

    private List<String> evidenceRoles(CodeSearchResult result) {
        if (result == null) {
            return List.of();
        }
        LinkedHashSet<String> roles = new LinkedHashSet<>();
        String nameText = normalizeQuestionText(splitIdentifierTerms(String.join(" ",
                safe(result.filePath(), ""),
                safe(result.chunkType(), ""),
                safe(result.symbolName(), ""),
                safe(result.className(), ""),
                safe(result.methodName(), ""),
                safe(result.namespaceName(), ""),
                safe(result.controlName(), ""),
                safe(result.eventName(), "")
        )));
        String contentText = normalizeQuestionText(splitIdentifierTerms(safe(result.content(), "")));
        String combined = nameText + " " + contentText;
        boolean rankingIdentity = containsRoleTerms(nameText, "evidence score", "evidence rank", "ranking", "ranker", "rerank", "score", "edge weight", "intent evidence", "rank");
        if (containsRoleTerms(nameText, "indexing", "indexer", "index", "run index", "run indexing", "repository indexing")
                || containsRoleTerms(contentText, "orchestrates file scan", "file scan", "parser chunk generation", "chunk generation", "generated code chunks", "embedding", "add chunks")) {
            roles.add("indexing/pipeline");
        }
        if (containsRoleTerms(nameText, "search", "retrieval", "retrieve", "query", "expand", "expansion", "related chunks")
                || containsRoleTerms(contentText, "retrieves query", "search expansion", "expands related chunks", "seed chunks", "search result")) {
            roles.add("retrieval/search-expansion");
        }
        if (!rankingIdentity && (isGraphExpanded(result)
                || containsRoleTerms(nameText, "graph related chunks", "graph neighbors", "graph chunks for paths", "traversal", "graph path")
                || containsRoleTerms(contentText, "graph neighbors", "traverses", "traversal", "graph path", "path score", "graph depth", "max hop", "direction"))) {
            roles.add("graph-traversal/expansion");
        }
        if (containsRoleTerms(nameText, "merge graph", "replace graph", "save graph", "graph storage", "code graph storage")
                || containsRoleTerms(contentText, "merge graph", "replace graph", "save graph", "stores code graph", "stores code_graph", "insert statements", "code_graph_nodes", "code_graph_edges", "graph storage", "code graph storage")) {
            roles.add("graph-storage");
        }
        if (containsRoleTerms(nameText, "graph builder", "build diagnostics", "build with diagnostics", "graph build", "graph analyzer", "graph analysis")
                || containsRoleTerms(contentText, "builds graph", "graph builder", "analyzes graph", "graph diagnostics", "parser graph", "nodes and edges from", "relationships")) {
            roles.add("graph-build/analysis");
        }
        if (rankingIdentity || containsRoleTerms(contentText, "scores", "reranks", "evidence score", "ranking reason", "edge weight", "intent evidence score")) {
            roles.add("evidence-ranking");
        }
        if (containsRoleTerms(nameText, "generate answer", "generate code answer", "build prompt", "build context", "prompt context", "answer context")
                || containsRoleTerms(contentText, "builds prompt", "calls the llm", "model for answer generation", "returns answer response citations", "source code context", "prompt context")) {
            roles.add("answer-context/generation");
        }
        if (isGraphExpanded(result) && !roles.contains("graph-traversal/expansion")) {
            roles.add("graph-expanded-result");
        }
        return roles.stream().limit(4).toList();
    }

    private boolean containsRoleTerms(String value, String... terms) {
        String safeValue = safe(value, "");
        for (String term : terms) {
            if (safeValue.contains(normalizeQuestionText(splitIdentifierTerms(term)))) {
                return true;
            }
        }
        return false;
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


    private String adjudicationClaimContext(CodeSearchResult result) {
        if (result == null || result.metadata() == null) {
            return "";
        }
        Object supported = result.metadata().get("llmSupportedClaims");
        Object unsupported = result.metadata().get("llmNotSupportedClaims");
        return nullable(" llmSupportedClaims=", supported == null ? null : String.valueOf(supported))
                + nullable(" llmNotSupportedClaims=", unsupported == null ? null : String.valueOf(unsupported));
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

    private String truncate(String value, int maxChars) {
        if (value == null || maxChars <= 0 || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "...";
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

    public interface CodeAnswerStreamSink {
        default void onStatus(String stage, String message) {
        }

        void onEvidence(List<CodeEvidence> evidence);

        void onDelta(String text);

        void onReplace(String answer, String reason);
    }

    private record CodeRetrieval(
            List<CodeSearchResult> results,
            RagPipelineService.EvidenceAssessment assessment,
            RagPipelineService.QueryPlan queryPlan,
            CodeQueryPlan deterministicPlan,
            RagPipelineService.CodeEvidenceFollowUpPlan followUpPlan,
            int followUpQueriesUsed,
            int followUpCandidateCount,
            int iteration,
            int candidateCount,
            int pinnedCandidateCount,
            int pinnedUsedCount
    ) {
    }

    private record AnswerQualityTrace(
            boolean observed,
            String initialFailureReason,
            int initialChars,
            int initialCitationRefs,
            int initialInvalidCitationRefs,
            int initialCitationCoverage,
            String initialDoneReason,
            String initialPreview,
            boolean retryAttempted,
            String retryFailureReason,
            int retryChars,
            int retryCitationRefs,
            int retryInvalidCitationRefs,
            int retryCitationCoverage,
            String retryDoneReason,
            String retryPreview,
            String finalFailureReason,
            boolean unavailable,
            String unavailableReason
    ) {
        static AnswerQualityTrace empty() {
            return new AnswerQualityTrace(false, "", 0, 0, 0, 0, "", "", false, "", 0, 0, 0, 0, "", "", "", false, "");
        }

        static AnswerQualityTrace unavailable(RuntimeException ex) {
            return new AnswerQualityTrace(true, "", 0, 0, 0, 0, "", "", false, "", 0, 0, 0, 0, "", "", "", true,
                    ex == null ? "unknown" : ex.getClass().getSimpleName());
        }

        static AnswerQualityTrace fromInitial(String answer, String doneReason, List<CodeSearchResult> evidence, String failureReason) {
            CitationSnapshot snapshot = CitationSnapshot.from(answer, evidence);
            return new AnswerQualityTrace(true, safeReason(failureReason), safeValue(answer, "").length(), snapshot.references(), snapshot.invalid(), snapshot.coverage(),
                    safeValue(doneReason, "none"), preview(answer), false, "", 0, 0, 0, 0, "", "", "", false, "");
        }

        AnswerQualityTrace withRetry(String answer, String doneReason, List<CodeSearchResult> evidence, String failureReason) {
            CitationSnapshot snapshot = CitationSnapshot.from(answer, evidence);
            return new AnswerQualityTrace(true, initialFailureReason, initialChars, initialCitationRefs, initialInvalidCitationRefs, initialCitationCoverage,
                    initialDoneReason, initialPreview, true, safeReason(failureReason), safeValue(answer, "").length(), snapshot.references(), snapshot.invalid(),
                    snapshot.coverage(), safeValue(doneReason, "none"), preview(answer), finalFailureReason, unavailable, unavailableReason);
        }

        AnswerQualityTrace withFinalFailure(String answer, String doneReason, List<CodeSearchResult> evidence, String failureReason) {
            if (!observed) {
                AnswerQualityTrace trace = fromInitial(answer, doneReason, evidence, failureReason);
                return trace.withFinalFailure(answer, doneReason, evidence, failureReason);
            }
            return new AnswerQualityTrace(true, initialFailureReason, initialChars, initialCitationRefs, initialInvalidCitationRefs, initialCitationCoverage,
                    initialDoneReason, initialPreview, retryAttempted, retryFailureReason, retryChars, retryCitationRefs, retryInvalidCitationRefs,
                    retryCitationCoverage, retryDoneReason, retryPreview, safeReason(failureReason), unavailable, unavailableReason);
        }

        String summary() {
            if (unavailable) {
                return "LLM answer quality trace: unavailable=true, reason=" + unavailableReason + ".";
            }
            StringBuilder builder = new StringBuilder("LLM answer quality trace: initialFailureReason=")
                    .append(initialFailureReason.isBlank() ? "none" : initialFailureReason)
                    .append(", initialChars=").append(initialChars)
                    .append(", initialCitedReferences=").append(initialCitationRefs)
                    .append(", initialInvalidCitationRefs=").append(initialInvalidCitationRefs)
                    .append(", initialCitationCoverage=").append(initialCitationCoverage).append("%")
                    .append(", initialDoneReason=").append(initialDoneReason);
            if (retryAttempted) {
                builder.append(", retryFailureReason=").append(retryFailureReason.isBlank() ? "none" : retryFailureReason)
                        .append(", retryChars=").append(retryChars)
                        .append(", retryCitedReferences=").append(retryCitationRefs)
                        .append(", retryInvalidCitationRefs=").append(retryInvalidCitationRefs)
                        .append(", retryCitationCoverage=").append(retryCitationCoverage).append("%")
                        .append(", retryDoneReason=").append(retryDoneReason);
            }
            if (!finalFailureReason.isBlank()) {
                builder.append(", finalFailureReason=").append(finalFailureReason);
            }
            if (!initialPreview.isBlank()) {
                builder.append(", initialPreview=\"").append(initialPreview).append("\"");
            }
            if (retryAttempted && !retryPreview.isBlank()) {
                builder.append(", retryPreview=\"").append(retryPreview).append("\"");
            }
            return builder.append(".").toString();
        }

        private static String safeReason(String value) {
            return safeValue(value, "").replaceAll("[\\r\\n]+", " ").trim();
        }

        private static String preview(String answer) {
            String compact = safeValue(answer, "").replaceAll("\\s+", " ").trim();
            if (compact.length() <= 220) {
                return compact.replace("\"", "'");
            }
            return compact.substring(0, 220).trim().replace("\"", "'") + "...";
        }

        private static String safeValue(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    private record CitationSnapshot(int references, int invalid, int coverage) {
        static CitationSnapshot from(String answer, List<CodeSearchResult> evidence) {
            String safeAnswer = answer == null ? "" : answer;
            List<CodeSearchResult> safeEvidence = evidence == null ? List.of() : evidence;
            Set<Integer> referenced = citationReferencesStatic(safeAnswer);
            long invalid = referenced.stream()
                    .filter(index -> index < 1 || index > safeEvidence.size())
                    .count();
            List<String> claims = claimSegmentsStatic(safeAnswer);
            long citedClaims = claims.stream().filter(CitationSnapshot::containsCitationStatic).count();
            int coverage = claims.isEmpty() ? 0 : (int) Math.round((citedClaims * 100.0) / claims.size());
            return new CitationSnapshot(referenced.size(), (int) invalid, coverage);
        }

        private static Set<Integer> citationReferencesStatic(String answer) {
            Set<Integer> values = new HashSet<>();
            Matcher matcher = Pattern.compile("\\[(\\d+)]").matcher(answer == null ? "" : answer);
            while (matcher.find()) {
                try {
                    values.add(Integer.parseInt(matcher.group(1)));
                } catch (NumberFormatException ignored) {
                    // Regex keeps this numeric, but keep parsing defensive.
                }
            }
            return values;
        }

        private static List<String> claimSegmentsStatic(String answer) {
            String normalized = (answer == null ? "" : answer).replace('\r', '\n');
            return Pattern.compile("[\\n.!?]+")
                    .splitAsStream(normalized)
                    .map(String::trim)
                    .filter(segment -> segment.length() >= 18)
                    .filter(segment -> segment.matches("(?s).*[\\p{L}\\p{N}].*"))
                    .limit(40)
                    .toList();
        }

        private static boolean containsCitationStatic(String answer) {
            return answer != null && answer.matches("(?s).*\\[\\d+].*");
        }
    }

    private String abbreviate(String value, int maxChars) {
        String text = safe(value, "");
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private record CodeQueryPlan(String intent, List<String> queries, boolean originalOnlyFallback) {
        List<String> auxiliaryQueries() {
            if (queries == null || queries.size() <= 1) {
                return List.of();
            }
            return queries.subList(1, queries.size());
        }
    }

    private record CodeContextBundle(List<CodeSearchResult> results, String context, int droppedCount) {
    }

    private record CodeExcerpt(
            String text,
            String kind,
            boolean contentComplete,
            boolean omittedByBudget,
            int excerptLineStart,
            int excerptLineEnd
    ) {
    }

    private record CitationQuality(int referencedCount, int invalidCount, int coveragePercent, String summary) {
    }

    private record LengthContinuation(String answer, String doneReason, OllamaClient.ChatResult chatResult, boolean continued) {
    }

    enum CodeQuestionMode {
        OVERVIEW("overview", "Synthesize search, definitions, references, and nearby chunks. Answer natural-language architecture questions with sections: summary, related files/methods, flow, evidence, and limitations."),
        REASONING("reasoning", "Explain why the implementation appears to be structured this way. Separate direct code evidence from inferred design intent, tradeoffs, and uncertainty."),
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
