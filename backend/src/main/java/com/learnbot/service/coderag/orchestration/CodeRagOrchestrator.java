package com.learnbot.service.coderag.orchestration;

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
import com.learnbot.service.CodeReferenceService;
import com.learnbot.service.CodeSearchService;
import com.learnbot.service.CodeSourceClassifier;
import com.learnbot.service.CodeIntelligenceAuthority;
import com.learnbot.service.CommitInsightService;
import com.learnbot.service.EvidenceExcerptSelector;
import com.learnbot.service.GraphSearchIntent;
import com.learnbot.service.OllamaClient;
import com.learnbot.service.RagMetricsService;
import com.learnbot.service.RagPipelineService;
import com.learnbot.service.coderag.answer.CodeAnswerStreamSink;
import com.learnbot.service.coderag.answer.CodeAnswerGenerator;
import com.learnbot.service.coderag.answer.CodeAnswerVerifier;
import com.learnbot.service.coderag.answer.CodeContextAssembler;
import com.learnbot.service.coderag.answer.CodeEvidenceFidelityFallback;
import com.learnbot.service.coderag.answer.CodeEvidenceIrFidelity;
import com.learnbot.service.coderag.answer.OllamaCodeAnswerGenerator;
import com.learnbot.service.coderag.diagnostics.CodeRagDiagnosticsBuilder;
import com.learnbot.service.coderag.evidence.CodeEvidenceAccumulator;
import com.learnbot.service.coderag.evidence.CodeEvidenceAdjudicator;
import com.learnbot.service.coderag.evidence.CodeEvidenceCoverageGate;
import com.learnbot.service.coderag.evidence.CodeEvidenceFileDiversity;
import com.learnbot.service.coderag.evidence.CodeEvidenceId;
import com.learnbot.service.coderag.evidence.CodeEvidenceRanker;
import com.learnbot.service.coderag.evidence.CodeEvidenceRetentionPlan;
import com.learnbot.service.coderag.evidence.CodeEvidenceSelectionPolicy;
import com.learnbot.service.coderag.evidence.extractor.AssignmentEvidenceExtractor;
import com.learnbot.service.coderag.evidence.extractor.EndpointEvidenceExtractor;
import com.learnbot.service.coderag.evidence.extractor.EvidenceExtractorRegistry;
import com.learnbot.service.coderag.evidence.extractor.NavigationEvidenceExtractor;
import com.learnbot.service.coderag.evidence.extractor.OperationEvidenceExtractor;
import com.learnbot.service.coderag.evidence.extractor.PersistenceEvidenceExtractor;
import com.learnbot.service.coderag.evidence.extractor.TransactionEvidenceExtractor;
import com.learnbot.service.coderag.model.CodeAnalysisDiagnosticMetadata;
import com.learnbot.service.coderag.model.CodeEvidenceExtractionContext;
import com.learnbot.service.coderag.model.CodeEvidenceIr;
import com.learnbot.service.coderag.model.CodeEvidenceItem;
import com.learnbot.service.coderag.model.EvidenceExtractionStage;
import com.learnbot.service.coderag.model.CodeQuestionMode;
import com.learnbot.service.coderag.retrieval.CodeEvidenceOperationExecutor;
import com.learnbot.service.coderag.retrieval.CodeGraphClosurePlanner;
import com.learnbot.service.coderag.retrieval.CodeInitialDiscoveryPlanner;
import com.learnbot.service.coderag.retrieval.CodeQueryRewritePolicy;
import com.learnbot.service.coderag.retrieval.CodeRetrievalCoordinator;
import com.learnbot.service.coderag.retrieval.CodeRetrievalPlanValidator;
import com.learnbot.service.coderag.retrieval.RepositoryQuestionMapBuilder;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class CodeRagOrchestrator {
    private static final int MAX_PLAN_CONTRACT_REPAIR_ATTEMPTS = 2;
    private static final Logger log = LoggerFactory.getLogger(CodeRagOrchestrator.class);
    private static final int OVERVIEW_CONTEXT_LIMIT = 12;
    private static final int DEFAULT_CONTEXT_LIMIT = 8;
    private static final int FALLBACK_EXCERPT_CHARS = 180;
    private static final int PRESELECTION_IR_EVIDENCE_LIMIT = 64;
    private static final int PROMPT_SUFFIX_RESERVATION_PASSES = 3;
    private static final int MAX_EVIDENCE_RESPONSE_POLICY_CHARS = 2_400;
    private static final double CONVERSATION_PINNED_BOOST = 0.18;
    private static final Pattern RESOURCE_IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{2,}(?:\\.[A-Za-z0-9_]+)?");

    private final CodeSearchService searchService;
    private final CodeRepository codeRepository;
    private final CodeReferenceService referenceService;
    private final CommitInsightService commitInsightService;
    private final OllamaClient ollamaClient;
    private final LearnBotProperties properties;
    private final RagPipelineService pipelineService;
    private final CodeEvidenceRanker evidenceRanker;
    private final RagMetricsService ragMetricsService;
    private final CodeRetrievalCoordinator retrievalCoordinator;
    private final CodeGraphClosurePlanner graphClosurePlanner = new CodeGraphClosurePlanner();
    private final CodeInitialDiscoveryPlanner initialDiscoveryPlanner = new CodeInitialDiscoveryPlanner();
    private final CodeQueryRewritePolicy queryRewritePolicy = new CodeQueryRewritePolicy();
    private final RepositoryQuestionMapBuilder questionMapBuilder;
    private final CodeQuestionRouter questionRouter;
    private final CodeContextAssembler contextAssembler;
    private final CodeAnswerGenerator answerGenerator;
    private final CodeAnswerVerifier answerVerifier;
    private final CodeEvidenceAccumulator evidenceAccumulator;
    private final CodeEvidenceAdjudicator evidenceAdjudicator;
    private final CodeRagDiagnosticsBuilder diagnosticsBuilder;
    private final CodeEvidenceCoverageGate coverageGate = new CodeEvidenceCoverageGate();

    public CodeRagOrchestrator(
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
        this(
                searchService, codeRepository, referenceService, commitInsightService,
                ollamaClient, properties, pipelineService, evidenceRanker, ragMetricsService,
                defaultEvidenceExtractorRegistry(),
                new OllamaCodeAnswerGenerator(ollamaClient),
                new CodeRagDiagnosticsBuilder(evidenceRanker)
        );
    }

    @Autowired
    public CodeRagOrchestrator(
            CodeSearchService searchService,
            CodeRepository codeRepository,
            CodeReferenceService referenceService,
            CommitInsightService commitInsightService,
            OllamaClient ollamaClient,
            LearnBotProperties properties,
            RagPipelineService pipelineService,
            CodeEvidenceRanker evidenceRanker,
            RagMetricsService ragMetricsService,
            EvidenceExtractorRegistry evidenceExtractorRegistry,
            CodeAnswerGenerator answerGenerator,
            CodeRagDiagnosticsBuilder diagnosticsBuilder
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
        this.retrievalCoordinator = new CodeRetrievalCoordinator(
                new CodeEvidenceOperationExecutor(searchService, codeRepository, referenceService));
        this.questionMapBuilder = new RepositoryQuestionMapBuilder(codeRepository);
        this.questionRouter = new CodeQuestionRouter(
                ollamaClient, properties, pipelineService, commitInsightService != null);
        this.contextAssembler = new CodeContextAssembler(evidenceRanker.debug());
        this.answerGenerator = Objects.requireNonNull(answerGenerator, "answerGenerator");
        this.answerVerifier = new CodeAnswerVerifier(this::qualityFailureReason);
        this.evidenceAccumulator = new CodeEvidenceAccumulator(evidenceExtractorRegistry);
        this.evidenceAdjudicator = new CodeEvidenceAdjudicator();
        this.diagnosticsBuilder = Objects.requireNonNull(diagnosticsBuilder, "diagnosticsBuilder");
    }

    private static EvidenceExtractorRegistry defaultEvidenceExtractorRegistry() {
        return new EvidenceExtractorRegistry(List.of(
                new OperationEvidenceExtractor(),
                new EndpointEvidenceExtractor(),
                new AssignmentEvidenceExtractor(),
                new TransactionEvidenceExtractor(),
                new NavigationEvidenceExtractor(),
                new PersistenceEvidenceExtractor()
        ));
    }

    public CodeRagOrchestrator(
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

    public CodeRagOrchestrator(
            CodeSearchService searchService,
            CodeReferenceService referenceService,
            CommitInsightService commitInsightService,
            OllamaClient ollamaClient,
            LearnBotProperties properties,
            RagPipelineService pipelineService
    ) {
        this(searchService, null, referenceService, commitInsightService, ollamaClient, properties, pipelineService, new CodeEvidenceRanker(properties), null);
    }

    public CodeRagOrchestrator(
            CodeSearchService searchService,
            CodeReferenceService referenceService,
            CommitInsightService commitInsightService,
            OllamaClient ollamaClient,
            LearnBotProperties properties
    ) {
        this(searchService, null, referenceService, commitInsightService, ollamaClient, properties, new RagPipelineService(ollamaClient, properties), new CodeEvidenceRanker(properties), null);
    }

    public CodeRagOrchestrator(
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
        try (CodeRagLlmCallBudget.Scope ignored = openCodeRagLlmBudget()) {
            return askPrioritized(repositoryId, selectedSpaceId, spaceIds, question, mode, limit, null, null);
        } finally {
            ollamaClient.finishPrimaryRequest();
        }
    }

    public CodeAskResponse askConversational(UUID repositoryId, UUID selectedSpaceId, List<UUID> spaceIds, String question, String mode, Integer limit, RagConversationContext conversationContext) {
        ollamaClient.beginPrimaryRequest();
        try (CodeRagLlmCallBudget.Scope ignored = openCodeRagLlmBudget()) {
            return askPrioritized(repositoryId, selectedSpaceId, spaceIds, question, mode, limit, conversationContext, null);
        } finally {
            ollamaClient.finishPrimaryRequest();
        }
    }

    public CodeAskResponse askStreaming(UUID repositoryId, UUID selectedSpaceId, List<UUID> spaceIds, String question, String mode, Integer limit, CodeAnswerStreamSink streamSink) {
        ollamaClient.beginPrimaryRequest();
        try (CodeRagLlmCallBudget.Scope ignored = openCodeRagLlmBudget()) {
            return askPrioritized(repositoryId, selectedSpaceId, spaceIds, question, mode, limit, null, streamSink);
        } finally {
            ollamaClient.finishPrimaryRequest();
        }
    }

    public CodeAskResponse askConversationalStreaming(UUID repositoryId, UUID selectedSpaceId, List<UUID> spaceIds, String question, String mode, Integer limit, RagConversationContext conversationContext, CodeAnswerStreamSink streamSink) {
        ollamaClient.beginPrimaryRequest();
        try (CodeRagLlmCallBudget.Scope ignored = openCodeRagLlmBudget()) {
            return askPrioritized(repositoryId, selectedSpaceId, spaceIds, question, mode, limit, conversationContext, streamSink);
        } finally {
            ollamaClient.finishPrimaryRequest();
        }
    }

    private CodeAskResponse askPrioritized(UUID repositoryId, UUID selectedSpaceId, List<UUID> spaceIds, String question, String mode, Integer limit, RagConversationContext conversationContext, CodeAnswerStreamSink streamSink) {
        long askStarted = System.nanoTime();
        String originalQuestion = safe(question, "");
        String effectiveQuestion = questionRouter.effectiveQuestion(originalQuestion, conversationContext);
        boolean combinedPlanning = pipelineService.supportsCombinedCodePlanning();
        RagPipelineService.CodeRagRouteDecision routeDecision = questionRouter.initialRoute(
                originalQuestion, mode, conversationContext, combinedPlanning);
        boolean commitFallbackUsed = false;
        if (routeDecision.route() == RagPipelineService.CodeRagRoute.COMMIT_DIFF && commitInsightService != null) {
            CodeAskResponse commitResponse = commitInsightService.answer(
                    repositoryId, questionRouter.routedCommitQuestion(originalQuestion, routeDecision));
            if (!commitResponse.evidence().isEmpty()) {
                CodeAskResponse routed = questionRouter.withRouteDiagnostics(commitResponse, routeDecision, false);
                if (streamSink != null) {
                    streamSink.onReplace(routed.answer(), "commit_insight");
                    streamSink.onEvidence(routed.evidence());
                }
                return routed;
            }
            commitFallbackUsed = true;
        }
        effectiveQuestion = questionRouter.routedQuestion(effectiveQuestion, routeDecision);
        String effectiveMode = questionRouter.routedMode(mode, routeDecision);
        CodeQuestionMode questionMode = questionRouter.classify(effectiveQuestion, effectiveMode, conversationContext);
        int safeLimit = questionRouter.safeLimit(questionMode, limit);
        if (streamSink != null) {
            streamSink.onStatus("retrieval_started", "코드 근거를 검색하고 있습니다.");
        }
        long retrievalStarted = System.nanoTime();
        CodeRetrieval retrieval = retrieveCodeEvidence(repositoryId, selectedSpaceId, spaceIds, effectiveQuestion, questionMode, safeLimit, conversationContext);
        long retrievalMs = elapsedMs(retrievalStarted);
        if (combinedPlanning && retrieval.routeDecision() != null) {
            routeDecision = retrieval.routeDecision();
            effectiveMode = questionRouter.routedMode(effectiveMode, routeDecision);
            questionMode = questionRouter.classify(effectiveQuestion, effectiveMode, conversationContext);
            if (routeDecision.route() == RagPipelineService.CodeRagRoute.COMMIT_DIFF && commitInsightService != null) {
                CodeAskResponse commitResponse = commitInsightService.answer(
                        repositoryId, questionRouter.routedCommitQuestion(originalQuestion, routeDecision));
                if (!commitResponse.evidence().isEmpty()) {
                    CodeAskResponse routed = questionRouter.withRouteDiagnostics(commitResponse, routeDecision, false);
                    if (streamSink != null) {
                        streamSink.onReplace(routed.answer(), "commit_insight");
                        streamSink.onEvidence(routed.evidence());
                    }
                    return routed;
                }
                commitFallbackUsed = true;
            }
        }
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
                Treat citationKind=direct_code as direct source evidence and citationKind containing graph_relationship as relationship evidence; do not cite relationship evidence as if it were a direct method call.
                Treat llmSupportedClaims as claims directly supported by that evidence.
                Treat llmNotSupportedClaims as explicit boundaries; do not state them as facts unless another selected citation directly supports them.
                Build the answer from selected evidence and these claim boundaries. Do not restore unselected candidate interpretations.
                Treat rank and evidenceScore as relevance hints, not as method call order.
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
        String promptPrefix = questionRouter.questionPrompt(originalQuestion, effectiveQuestion, conversationContext)
                + conversationFocus(conversationContext);
        List<CodeSearchResult> contextCandidates = answerContextResults(
                questionMode, effectiveQuestion, results, retrieval.followUpPlan(),
                retrieval.evidenceIr());
        CodeEvidenceRetentionPlan contextRetentionPlan = answerEvidenceRetentionPlan(
                effectiveQuestion, contextCandidates, retrieval.followUpPlan(),
                retrieval.evidenceIr());
        Set<String> requiredContextEvidenceIds = requiredEvidenceIds(contextRetentionPlan);
        CodeContextAssembler.ContextBundle contextBundle = contextAssembler.assemble(
                new CodeContextAssembler.AssemblyRequest(
                        effectiveQuestion,
                        questionMode.value(),
                        systemPrompt,
                        promptPrefix,
                        contextCandidates,
                        streamSink != null,
                        pipelineService.contextWindow(),
                        pipelineService.promptTokenBudgetBalanced(),
                        requiredContextEvidenceIds
                ));
        String promptSuffixReservation = "";
        AnswerPromptSupport promptSupport = null;
        for (int pass = 0; pass < PROMPT_SUFFIX_RESERVATION_PASSES; pass++) {
            promptSupport = answerPromptSupport(effectiveQuestion, contextBundle.results(), retrieval);
            if (promptSupport.suffix().length() <= promptSuffixReservation.length()) {
                break;
            }
            int reservationChars = pass == PROMPT_SUFFIX_RESERVATION_PASSES - 1
                    ? Math.max(promptSupport.suffix().length(),
                            CodeEvidenceIrFidelity.promptCharLimit()
                                    + MAX_EVIDENCE_RESPONSE_POLICY_CHARS)
                    : promptSupport.suffix().length();
            promptSuffixReservation = promptSupport.suffix()
                    + " ".repeat(Math.max(0, reservationChars - promptSupport.suffix().length()));
            contextBundle = contextAssembler.assemble(
                    new CodeContextAssembler.AssemblyRequest(
                            effectiveQuestion,
                            questionMode.value(),
                            systemPrompt,
                            promptPrefix + promptSuffixReservation,
                            contextCandidates,
                            streamSink != null,
                            pipelineService.contextWindow(),
                            pipelineService.promptTokenBudgetBalanced(),
                            requiredContextEvidenceIds
                    ));
            if (pass == PROMPT_SUFFIX_RESERVATION_PASSES - 1) {
                promptSupport = answerPromptSupport(effectiveQuestion, contextBundle.results(), retrieval);
            }
        }
        List<CodeSearchResult> answerResults = contextBundle.results();
        CodeEvidenceAdjudicator.Adjudication typedAdjudication = promptSupport.typedAdjudication();
        CodeEvidenceIr answerScopedIr = typedAdjudication.evidenceIr();
        CodeEvidenceCoverageGate.Outcome responseCoverage = promptSupport.responseCoverage();
        String userPrompt = promptPrefix
                + promptSupport.suffix()
                + "\n\nSource-code context:\n" + contextBundle.context();
        int contextBudgetDropped = contextBundle.droppedCount();
        long contextMs = elapsedMs(contextStarted);
        if (streamSink != null) {
            streamSink.onStatus("evidence_ready", "답변에 사용할 코드 근거를 정리했습니다.");
            streamSink.onEvidence(buildEvidence(answerResults));
        }
        if (shouldBlockAnswerGeneration(
                responseCoverage, retrieval.terminalStatus(),
                pipelineService.supportsCombinedCodePlanning() ? retrieval.followUpPlan() : null)) {
            String answer = insufficientEvidenceAnswer(answerResults, retrieval);
            recordMetrics(questionMode.value(), retrieval, retrievalMs, contextMs, 0, answerResults.size(), 0, 0, true, false, elapsedMs(askStarted));
            return new CodeAskResponse(
                    questionMode.value(),
                    answer,
                    buildEvidence(answerResults),
                    confidence(answerResults, retrieval.assessment()),
                    typedEvidenceDiagnostics(buildDiagnostics(
                            questionMode, results, answerResults, answer, null,
                            false, false, false, false, false,
                            AnswerQualityTrace.empty(), retrieval, contextBudgetDropped,
                            routeDecision, commitFallbackUsed, originalQuestion,
                            effectiveQuestion, conversationContext), typedAdjudication)
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
                    ? chatWithLimit(
                            systemPrompt, userPrompt, maxOutputTokens,
                            CodeAnswerGenerator.Phase.INITIAL)
                    : stream(systemPrompt, userPrompt, streamSink, streamedAnswer, maxOutputTokens);
            llmMs += elapsedMs(llmStarted);
            finalChatResult = chatResult;
            answer = chatResult.content();
            answerDoneReason = chatResult.doneReason();
            if (isLengthStop(answerDoneReason) && CodeRagLlmCallBudget.hasCapacity()) {
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
            String qualityReason = answerQualityFailureReason(
                    effectiveQuestion, answer, answerResults, answerDoneReason, answerScopedIr);
            answerQualityTrace = AnswerQualityTrace.fromInitial(answer, answerDoneReason, answerResults, qualityReason);
            if (qualityReason != null && pipelineService.maxIterations() > 1
                    && CodeRagLlmCallBudget.hasCapacity()) {
                String retryPrompt = userPrompt
                        + "\n\nPrevious answer failed quality check: " + qualityReason + "."
                        + "\nRewrite the answer using only the cited code context. Cite every factual claim with [n].";
                long retryStarted = System.nanoTime();
                OllamaClient.ChatResult retryResult = chatWithLimit(
                        systemPrompt + "\nBe concise and citation-strict.",
                        retryPrompt,
                        repairOutputTokens(maxOutputTokens),
                        CodeAnswerGenerator.Phase.REPAIR);
                llmMs += elapsedMs(retryStarted);
                String retryAnswer = retryResult == null ? "" : retryResult.content();
                String retryDoneReason = retryResult == null ? null : retryResult.doneReason();
                String retryQualityReason = answerQualityFailureReason(
                        effectiveQuestion, retryAnswer, answerResults, retryDoneReason, answerScopedIr);
                log.info("Code RAG answer repair initialFailure={} retryFailure={} initialDoneReason={} retryDoneReason={} question={}",
                        qualityReason, retryQualityReason, safe(answerDoneReason, ""), safe(retryDoneReason, ""),
                        abbreviate(effectiveQuestion, 180));
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
            answer = CodeEvidenceFidelityFallback.answer(
                    answerResults, "answer generation unavailable", answerScopedIr);
            answerDoneReason = null;
            llmUnavailable = true;
            answerQualityTrace = AnswerQualityTrace.unavailable(ex);
            if (streamSink != null) {
                streamSink.onReplace(answer, "llm_unavailable_fallback");
            }
        }
        String finalQualityReason = answerQualityFailureReason(
                effectiveQuestion, answer, answerResults, answerDoneReason, answerScopedIr);
        if (finalQualityReason != null) {
            answerRewritten = true;
            answerQualityTrace = answerQualityTrace.withFinalFailure(answer, answerDoneReason, answerResults, finalQualityReason);
            String fidelityFailureReason = CodeEvidenceIrFidelity.missingReason(answer, answerScopedIr);
            answer = CodeEvidenceFidelityFallback.answer(
                    answerResults,
                    fidelityFailureReason == null ? finalQualityReason : fidelityFailureReason,
                    answerScopedIr);
            log.info("Code RAG answer replaced by evidence-fidelity fallback reason={} evidence={} question={}",
                    fidelityFailureReason == null ? finalQualityReason : fidelityFailureReason,
                    answerResults.size(), abbreviate(effectiveQuestion, 180));
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
                typedEvidenceDiagnostics(buildDiagnostics(
                        questionMode, results, answerResults, answer, answerDoneReason,
                        llmUnavailable, answerRewritten, answerRetried, answerContinued,
                        answerKeptAfterStreamValidation, answerQualityTrace, retrieval,
                        contextBudgetDropped, routeDecision, commitFallbackUsed,
                        originalQuestion, effectiveQuestion, conversationContext), typedAdjudication)
        );
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
        String traceId = UUID.randomUUID().toString();
        long retrievalDeadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(
                pipelineService.codeRetrievalDeadlineSeconds());
        Map<UUID, CodeSearchResult> merged = new LinkedHashMap<>();
        int pinnedCandidateCount = collectPinnedConversationEvidence(repositoryId, selectedSpaceId, spaceIds, question, conversationContext, merged);
        int searchLimit = pipelineService.codeSearchLimit(questionMode == CodeQuestionMode.OVERVIEW ? limit + 6 : limit + 4);
        CodeQueryPlan deterministicPlan = codeQueryPlan(question);
        RagPipelineService.QueryPlan queryPlan = queryRewritePolicy.needsSourceVocabularyBridge(question)
                ? pipelineService.buildQueryPlan(question, RagPipelineService.Domain.CODE, List.of())
                : new RagPipelineService.QueryPlan(
                        RagPipelineService.Domain.CODE, List.of(question), false, false, false,
                        "source-vocabulary bridge not needed");
        collectEvidenceForQuery(repositoryId, selectedSpaceId, spaceIds, question, questionMode, searchLimit, merged);
        queryPlan.queries().stream()
                .filter(query -> query != null && !query.isBlank() && !query.equalsIgnoreCase(question))
                .distinct()
                .limit(2)
                .forEach(query -> collectGraphExpandedEvidenceForQuery(
                        repositoryId, selectedSpaceId, spaceIds, query, questionMode, searchLimit, merged));
        int graphExpansionAdded = 0;
        CodeEvidenceIr retrievalEvidenceIr = accumulateRetrievalEvidenceIr(
                CodeEvidenceIr.empty(), question, questionMode, EvidenceExtractionStage.POST_SEED, merged.values());
        RepositoryQuestionMapBuilder.RepositoryQuestionMap repositoryMap = questionMapBuilder.build(
                repositoryId, selectedSpaceId, spaceIds, question, merged.values(), retrievalEvidenceIr);
        String plannerContext = repositoryMap.plannerContext();
        RagPipelineService.CodeEvidenceSearchPlan searchPlan = pipelineService.planCodeEvidenceSearch(
                question,
                questionMode.value(),
                plannerContext,
                4
        );
        RagPipelineService.CodeRagRouteDecision combinedRouteDecision = new RagPipelineService.CodeRagRouteDecision(
                searchPlan.route(), searchPlan.mode(), searchPlan.confidence(), searchPlan.queries(),
                searchPlan.commitRef(), searchPlan.targetFile(), searchPlan.targetSymbol(), searchPlan.reason(),
                searchPlan.attempted(), !searchPlan.usable());
        CodeQuestionMode plannedQuestionMode = CodeQuestionMode.from(
                questionRouter.routedMode(questionMode.value(), combinedRouteDecision));
        List<RagPipelineService.CodeSearchOperation> initialPlanOperations =
                initialDiscoveryPlanner.augmentDirectReadOnlyPlan(
                        question, searchPlan.checklist(), searchPlan.operations(), 4);
        RagPipelineService.CodeEvidenceFollowUpPlan initialTypedPlan = graphClosurePlanner.augment(
                plannedQuestionMode,
                new RagPipelineService.CodeEvidenceFollowUpPlan(
                        searchPlan.attempted(), false, searchPlan.reason(), List.of(), List.of(), List.of(),
                        searchPlan.checklist().stream()
                                .map(RagPipelineService.CodeEvidenceChecklistItem::claimId).toList(),
                        searchPlan.checklist(), initialPlanOperations, List.of(), searchPlan.hypothesis(),
                        searchPlan.hypothesisVersion(), "UNRESOLVED", List.of(), "NONE"),
                repositoryMap,
                Set.of());
        CodeRetrievalPlanValidator.PlanValidationResult initialPlanValidation = retrievalCoordinator.validateInitialPlan(
                question, initialTypedPlan, repositoryMap, Set.of());
        InitialPlanExecution initialExecution = executeInitialPlanEvidence(
                repositoryId, selectedSpaceId, spaceIds, question, plannedQuestionMode, searchLimit, searchPlan,
                initialPlanValidation.executableOperations(), merged);
        int plannedCandidateCount = initialExecution.candidatesAdded();
        LinkedHashSet<String> initialExecutedOperations = new LinkedHashSet<>(
                initialExecution.executedOperationKeys());
        log.info("Code RAG search plan attempted={} usable={} confidence={} route={} mode={} operations={} typedOperations={} checklistClaims={} candidatesAdded={} validation={} validationErrors={} reason={} question={}",
                searchPlan.attempted(), searchPlan.usable(), searchPlan.confidence(),
                searchPlan.route(), plannedQuestionMode.value(), searchPlan.queries(),
                initialTypedPlan.operations().stream().map(this::operationTrace).toList(),
                searchPlan.checklist().stream().map(RagPipelineService.CodeEvidenceChecklistItem::claimId).toList(),
                plannedCandidateCount, initialPlanValidation.code(), initialPlanValidation.errors(),
                safe(searchPlan.reason(), ""), abbreviate(question, 180));
        retrievalEvidenceIr = accumulateRetrievalEvidenceIr(
                retrievalEvidenceIr, question, plannedQuestionMode,
                EvidenceExtractionStage.POST_OPERATION, merged.values());
        repositoryMap = questionMapBuilder.update(
                repositoryMap, selectedSpaceId, spaceIds, question,
                merged.values(), initialExecution.observations(), retrievalEvidenceIr).map();
        List<CodeSearchResult> results = rankedCodeEvidence(
                question, plannedQuestionMode, merged, limit, null);
        RagPipelineService.EvidenceAssessment assessment = pipelineService.assessCode(
                question,
                results,
                minCodeEvidence(plannedQuestionMode),
                1
        );
        List<String> conversationQueries = conversationAnchorQueries(question, conversationContext);
        if (!conversationQueries.isEmpty()) {
            queryPlan = new RagPipelineService.QueryPlan(
                    queryPlan.domain(),
                    java.util.stream.Stream.concat(queryPlan.queries().stream(), conversationQueries.stream())
                            .filter(value -> value != null && !value.isBlank())
                            .distinct()
                            .toList(),
                    queryPlan.rewriteAttempted(), queryPlan.rewriteUsed(), queryPlan.rewriteFailed(),
                    queryPlan.reason());
        }
        int iteration = 1;
        int followUpCandidateCount = 0;
        RagPipelineService.CodeEvidenceFollowUpPlan followUpPlan = graphClosurePlanner.augment(
                plannedQuestionMode, enforceDirectClaimProof(
                pipelineService.planCodeEvidenceIteration(
                question,
                plannedQuestionMode.value(),
                results,
                2,
                approvedInitialChecklist(
                        question, searchPlan, initialPlanValidation.executableOperations()),
                initialExecution.observations(),
                iteration,
                hypothesisMapContext(
                        repositoryMap,
                        approvedInitialHypothesis(
                                question, searchPlan, initialPlanValidation.executableOperations()),
                        searchPlan.hypothesisVersion())
                ), repositoryMap), repositoryMap, initialExecutedOperations);
        results = applyValidatedCoverageSelections(followUpPlan, results, merged);
        int followUpQueriesUsed = 0;
        boolean followUpStoppedEarly = false;
        List<String> operationObservations = new ArrayList<>(initialExecution.observations());
        LinkedHashSet<String> executedOperations = new LinkedHashSet<>(initialExecutedOperations);
        LinkedHashSet<String> iterationQueries = new LinkedHashSet<>();
        int maxAdditionalIterations = Math.max(0, pipelineService.codeRetrievalMaxIterations() - 1);
        int completedAdditionalIterations = 0;
        int previousMeaningfulEvidenceCount = meaningfulEvidenceCount(results);
        int previousValidatedClaimCount = validatedClaimCount(results);
        int previousDirectReadEvidenceCount = directReadEvidenceCount(results);
        boolean indexChanged = false;
        int contractRepairAttempts = 0;
        CodeRetrievalPlanValidator.PlanValidationCode terminalValidationCode =
                CodeRetrievalPlanValidator.PlanValidationCode.VALID;

        while (!followUpPlan.enough()
                && completedAdditionalIterations < maxAdditionalIterations
                && System.nanoTime() < retrievalDeadlineNanos) {
            int executedThisIteration = 0;
            CodeRetrievalPlanValidator.PlanValidationResult planValidation = retrievalCoordinator.validatePlan(
                    followUpPlan, repositoryMap, executedOperations);
            terminalValidationCode = planValidation.code();
            if (!planValidation.valid()) {
                planValidation.errors().forEach(error -> operationObservations.add(
                        "phase=VALIDATE_PLAN status=" + error.code()
                                + " operationId=" + safe(error.operationId(), "")
                                + " detail=" + safe(error.detail(), "")));
                if (!planValidation.executableOperations().isEmpty()) {
                    operationObservations.add("phase=VALIDATE_PLAN status=PARTIAL_EXECUTION validOperations="
                            + planValidation.executableOperations().size());
                } else if (contractRepairAttempts++ < MAX_PLAN_CONTRACT_REPAIR_ATTEMPTS
                        && System.nanoTime() < retrievalDeadlineNanos) {
                    operationObservations.add("phase=REPAIR_PLAN status=REQUESTED validation=" + planValidation.code());
                    followUpPlan = graphClosurePlanner.augment(plannedQuestionMode,
                            enforceDirectClaimProof(pipelineService.planCodeEvidenceIteration(
                            question,
                            plannedQuestionMode.value(),
                            results,
                            2,
                            followUpPlan.checklist(),
                            operationObservations,
                            iteration,
                            hypothesisMapContext(repositoryMap, followUpPlan.hypothesis(), followUpPlan.hypothesisVersion())
                    ), repositoryMap), repositoryMap, executedOperations);
                    results = applyValidatedCoverageSelections(followUpPlan, results, merged);
                    continue;
                } else {
                    operationObservations.add("phase=REPAIR_PLAN status=FAILED validation=" + planValidation.code());
                    break;
                }
            }
            for (RagPipelineService.CodeSearchOperation requestedOperation : planValidation.executableOperations()) {
                RagPipelineService.CodeSearchOperation operation = resolveOperationOperands(requestedOperation, results);
                String operationKey = retrievalOperationKey(operation);
                if (!executedOperations.add(operationKey)) {
                    operationObservations.add(operationTrace(operation) + " status=SKIPPED_DUPLICATE");
                    continue;
                }
                CodeEvidenceOperationExecutor.Execution execution = retrievalCoordinator.executeOperation(
                        repositoryId,
                        selectedSpaceId,
                        spaceIds,
                        operation,
                        graphSearchIntent(plannedQuestionMode),
                        searchLimit,
                        retrievalOperationIntent(question, operation, followUpPlan.checklist())
                );
                operationObservations.add(operationTrace(operation)
                        + operationResultHandles(operation, execution.results())
                        + " " + execution.observation());
                int before = merged.size();
                for (CodeSearchResult result : execution.results()) {
                    CodeSearchResult marked = markLlmIterationEvidence(result, operation);
                    merge(merged, marked);
                }
                followUpCandidateCount += Math.max(0, merged.size() - before);
                followUpQueriesUsed++;
                executedThisIteration++;
                if (operation.isSearch() && !operation.query().isBlank()) {
                    iterationQueries.add(operation.query());
                }
                log.info("Code RAG retrieval operation iteration={} operationId={} claimIds={} originEvidenceIds={} type={} status={} candidates={} reason={}",
                        iteration + 1, operation.operationId(), operation.claimIds(), operation.originEvidenceIds(),
                        operation.type(), execution.status(), execution.results().size(),
                        abbreviate(execution.reason(), 160));
            }
            if (executedThisIteration == 0) {
                break;
            }
            completedAdditionalIterations++;
            iteration++;
            retrievalEvidenceIr = accumulateRetrievalEvidenceIr(
                    retrievalEvidenceIr, question, plannedQuestionMode,
                    EvidenceExtractionStage.POST_OPERATION, merged.values());
            RepositoryQuestionMapBuilder.MapUpdateResult mapUpdate = questionMapBuilder.update(
                    repositoryMap, selectedSpaceId, spaceIds, question, merged.values(), operationObservations,
                    retrievalEvidenceIr);
            repositoryMap = mapUpdate.map();
            if (mapUpdate.identityChanged()) {
                operationObservations.add("status=INDEX_CHANGED");
                indexChanged = true;
                break;
            }
            results = rankedCodeEvidence(question, plannedQuestionMode, merged, limit, followUpPlan);
            assessment = pipelineService.assessCode(
                    question, results, minCodeEvidence(plannedQuestionMode), iteration);
            RagPipelineService.CodeEvidenceFollowUpPlan previousPlan = followUpPlan;
            followUpPlan = preservePlanOnPlanningFailure(previousPlan, graphClosurePlanner.augment(
                    plannedQuestionMode, enforceDirectClaimProof(pipelineService.planCodeEvidenceIteration(
                    question,
                    plannedQuestionMode.value(),
                    results,
                    2,
                    followUpPlan.checklist(),
                    operationObservations,
                    iteration,
                    hypothesisMapContext(repositoryMap, followUpPlan.hypothesis(), followUpPlan.hypothesisVersion())
            ), repositoryMap), repositoryMap, executedOperations));
            results = applyValidatedCoverageSelections(followUpPlan, results, merged);
            queryPlan = new RagPipelineService.QueryPlan(
                    RagPipelineService.Domain.CODE,
                    java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(question), iterationQueries.stream()).distinct().toList(),
                    true,
                    true,
                    false,
                    "llm retrieval iteration " + iteration + ": " + safe(followUpPlan.reason(), "")
            );
            int currentValidatedClaimCount = validatedClaimCount(results);
            int currentMeaningfulEvidenceCount = meaningfulEvidenceCount(results);
            int currentDirectReadEvidenceCount = directReadEvidenceCount(results);
            boolean progressed = repositoryMap.evidenceProgress()
                    || currentMeaningfulEvidenceCount > previousMeaningfulEvidenceCount
                    || currentValidatedClaimCount > previousValidatedClaimCount
                    || currentDirectReadEvidenceCount > previousDirectReadEvidenceCount;
            if (!progressed) {
                if (hasNovelExecutableOperation(followUpPlan, results, executedOperations)) {
                    operationObservations.add("status=RETRIEVAL_PLAN_PROGRESS");
                } else {
                    operationObservations.add("status=NO_EVIDENCE_PROGRESS");
                    break;
                }
            }
            previousMeaningfulEvidenceCount = currentMeaningfulEvidenceCount;
            previousValidatedClaimCount = currentValidatedClaimCount;
            previousDirectReadEvidenceCount = currentDirectReadEvidenceCount;
        }
        followUpStoppedEarly = followUpPlan.enough() && completedAdditionalIterations < maxAdditionalIterations;

        int pinnedUsedCount = (int) results.stream().filter(this::isConversationPinned).count();
        long followUpSelectedCount = results.stream().filter(this::isLlmFollowUpEvidence).count();
        String terminalStatus = indexChanged
                ? "INDEX_CHANGED"
                : followUpPlan.enough()
                ? "SATISFIED"
                : System.nanoTime() >= retrievalDeadlineNanos
                        ? "BUDGET_EXHAUSTED"
                        : terminalValidationCode != CodeRetrievalPlanValidator.PlanValidationCode.VALID
                                ? terminalValidationCode.name()
                                : !"NONE".equals(followUpPlan.terminationRequest())
                                        ? followUpPlan.terminationRequest()
                                        : "NO_EVIDENCE_PROGRESS";
        log.info("Code RAG retrieval traceId={} indexVersion={} mapRevision={} hypothesisVersion={} premiseDisposition={} claimResults={} terminalStatus={} attempted={} enough={} operationsUsed={} candidatesAdded={} selected={} iterations={} earlyStop={} graphAdded={} missingAreas={} groups={} reason={} question={}",
                traceId,
                repositoryMap.indexVersion(),
                repositoryMap.revision(),
                followUpPlan.hypothesisVersion(),
                followUpPlan.premiseDisposition(),
                followUpPlan.claimResults().stream().map(result -> result.claimId() + ":" + result.status()).toList(),
                terminalStatus,
                followUpPlan.attempted(),
                followUpPlan.enough(),
                followUpQueriesUsed,
                followUpCandidateCount,
                followUpSelectedCount,
                iteration,
                followUpStoppedEarly,
                graphExpansionAdded,
                followUpPlan.missingAreas(),
                followUpPlan.requiredEvidenceGroups(),
                safe(followUpPlan.reason(), ""),
                abbreviate(question, 180));
        if (followUpPlan.attempted()) {
            log.info("Code RAG retrieval iteration detail queryAreas={} groups={} queries={} observations={} selectedFiles={}",
                    followUpPlan.queryAreas(),
                    followUpPlan.requiredEvidenceGroups(),
                    followUpPlan.followUpQueries(),
                    operationObservations,
                    selectedPathSummary(results));
        }
        return new CodeRetrieval(results, assessment, queryPlan, deterministicPlan, followUpPlan,
                followUpQueriesUsed, followUpCandidateCount, iteration, merged.size(), pinnedCandidateCount,
                pinnedUsedCount, traceId, repositoryMap.indexVersion(), repositoryMap.revision(), terminalStatus,
                retrievalEvidenceIr, combinedRouteDecision);
    }

    String retrievalOperationIntent(
            String question,
            RagPipelineService.CodeSearchOperation operation,
            List<RagPipelineService.CodeEvidenceChecklistItem> checklist
    ) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        addIfNotBlank(parts, question);
        if (operation != null && operation.isSearch()) {
            addIfNotBlank(parts, operation.query());
            addIfNotBlank(parts, operation.evidenceGroup());
            Set<String> claimIds = new LinkedHashSet<>(operation.claimIds());
            for (RagPipelineService.CodeEvidenceChecklistItem item : checklist == null ? List.<RagPipelineService.CodeEvidenceChecklistItem>of() : checklist) {
                if (!claimIds.contains(item.claimId())) continue;
                addIfNotBlank(parts, item.goal());
                addIfNotBlank(parts, item.actor());
                addIfNotBlank(parts, item.action());
                addIfNotBlank(parts, item.object());
                addIfNotBlank(parts, item.expectedOutcome());
            }
        }
        return abbreviate(String.join(" ", parts), 1600);
    }

    private void addIfNotBlank(Set<String> values, String value) {
        if (values != null && value != null && !value.isBlank()) values.add(value.trim());
    }

    String searchPlanIntent(
            String question,
            RagPipelineService.CodeEvidenceSearchPlan plan,
            List<RagPipelineService.CodeSearchOperation> approvedOperations
    ) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        addIfNotBlank(parts, question);
        if (plan != null && approvedOperations != null) {
            for (RagPipelineService.CodeSearchOperation operation : approvedOperations) {
                if (operation.isSearch()) addIfNotBlank(parts, operation.query());
            }
        }
        return abbreviate(String.join(" ", parts), 2000);
    }

    List<RagPipelineService.CodeEvidenceChecklistItem> approvedInitialChecklist(
            String question,
            RagPipelineService.CodeEvidenceSearchPlan plan,
            List<RagPipelineService.CodeSearchOperation> approvedOperations
    ) {
        if (plan == null) return List.of();
        Map<String, LinkedHashSet<String>> approvedQueries = new LinkedHashMap<>();
        Map<String, String> approvedGroups = new LinkedHashMap<>();
        for (RagPipelineService.CodeSearchOperation operation
                : approvedOperations == null ? List.<RagPipelineService.CodeSearchOperation>of() : approvedOperations) {
            for (String claimId : operation.claimIds()) {
                if (claimId == null || claimId.isBlank()) continue;
                LinkedHashSet<String> queries = approvedQueries.computeIfAbsent(
                        claimId, ignored -> new LinkedHashSet<>());
                addIfNotBlank(queries, operation.isSearch() ? operation.query() : question);
                if (operation.evidenceGroup() != null && !operation.evidenceGroup().isBlank()) {
                    approvedGroups.putIfAbsent(claimId, operation.evidenceGroup().trim());
                }
            }
        }
        Set<String> emittedClaims = new LinkedHashSet<>();
        return plan.checklist().stream()
                .filter(item -> emittedClaims.add(item.claimId()))
                .map(item -> {
                    LinkedHashSet<String> queries = approvedQueries.get(item.claimId());
                    boolean approved = queries != null && !queries.isEmpty();
                    List<String> safeQueries = approved
                            ? List.copyOf(queries)
                            : question == null || question.isBlank() ? List.of() : List.of(question.trim());
                    String safeGoal = approved ? String.join(" ", queries) : safe(question, "");
                    return new RagPipelineService.CodeEvidenceChecklistItem(
                            item.claimId(),
                            approved ? approvedGroups.getOrDefault(item.claimId(), item.claimId()) : item.claimId(),
                            safeGoal,
                            safeQueries,
                            "",
                            "",
                            "",
                            "",
                            List.of(),
                            item.requiredEvidenceKinds());
                })
                .toList();
    }

    String approvedInitialHypothesis(
            String question,
            RagPipelineService.CodeEvidenceSearchPlan plan,
            List<RagPipelineService.CodeSearchOperation> approvedOperations
    ) {
        return searchPlanIntent(question, plan, approvedOperations);
    }

    private RagPipelineService.CodeEvidenceFollowUpPlan preservePlanOnPlanningFailure(
            RagPipelineService.CodeEvidenceFollowUpPlan previous,
            RagPipelineService.CodeEvidenceFollowUpPlan next
    ) {
        if (previous == null || next == null || !next.claimResults().isEmpty()
                || !next.reason().contains("planner failed")) return next;
        String termination = next.reason().contains("budget exhausted") ? "BUDGET_EXHAUSTED" : next.terminationRequest();
        return new RagPipelineService.CodeEvidenceFollowUpPlan(
                true, false, next.reason(), previous.missingAreas(), List.of(), List.of(),
                previous.requiredEvidenceGroups(), previous.checklist(), List.of(), previous.coverageSelections(),
                previous.hypothesis(), previous.hypothesisVersion(), previous.premiseDisposition(),
                previous.claimResults(), termination);
    }

    private CodeRagLlmCallBudget.Scope openCodeRagLlmBudget() {
        int maxCalls = pipelineService.supportsCombinedCodePlanning()
                ? pipelineService.codeRetrievalMaxIterations() + 4
                : Integer.MAX_VALUE;
        return CodeRagLlmCallBudget.open(maxCalls, 1);
    }

    private RagPipelineService.CodeEvidenceFollowUpPlan enforceDirectClaimProof(
            RagPipelineService.CodeEvidenceFollowUpPlan plan,
            RepositoryQuestionMapBuilder.RepositoryQuestionMap repositoryMap
    ) {
        if (plan == null || repositoryMap == null || plan.claimResults().isEmpty()) return plan;
        List<RagPipelineService.CodeClaimResult> verified = plan.claimResults().stream()
                .map(result -> {
                    if (!result.terminalWithEvidence() || result.evidenceIds().stream()
                            .anyMatch(repositoryMap::isDirectProofEvidenceId)) return result;
                    List<String> limitations = new ArrayList<>(result.limitations());
                    limitations.add("navigation evidence does not directly prove the requested action");
                    return new RagPipelineService.CodeClaimResult(
                            result.claimId(), "UNRESOLVED", List.of(), "", limitations,
                            result.supersededByClaimId());
                })
                .toList();
        Set<String> terminalClaims = verified.stream()
                .filter(RagPipelineService.CodeClaimResult::terminalWithEvidence)
                .map(RagPipelineService.CodeClaimResult::claimId)
                .collect(java.util.stream.Collectors.toSet());
        List<String> missing = plan.checklist().stream()
                .map(RagPipelineService.CodeEvidenceChecklistItem::claimId)
                .filter(id -> !terminalClaims.contains(id))
                .toList();
        if (missing.isEmpty() && verified.equals(plan.claimResults())) return plan;
        Set<String> validEvidenceIds = verified.stream()
                .filter(RagPipelineService.CodeClaimResult::terminalWithEvidence)
                .flatMap(result -> result.evidenceIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        List<RagPipelineService.CodeEvidenceCoverageSelection> selections = plan.coverageSelections().stream()
                .filter(selection -> selection.evidenceIds().stream().anyMatch(validEvidenceIds::contains))
                .toList();
        return new RagPipelineService.CodeEvidenceFollowUpPlan(
                plan.attempted(), missing.isEmpty(), plan.reason(), missing,
                plan.followUpQueries(), plan.queryAreas(), plan.requiredEvidenceGroups(), plan.checklist(),
                plan.operations(), selections, plan.hypothesis(), plan.hypothesisVersion(),
                plan.premiseDisposition(), verified, plan.terminationRequest());
    }

    private Set<String> uncoveredEvidenceGroups(RagPipelineService.CodeEvidenceFollowUpPlan plan) {
        LinkedHashSet<String> covered = new LinkedHashSet<>();
        if (plan == null) return covered;
        plan.coverageSelections().stream()
                .map(RagPipelineService.CodeEvidenceCoverageSelection::evidenceGroup)
                .map(this::normalizeEvidenceGroupValue)
                .filter(group -> !group.isBlank() && !"unknown".equals(group))
                .forEach(covered::add);
        return plan.requiredEvidenceGroups().stream()
                .map(this::normalizeEvidenceGroupValue)
                .filter(group -> !group.isBlank() && !"unknown".equals(group))
                .filter(group -> !covered.contains(group))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private String hypothesisMapContext(
            RepositoryQuestionMapBuilder.RepositoryQuestionMap repositoryMap,
            String hypothesis,
            int hypothesisVersion
    ) {
        String map = repositoryMap == null ? "" : repositoryMap.plannerContext();
        if (hypothesis == null || hypothesis.isBlank()) return map;
        return map + "\n[PREVIOUS_HYPOTHESIS] version=" + Math.max(1, hypothesisVersion)
                + "\n" + hypothesis + "\n";
    }

    private boolean hasNovelExecutableOperation(
            RagPipelineService.CodeEvidenceFollowUpPlan plan,
            List<CodeSearchResult> candidates,
            Set<String> executedOperations
    ) {
        if (plan == null || plan.enough() || plan.operations().isEmpty()) return false;
        Set<String> uncovered = uncoveredEvidenceGroups(plan);
        Set<String> unresolvedClaims = plan.checklist().stream()
                .map(RagPipelineService.CodeEvidenceChecklistItem::claimId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        plan.claimResults().stream()
                .filter(RagPipelineService.CodeClaimResult::terminalWithEvidence)
                .map(RagPipelineService.CodeClaimResult::claimId)
                .forEach(unresolvedClaims::remove);
        for (RagPipelineService.CodeSearchOperation requested : plan.operations()) {
            RagPipelineService.CodeSearchOperation operation = resolveOperationOperands(requested, candidates);
            String group = normalizeEvidenceGroupValue(operation.evidenceGroup());
            boolean targetsUnresolvedClaim = operation.claimIds().stream().anyMatch(unresolvedClaims::contains);
            if ((targetsUnresolvedClaim || (!group.isBlank() && !"unknown".equals(group) && uncovered.contains(group)))
                    && operation.validationError().isBlank()
                    && !executedOperations.contains(retrievalOperationKey(operation))) {
                return true;
            }
        }
        return false;
    }

    private RagPipelineService.CodeSearchOperation resolveOperationOperands(
            RagPipelineService.CodeSearchOperation operation,
            List<CodeSearchResult> candidates
    ) {
        return operation;
    }

    private List<CodeSearchResult> applyValidatedCoverageSelections(
            RagPipelineService.CodeEvidenceFollowUpPlan plan,
            List<CodeSearchResult> candidates,
            Map<UUID, CodeSearchResult> merged
    ) {
        if (plan == null || candidates == null || candidates.isEmpty() || plan.coverageSelections().isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        List<CodeSearchResult> marked = new ArrayList<>(candidates);
        for (RagPipelineService.CodeEvidenceCoverageSelection selection : plan.coverageSelections()) {
            if (selection.supportedClaims().isEmpty()) continue;
            String group = normalizeEvidenceGroupValue(selection.evidenceGroup());
            if (group.isBlank() || "unknown".equals(group)) {
                continue;
            }
            LinkedHashSet<Integer> selectedIndexes = new LinkedHashSet<>();
            for (String evidenceId : selection.evidenceIds()) {
                boolean matched = false;
                for (int index = 0; index < marked.size(); index++) {
                    if (evidenceId.equals(CodeEvidenceId.from(marked.get(index)))) {
                        selectedIndexes.add(index);
                        matched = true;
                    }
                }
                if (!matched) {
                    merged.values().stream()
                            .filter(result -> evidenceId.equals(CodeEvidenceId.from(result)))
                            .findFirst()
                            .ifPresent(result -> {
                                marked.add(result);
                                selectedIndexes.add(marked.size() - 1);
                            });
                }
            }
            for (Integer evidenceIndex : selection.evidenceIndexes()) {
                if (evidenceIndex == null || evidenceIndex < 1 || evidenceIndex > marked.size()) {
                    continue;
                }
                selectedIndexes.add(evidenceIndex - 1);
            }
            for (Integer index : selectedIndexes) {
                CodeSearchResult result = marked.get(index);
                Map<String, Object> metadata = new LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
                LinkedHashSet<String> groups = new LinkedHashSet<>();
                addNormalizedMetadataValues(groups, metadata.get("llmValidatedEvidenceGroup"));
                groups.add(group);
                metadata.put("llmValidatedEvidenceGroup", List.copyOf(groups));
                metadata.put("llmValidatedEvidence", true);
                metadata.put("llmSupportedClaims", selection.supportedClaims());
                metadata.put("llmPipelineStage", selection.pipelineStage());
                CodeSearchResult validated = withMetadata(result, metadata);
                marked.set(index, validated);
                merge(merged, validated);
            }
        }
        return List.copyOf(marked);
    }

    private int validatedClaimCount(List<CodeSearchResult> evidence) {
        LinkedHashSet<String> claims = new LinkedHashSet<>();
        if (evidence != null) {
            for (CodeSearchResult result : evidence) {
                if (result == null || result.metadata() == null) continue;
                Object value = result.metadata().get("llmSupportedClaims");
                if (value instanceof Collection<?> values) {
                    values.stream().filter(java.util.Objects::nonNull).map(String::valueOf)
                            .filter(claim -> !claim.isBlank()).forEach(claims::add);
                }
            }
        }
        return claims.size();
    }

    int meaningfulEvidenceCount(List<CodeSearchResult> evidence) {
        LinkedHashSet<String> identities = new LinkedHashSet<>();
        if (evidence == null) return 0;
        for (CodeSearchResult result : evidence) {
            if (result == null || result.chunkId() == null || result.content() == null || result.content().isBlank()) continue;
            Map<String, Object> metadata = result.metadata() == null ? Map.of() : result.metadata();
            String symbolEvidenceKind = String.valueOf(metadata.getOrDefault("symbolEvidenceKind", ""));
            int span = Math.max(1, result.lineEnd() - result.lineStart() + 1);
            boolean callableBodyExplicitlyAbsent = metadata.containsKey("callableBodyPresent")
                    && !Boolean.parseBoolean(String.valueOf(metadata.get("callableBodyPresent")));
            boolean boundedStructuralIdentity = span <= 400
                    && !isProjectContext(result.chunkType())
                    && (notBlank(result.methodName()) || notBlank(result.symbolName()))
                    && !callableBodyExplicitlyAbsent;
            boolean definitionOrReference = "DEFINITION".equals(symbolEvidenceKind)
                    || "REFERENCE".equals(symbolEvidenceKind);
            if (boundedStructuralIdentity || definitionOrReference) {
                identities.add(CodeEvidenceId.from(result));
            }
        }
        return identities.size();
    }

    private int directReadEvidenceCount(List<CodeSearchResult> evidence) {
        if (evidence == null) return 0;
        return (int) evidence.stream()
                .filter(java.util.Objects::nonNull)
                .filter(result -> Boolean.TRUE.equals(metadataBoolean(result, "llmDirectRead"))
                        || Boolean.TRUE.equals(metadataBoolean(result, "llmReadFulfilled")))
                .map(CodeEvidenceId::from)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .count();
    }

    int collectSearchPlanEvidence(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            String question,
            CodeQuestionMode questionMode,
            int searchLimit,
            RagPipelineService.CodeEvidenceSearchPlan searchPlan,
            List<RagPipelineService.CodeSearchOperation> executableOperations,
            Map<UUID, CodeSearchResult> merged
    ) {
        return executeInitialPlanEvidence(
                repositoryId, selectedSpaceId, spaceIds, question, questionMode, searchLimit,
                searchPlan, executableOperations, merged).candidatesAdded();
    }

    InitialPlanExecution executeInitialPlanEvidence(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            String question,
            CodeQuestionMode questionMode,
            int searchLimit,
            RagPipelineService.CodeEvidenceSearchPlan searchPlan,
            List<RagPipelineService.CodeSearchOperation> executableOperations,
            Map<UUID, CodeSearchResult> merged
    ) {
        if (searchPlan == null || !searchPlan.usable()
                || executableOperations == null || executableOperations.isEmpty()) {
            return InitialPlanExecution.empty();
        }
        int before = merged.size();
        LinkedHashSet<String> executedOperationKeys = new LinkedHashSet<>();
        List<String> observations = new ArrayList<>();
        int perQueryLimit = Math.max(6, Math.min(searchLimit, 18));
        List<RagPipelineService.CodeEvidenceChecklistItem> approvedChecklist =
                approvedInitialChecklist(question, searchPlan, executableOperations);
        Map<String, RagPipelineService.CodeEvidenceChecklistItem> claims = approvedChecklist.stream()
                .collect(Collectors.toMap(RagPipelineService.CodeEvidenceChecklistItem::claimId, item -> item));
        for (RagPipelineService.CodeSearchOperation operation : executableOperations) {
            RagPipelineService.CodeEvidenceChecklistItem claim = operation.claimIds().stream()
                    .map(claims::get).filter(Objects::nonNull).findFirst().orElse(null);
            if (claim == null) {
                observations.add("phase=INITIAL_PLAN " + operationTrace(operation)
                        + " status=SKIPPED_UNKNOWN_CLAIM");
                continue;
            }
            CodeEvidenceOperationExecutor.Execution execution = retrievalCoordinator.executeOperation(
                    repositoryId,
                    selectedSpaceId,
                    spaceIds,
                    operation,
                    graphSearchIntent(questionMode),
                    perQueryLimit,
                    initialSearchOperationIntent(question, operation)
            );
            executedOperationKeys.add(retrievalOperationKey(operation));
            observations.add("phase=INITIAL_PLAN " + operationTrace(operation)
                    + operationResultHandles(operation, execution.results())
                    + " " + execution.observation());
            int retainedOperationResults = operation.isSearch() ? 4 : perQueryLimit;
            for (CodeSearchResult result : execution.results().stream()
                    .limit(retainedOperationResults).toList()) {
                CodeSearchResult operationMarked = operation.isSearch()
                        ? result : markLlmIterationEvidence(result, operation);
                merge(merged, markLlmSearchPlanGroupEvidence(
                        operationMarked, claim, operation.isSearch() ? operation.query() : question));
            }
        }
        return new InitialPlanExecution(
                Math.max(0, merged.size() - before),
                List.copyOf(executedOperationKeys),
                List.copyOf(observations));
    }

    record InitialPlanExecution(
            int candidatesAdded,
            List<String> executedOperationKeys,
            List<String> observations
    ) {
        InitialPlanExecution {
            candidatesAdded = Math.max(0, candidatesAdded);
            executedOperationKeys = executedOperationKeys == null ? List.of() : List.copyOf(executedOperationKeys);
            observations = observations == null ? List.of() : List.copyOf(observations);
        }

        static InitialPlanExecution empty() {
            return new InitialPlanExecution(0, List.of(), List.of());
        }
    }

    String initialSearchOperationIntent(
            String question,
            RagPipelineService.CodeSearchOperation operation
    ) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        addIfNotBlank(parts, question);
        if (operation != null && operation.isSearch()) addIfNotBlank(parts, operation.query());
        return abbreviate(String.join(" ", parts), 1600);
    }

    public static boolean shouldBlockAnswerGeneration(
            CodeEvidenceCoverageGate.Outcome coverage,
            String terminalStatus,
            RagPipelineService.CodeEvidenceFollowUpPlan plan
    ) {
        return coverage == null || !coverage.answerable();
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

    private boolean shouldUseEvidenceFallback(CodeRetrieval retrieval, List<CodeSearchResult> answerResults) {
        if (retrieval == null) {
            return true;
        }
        return !coverageGate.evaluate(
                retrieval.followUpPlan(), answerResults, retrieval.indexVersion()).answerable();
    }

    private String evidenceResponsePolicyContext(CodeEvidenceCoverageGate.Outcome outcome) {
        if (outcome == null || outcome.decision() == CodeEvidenceCoverageGate.Decision.FULL
                || outcome.decision() == CodeEvidenceCoverageGate.Decision.DENY) {
            return "";
        }
        String resolved = outcome.resolvedClaimIds().isEmpty()
                ? "no planner-validated claim; only facts directly visible in the supplied source excerpts"
                : String.join(", ", outcome.resolvedClaimIds());
        String missing = outcome.missingReasons().stream()
                .limit(6)
                .map(reason -> abbreviate(reason, 240))
                .collect(Collectors.joining("; "));
        String policy = "\n\nEvidence coverage decision: " + outcome.decision() + "."
                + "\nFirst answer the directly supported portion identified by these resolved claims: " + resolved + "."
                + "\nYou may describe a likely flow only in a separate section named '근거 기반 추정',"
                + " explicitly label every such statement as an inference, and cite the candidate evidence."
                + "\nNever present inferred calls, writes, or state transitions as verified facts."
                + " Add a final section named '확인하지 못한 부분'"
                + " and state these limitations: " + (missing.isBlank() ? "remaining claims were not verified" : missing) + ".";
        return abbreviate(policy, MAX_EVIDENCE_RESPONSE_POLICY_CHARS);
    }

    private String insufficientEvidenceAnswer(List<CodeSearchResult> results, CodeRetrieval retrieval) {
        CodeEvidenceCoverageGate.Outcome outcome = coverageGate.evaluate(
                retrieval == null ? null : retrieval.followUpPlan(),
                results,
                retrieval == null ? "" : retrieval.indexVersion()
        );
        if (outcome.decision() == CodeEvidenceCoverageGate.Decision.DISCOVERY) {
            return discoveryEvidenceAnswer(results, outcome);
        }
        String missing = outcome.missingReasons().stream()
                .limit(6)
                .map(reason -> "- " + reason)
                .collect(Collectors.joining("\n"));
        if (missing.isBlank()) {
            missing = "- 답변에 필요한 직접 코드 근거가 충분한지 확인하지 못했습니다.";
        }
        String candidates = IntStream.range(0, Math.min(results == null ? 0 : results.size(), 6))
                .mapToObj(index -> {
                    CodeSearchResult result = results.get(index);
                    return (index + 1) + ". `" + safe(result.filePath(), "unknown") + "`:"
                            + result.lineStart() + "-" + result.lineEnd() + " [" + (index + 1) + "]";
                })
                .collect(Collectors.joining("\n"));
        if (candidates.isBlank()) {
            candidates = "확인된 후보가 없습니다.";
        }
        return "현재 Retrieval Iteration으로는 답변에 필요한 코드 근거를 충분히 확인하지 못했습니다.\n\n"
                + "### 부족한 근거\n" + missing + "\n\n"
                + "### 확인된 후보\n" + candidates + "\n\n"
                + "근거가 없는 내용을 사실처럼 채우지 않기 위해 정상 답변 생성을 중단했습니다.";
    }

    private String discoveryEvidenceAnswer(
            List<CodeSearchResult> results,
            CodeEvidenceCoverageGate.Outcome outcome
    ) {
        String candidates = IntStream.range(0, Math.min(results == null ? 0 : results.size(), 8))
                .mapToObj(index -> {
                    CodeSearchResult result = results.get(index);
                    String symbol = firstNonBlank(result.methodName(), result.symbolName(), result.className());
                    return "- `" + safe(result.filePath(), "unknown") + "`:" + result.lineStart() + "-"
                            + result.lineEnd() + (symbol.isBlank() ? "" : " (`" + symbol + "`)")
                            + " [" + (index + 1) + "]";
                })
                .collect(Collectors.joining("\n"));
        if (candidates.isBlank()) {
            candidates = "- 직접 확인할 수 있는 코드 후보가 없습니다.";
        }
        String unresolved = outcome.missingReasons().stream()
                .limit(8)
                .map(reason -> "- " + reason)
                .collect(Collectors.joining("\n"));
        if (unresolved.isBlank()) {
            unresolved = "- 후보가 질문의 구체적인 동작을 직접 입증하지 못했습니다.";
        }
        return "현재 검색에서 관련 코드 후보까지는 확인했지만, 동작을 직접 입증하는 구현 본문은 아직 검증하지 못했습니다.\n\n"
                + "### 확인된 코드 후보\n" + candidates + "\n\n"
                + "### 아직 확인하지 못한 부분\n" + unresolved + "\n\n"
                + "위 후보는 탐색 결과이며, 확인되지 않은 호출 관계나 저장 동작을 사실로 단정하지 않았습니다.";
    }

    private String firstNonBlank(String... values) {
        for (String value : values == null ? new String[0] : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private CodeQueryPlan codeQueryPlan(String question) {
        String base = safe(question, "").trim();
        return base.isBlank()
                ? new CodeQueryPlan("EMPTY", List.of(), true)
                : new CodeQueryPlan("ORIGINAL_QUESTION", List.of(base), true);
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

    void collectEvidenceForQuery(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            String query,
            CodeQuestionMode questionMode,
            int limit,
            Map<UUID, CodeSearchResult> merged
    ) {
        List<CodeSearchResult> results = collectEvidence(
                repositoryId, selectedSpaceId, spaceIds, query, questionMode, limit);
        for (CodeSearchResult result : results) {
            merge(merged, result);
        }
    }

    void collectGraphExpandedEvidenceForQuery(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            String query,
            CodeQuestionMode questionMode,
            int limit,
            Map<UUID, CodeSearchResult> merged
    ) {
        List<CodeSearchResult> results = searchService.search(
                repositoryId, query, Math.max(1, Math.min(30, limit)), spaceIds, selectedSpaceId,
                graphSearchIntent(questionMode));
        for (CodeSearchResult result : results == null ? List.<CodeSearchResult>of() : results) {
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

    public static String retrievalOperationKey(RagPipelineService.CodeSearchOperation operation) {
        return CodeRetrievalCoordinator.operationKey(operation);
    }

    private String operationTrace(RagPipelineService.CodeSearchOperation operation) {
        return "operationId=" + operation.operationId()
                + " claimIds=" + operation.claimIds()
                + " originEvidenceIds=" + operation.originEvidenceIds()
                + " type=" + operation.type()
                + " target={" + retrievalOperationEvidenceIntent(operation) + "}";
    }

    public static String operationResultHandles(
            RagPipelineService.CodeSearchOperation operation,
            List<CodeSearchResult> results
    ) {
        if (operation == null || !"list_file_symbols".equals(operation.type()) || results == null) return "";
        List<String> symbols = results.stream()
                .filter(java.util.Objects::nonNull)
                .flatMap(result -> java.util.stream.Stream.of(result.methodName(), result.symbolName()))
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .limit(32)
                .toList();
        return symbols.isEmpty() ? "" : " observedSymbols=" + symbols;
    }

    CodeSearchResult markLlmIterationEvidence(
            CodeSearchResult result,
            RagPipelineService.CodeSearchOperation operation
    ) {
        CodeSearchResult marked = markLlmFollowUpEvidence(
                result,
                retrievalOperationEvidenceIntent(operation)
        );
        if (operation.evidenceGroup() == null || operation.evidenceGroup().isBlank()) {
            return marked;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(marked.metadata() == null ? Map.of() : marked.metadata());
        metadata.put("llmEvidenceCoverageGroup", operation.evidenceGroup());
        metadata.put("llmChecklistGroup", operation.evidenceGroup());
        if (operationProducesFocusedEvidence(operation, result)) {
            metadata.put("llmChecklistGroupRequired", true);
        }
        metadata.put("llmRetrievalIterationEvidence", true);
        return withMetadata(marked, metadata);
    }

    String retrievalOperationEvidenceIntent(RagPipelineService.CodeSearchOperation operation) {
        if (operation == null) return "";
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        addIfNotBlank(parts, operation.type());
        if (operation.isSearch()) {
            addIfNotBlank(parts, operation.query());
        } else {
            addIfNotBlank(parts, operation.path());
            addIfNotBlank(parts, operation.symbol());
            addIfNotBlank(parts, operation.chunkId());
            if (operation.lineStart() != null) parts.add("lineStart=" + operation.lineStart());
            if (operation.lineEnd() != null) parts.add("lineEnd=" + operation.lineEnd());
            if (operation.radius() != null) parts.add("radius=" + operation.radius());
            operation.relations().forEach(value -> addIfNotBlank(parts, value));
        }
        return abbreviate(String.join(" ", parts), 1_600);
    }

    public static boolean operationProducesFocusedEvidence(
            RagPipelineService.CodeSearchOperation operation,
            CodeSearchResult result
    ) {
        return operation != null && switch (operation.type()) {
            case "read_symbol" -> result != null && !operation.symbol().isBlank()
                    && (operation.symbol().equals(result.methodName())
                    || operation.symbol().equals(result.symbolName()));
            case "find_endpoint", "read_chunk", "read_file_range", "read_adjacent", "traverse_graph" -> true;
            default -> false;
        };
    }

    private GraphSearchIntent graphSearchIntent(CodeQuestionMode questionMode) {
        return switch (questionMode) {
            case CALL_FLOW -> GraphSearchIntent.FLOW;
            case IMPACT -> GraphSearchIntent.IMPACT;
            case OVERVIEW -> GraphSearchIntent.OVERVIEW;
            default -> GraphSearchIntent.LOCATE;
        };
    }

    private List<CodeSearchResult> rankedCodeEvidence(
            String question,
            CodeQuestionMode questionMode,
            Map<UUID, CodeSearchResult> merged,
            int limit,
        RagPipelineService.CodeEvidenceFollowUpPlan followUpPlan
    ) {
        List<CodeSearchResult> ranked = evidenceRanker.rank(question, questionMode, List.copyOf(merged.values()));
        int selectionLimit = candidateSlateLimit(limit);
        CodeEvidenceRetentionPlan retentionPlan = evidenceRetentionPlan(question, ranked);
        List<CodeSearchResult> selected = CodeEvidenceSelectionPolicy.select(
                ranked, selectionLimit, retentionPlan);
        Set<UUID> retainedChunkIds = selected.stream()
                .filter(result -> retentionPlan.lookup(CodeEvidenceId.from(result)).isPresent())
                .map(CodeSearchResult::chunkId)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        selected = ensureMarkedChecklistGroupCoverage(ranked, selected, selectionLimit);
        return CodeEvidenceFileDiversity.select(
                ranked, selected, selectionLimit,
                result -> result != null && retainedChunkIds.contains(result.chunkId()));
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
        List<CodeSearchResult> ranked = evidenceRanker.rank(question, questionMode, List.copyOf(merged.values()));
        List<CodeSearchResult> selected = ranked.stream()
                .limit(limit)
                .toList();
        return limitedMutable(selected, limit);
    }

    private List<CodeSearchResult> answerContextResults(
            CodeQuestionMode questionMode,
            String question,
            List<CodeSearchResult> results,
            RagPipelineService.CodeEvidenceFollowUpPlan followUpPlan,
            CodeEvidenceIr retrievalIr
    ) {
        int configuredLimit = pipelineService.codeContextLimit(
                questionMode == CodeQuestionMode.OVERVIEW ? OVERVIEW_CONTEXT_LIMIT : DEFAULT_CONTEXT_LIMIT);
        int limit = Math.max(configuredLimit, llmChecklistGroups(followUpPlan).size());
        List<CodeSearchResult> ranked = evidenceRanker.rank(question, questionMode, results);
        List<RagPipelineService.CodeEvidenceChecklistItem> checklist = followUpPlan == null ? List.of() : followUpPlan.checklist();
        boolean claimSelectionsValidated = followUpPlan != null && followUpPlan.enough()
                && !followUpPlan.coverageSelections().isEmpty()
                && coverageGate.evaluate(followUpPlan, ranked).sufficient();
        CodeEvidenceCoverageGate.Outcome rankedCoverage = followUpPlan == null
                ? null : coverageGate.evaluate(followUpPlan, ranked);
        boolean verifiedSelectionsRetained = rankedCoverage != null && !rankedCoverage.resolvedClaimIds().isEmpty();
        boolean noVerifiedClaimDecision = pipelineService.supportsCombinedCodePlanning()
                && followUpPlan != null
                && !followUpPlan.claimResults().isEmpty()
                && followUpPlan.claimResults().stream()
                        .noneMatch(RagPipelineService.CodeClaimResult::terminalWithEvidence);
        boolean skipAdjudication = claimSelectionsValidated || noVerifiedClaimDecision || verifiedSelectionsRetained;
        RagPipelineService.CodeEvidenceAdjudication adjudication = skipAdjudication
                ? new RagPipelineService.CodeEvidenceAdjudication(false, false,
                        noVerifiedClaimDecision
                                ? "no verified claim is available for adjudication"
                                : "verifier-selected claim evidence is retained",
                        ranked)
                : pipelineService.adjudicateCodeEvidence(question, questionMode.value(), ranked, limit, checklist);
        if (adjudication.used()) {
            ranked = adjudication.results();
            List<CodeSearchResult> selected = preservePinnedEvidence(ranked, llmEvidenceSlateSelection(ranked, limit), limit);
            selected = ensureLlmChecklistGroupCoverage(followUpPlan, ranked, selected, limit);
            selected = preferExactRequestedSymbolEvidence(question, ranked, selected);
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
            return finalizeAnswerEvidence(question, ranked, selected, limit, followUpPlan, retrievalIr);
        }
        if (adjudication.attempted()) {
            ranked = adjudication.results();
        }
        List<CodeSearchResult> selected = ranked.stream().limit(limit).toList();
        selected = sourceAwareEvidenceSelection(questionMode, ranked, selected, limit);
        selected = ensureLlmChecklistGroupCoverage(followUpPlan, ranked, selected, limit);
        selected = preferStructuredEvidence(questionMode, ranked, selected, limit);
        return finalizeAnswerEvidence(question, ranked, selected, limit, followUpPlan, retrievalIr);
    }

    private List<CodeSearchResult> finalizeAnswerEvidence(
            String question,
            List<CodeSearchResult> ranked,
            List<CodeSearchResult> selected,
            int limit,
            RagPipelineService.CodeEvidenceFollowUpPlan followUpPlan,
            CodeEvidenceIr retrievalIr
    ) {
        List<CodeSearchResult> preserved = preservePinnedEvidence(ranked, selected, limit);
        CodeEvidenceRetentionPlan retentionPlan = answerEvidenceRetentionPlan(
                question, ranked, followUpPlan, retrievalIr);
        return CodeEvidenceSelectionPolicy.selectFinalEvidence(
                ranked, preserved, retentionPlan, limit);
    }

    private List<CodeSearchResult> preferExactRequestedSymbolEvidence(
            String question,
            List<CodeSearchResult> ranked,
            List<CodeSearchResult> selected
    ) {
        if (question == null || question.isBlank() || ranked == null || selected == null || selected.isEmpty()) {
            return selected == null ? List.of() : selected;
        }
        Set<String> requested = new LinkedHashSet<>();
        Matcher matcher = RESOURCE_IDENTIFIER_PATTERN.matcher(question);
        while (matcher.find()) {
            String identifier = matcher.group().toLowerCase(Locale.ROOT);
            requested.add(identifier);
            for (String segment : identifier.split("\\.")) {
                if (!segment.isBlank()) {
                    requested.add(segment);
                }
            }
        }
        List<CodeSearchResult> adjusted = new ArrayList<>(selected);
        for (CodeSearchResult exact : ranked) {
            String symbol = firstNonBlank(exact.methodName(), exact.symbolName());
            if (symbol.isBlank() || !requested.contains(symbol.toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (int index = 0; index < adjusted.size(); index++) {
                CodeSearchResult broad = adjusted.get(index);
                if (!safe(broad.filePath(), "").equals(safe(exact.filePath(), ""))
                        || broad.lineStart() > exact.lineStart() || broad.lineEnd() < exact.lineEnd()
                        || (broad.methodName() != null && !broad.methodName().isBlank())) {
                    continue;
                }
                Map<String, Object> metadata = new LinkedHashMap<>(exact.metadata() == null ? Map.of() : exact.metadata());
                if (broad.metadata() != null) {
                    copyMetadataValue(broad.metadata(), metadata, "llmValidatedEvidenceGroup");
                    copyMetadataValue(broad.metadata(), metadata, "llmValidatedEvidence");
                    copyMetadataValue(broad.metadata(), metadata, "llmChecklistGroupRequired");
                    copyMetadataValue(broad.metadata(), metadata, "llmChecklistGroup");
                }
                adjusted.set(index, withMetadata(exact, metadata));
                break;
            }
        }
        return List.copyOf(adjusted);
    }

    private void copyMetadataValue(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
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
            int selectedGroupIndex = IntStream.range(0, adjusted.size())
                    .filter(index -> llmCoverageGroups(adjusted.get(index)).contains(group))
                    .findFirst()
                    .orElse(-1);
            if (selectedGroupIndex >= 0) {
                adjusted.set(selectedGroupIndex, markLlmChecklistGroupRequired(adjusted.get(selectedGroupIndex), group));
                continue;
            }
            CodeSearchResult replacement = ranked.stream()
                    .filter(result -> !containsChunk(adjusted, result))
                    .filter(result -> llmCoverageGroups(result).contains(group))
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
        return limitedMutable(adjusted, coverageLimit);
    }

    private List<String> llmChecklistGroups(RagPipelineService.CodeEvidenceFollowUpPlan followUpPlan) {
        if (followUpPlan == null) {
            return List.of();
        }
        LinkedHashSet<String> groups = new LinkedHashSet<>();
        addLlmChecklistGroups(groups, followUpPlan.requiredEvidenceGroups());
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
        return selected != null && selected.stream().anyMatch(result -> llmCoverageGroups(result).contains(group));
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
                .flatMap(result -> llmCoverageGroups(result).stream())
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
                    .filter(result -> llmCoverageGroups(result).contains(group))
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

    private Set<String> llmCoverageGroups(CodeSearchResult result) {
        LinkedHashSet<String> groups = new LinkedHashSet<>();
        if (result == null || result.metadata() == null) {
            return groups;
        }
        addNormalizedMetadataValues(groups, result.metadata().get("llmValidatedEvidenceGroup"));
        return groups;
    }

    private void addNormalizedMetadataValues(Set<String> values, Object raw) {
        if (raw instanceof Collection<?> collection) {
            collection.forEach(item -> addNormalizedMetadataValues(values, item));
            return;
        }
        String normalized = normalizeEvidenceGroupValue(raw == null ? "" : String.valueOf(raw));
        if (!normalized.isBlank() && !"unknown".equals(normalized)) {
            values.add(normalized);
        }
    }

    private CodeSearchResult markLlmChecklistGroupRequired(CodeSearchResult result, String group) {
        if (result == null || group == null || group.isBlank()) {
            return result;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
        metadata.put("llmChecklistGroupRequired", true);
        metadata.put("llmChecklistGroup", group);
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
            Set<String> resultGroups = llmCoverageGroups(result);
            boolean preservesOnlyRequiredGroup = resultGroups.stream()
                    .filter(groups::contains)
                    .anyMatch(group -> selected.stream()
                            .filter(item -> llmCoverageGroups(item).contains(group))
                            .count() <= 1);
            if (preservesOnlyRequiredGroup) {
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
            List<CodeSearchResult> ranked,
            List<CodeSearchResult> selected,
            int limit
    ) {
        List<CodeSearchResult> adjusted = new ArrayList<>(selected == null ? List.of() : selected);
        if (ranked == null || ranked.isEmpty() || adjusted.isEmpty()) {
            return adjusted;
        }
        int targetStructured = structuredEvidenceTarget(questionMode, limit);
        while (structuredEvidenceCount(adjusted) < targetStructured) {
            CodeSearchResult replacement = ranked.stream()
                    .filter(result -> !containsChunk(adjusted, result))
                    .filter(this::isStructuredEvidenceCandidate)
                    .findFirst()
                    .orElse(null);
            if (replacement == null) {
                break;
            }
            int replaceIndex = weakestLineWindowIndex(adjusted);
            if (replaceIndex < 0 && questionMode == CodeQuestionMode.CALL_FLOW) {
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

    private int structuredEvidenceTarget(CodeQuestionMode questionMode, int limit) {
        int safeLimit = Math.max(1, limit);
        if (questionMode == CodeQuestionMode.CALL_FLOW) {
            return Math.min(safeLimit, Math.max(4, safeLimit / 2));
        }
        return switch (questionMode) {
            case OVERVIEW, CALL_FLOW, REASONING, IMPACT -> Math.min(safeLimit, Math.max(2, safeLimit / 3));
            default -> Math.min(safeLimit, 1);
        };
    }

    private long structuredEvidenceCount(List<CodeSearchResult> results) {
        return results == null ? 0 : results.stream().filter(this::isStructuredEvidenceCandidate).count();
    }

    private boolean isStructuredEvidenceCandidate(CodeSearchResult result) {
        if (result == null || isProjectContext(result.chunkType()) || isLineWindowEvidence(result)) {
            return false;
        }
        if (!isMainImplementationEvidence(result)) {
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

    private List<CodeSearchResult> sourceAwareEvidenceSelection(
            CodeQuestionMode questionMode,
            List<CodeSearchResult> ranked,
            List<CodeSearchResult> selected,
            int limit
    ) {
        List<CodeSearchResult> adjusted = new ArrayList<>(selected == null ? List.of() : selected);
        if (ranked == null || ranked.isEmpty() || adjusted.isEmpty()) {
            return adjusted;
        }
        int requiredMain = switch (questionMode) {
            case OVERVIEW, CALL_FLOW, REASONING, IMPACT -> Math.min(2, Math.max(1, limit));
            default -> 1;
        };
        while (mainImplementationCount(adjusted) < requiredMain) {
            CodeSearchResult replacement = ranked.stream()
                    .filter(result -> !containsChunk(adjusted, result))
                    .filter(this::isMainImplementationEvidence)
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

    private int mainImplementationCount(List<CodeSearchResult> results) {
        return (int) results.stream()
                .filter(this::isMainImplementationEvidence)
                .count();
    }

    private boolean isMainImplementationEvidence(CodeSearchResult result) {
        if (result == null || isProjectContext(result.chunkType())) {
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
                     || CodeSourceClassifier.SOURCE_VENDOR.equals(CodeSourceClassifier.sourceRole(result)))) {
                return index;
            }
        }
        for (int index = selected.size() - 1; index >= 0; index--) {
            if (!isRequiredConversationPinned(selected.get(index)) && !isMainImplementationEvidence(selected.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private boolean containsChunk(List<CodeSearchResult> results, CodeSearchResult candidate) {
        return candidate != null && results.stream().anyMatch(result -> result.chunkId().equals(candidate.chunkId()));
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

    private OllamaClient.ChatResult chatWithLimit(
            String systemPrompt,
            String userPrompt,
            int maxOutputTokens,
            CodeAnswerGenerator.Phase phase
    ) {
        CodeRagLlmCallBudget.acquireGeneration("answer generation");
        CodeAnswerGenerator.GenerationResult result = answerGenerator.generate(
                new CodeAnswerGenerator.GenerationRequest(
                        phase, systemPrompt, userPrompt, maxOutputTokens));
        if (result != null) {
            return toChatResult(result);
        }
        CodeRagLlmCallBudget.acquireGeneration("answer generation fallback");
        return ollamaClient.chatResult(systemPrompt, userPrompt);
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
                    continuationOutputTokens(questionMode, attempts),
                    CodeAnswerGenerator.Phase.CONTINUATION
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
        CodeRagLlmCallBudget.acquireGeneration("streaming answer generation");
        CodeAnswerGenerator.GenerationResult generated = answerGenerator.stream(
                CodeAnswerGenerator.GenerationRequest.initial(
                        systemPrompt, userPrompt, maxOutputTokens),
                delta -> {
                    streamedAnswer.append(delta);
                    streamSink.onDelta(delta);
                });
        return toChatResult(generated);
    }

    private OllamaClient.ChatResult toChatResult(CodeAnswerGenerator.GenerationResult generated) {
        return new OllamaClient.ChatResult(
                generated.answer(),
                generated.doneReason(),
                generated.done(),
                generated.promptTokens(),
                generated.outputTokens(),
                generated.baseUrl(),
                generated.model(),
                generated.role(),
                generated.fallbackUsed()
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
            metadata.put("citationKind", citationKind(result));
            metadata.put("evidenceResponsibility", role.isBlank() ? "unknown" : role);
        } else {
            metadata.put("debugHeuristicCitationKind", heuristicCitationKind(result));
            metadata.put("debugHeuristicEvidenceResponsibility", "supporting_context");
        }
        CodeAnalysisDiagnosticMetadata diagnostic = CodeAnalysisDiagnosticMetadata.from(result);
        if (diagnostic.present()) {
            metadata.put("analysisDiagnosticStatus", diagnostic.status());
            metadata.put("analysisDiagnosticScope", diagnostic.scope());
            if (!diagnostic.stage().isBlank()) metadata.put("analysisDiagnosticStage", diagnostic.stage());
            if (!diagnostic.language().isBlank()) metadata.put("analysisDiagnosticLanguage", diagnostic.language());
            if (!diagnostic.analyzer().isBlank()) metadata.put("analysisDiagnosticAnalyzer", diagnostic.analyzer());
            if (diagnostic.authority().rank() > 0) {
                metadata.put("analysisDiagnosticAuthority", diagnostic.authority().name());
            }
        }
        return Map.copyOf(metadata);
    }

    String preview(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        // ContextAssembler already produced the canonical bounded excerpt used by the model.
        // Returning that same text keeps API evidence and answer-verification evidence identical.
        return content;
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

    private String answerQualityFailureReason(
            String question,
            String answer,
            List<CodeSearchResult> evidence,
            String doneReason,
            CodeEvidenceIr evidenceIr
    ) {
        var verification = answerVerifier.verify(question, answer, evidence, doneReason, true, evidenceIr);
        return verification.accepted() ? null : verification.reason();
    }

    private CodeEvidenceRetentionPlan evidenceRetentionPlan(
            String question,
            List<CodeSearchResult> evidence
    ) {
        return CodeEvidenceRetentionPlan.from(
                adjudicateBoundedEvidence(question, evidence).evidenceIr());
    }

    private CodeEvidenceRetentionPlan answerEvidenceRetentionPlan(
            String question,
            List<CodeSearchResult> evidence,
            RagPipelineService.CodeEvidenceFollowUpPlan followUpPlan,
            CodeEvidenceIr retrievalIr
    ) {
        return CodeEvidenceRetentionPlan.from(retrievalIr)
                .merge(evidenceRetentionPlan(question, evidence))
                .merge(validatedClaimRetentionPlan(followUpPlan, evidence))
                .merge(externalRequiredRetentionPlan(evidence));
    }

    private CodeEvidenceRetentionPlan validatedClaimRetentionPlan(
            RagPipelineService.CodeEvidenceFollowUpPlan followUpPlan,
            List<CodeSearchResult> evidence
    ) {
        if (followUpPlan == null || evidence == null || evidence.isEmpty()) {
            return CodeEvidenceRetentionPlan.empty();
        }
        Map<String, CodeSearchResult> byEvidenceId = evidence.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        CodeEvidenceId::from,
                        result -> result,
                        (left, right) -> evidenceRanker.score(right) > evidenceRanker.score(left)
                                ? right : left,
                        LinkedHashMap::new));
        Map<String, CodeEvidenceRetentionPlan.Entry> entries = new LinkedHashMap<>();
        for (RagPipelineService.CodeClaimResult claim : followUpPlan.claimResults()) {
            if (claim == null || !claim.terminalWithEvidence()) continue;
            String group = "claim:" + normalizeEvidenceGroupValue(claim.claimId());
            for (String evidenceId : claim.evidenceIds()) {
                CodeSearchResult source = byEvidenceId.get(evidenceId);
                if (source == null) continue;
                CodeEvidenceRetentionPlan.Entry current = entries.get(evidenceId);
                LinkedHashSet<String> groups = new LinkedHashSet<>(
                        current == null ? Set.of() : current.groups());
                if (!group.endsWith(":")) groups.add(group);
                CodeIntelligenceAuthority authority = CodeEvidenceItem.authority(source);
                if (current != null && current.authority().rank() > authority.rank()) {
                    authority = current.authority();
                }
                entries.put(evidenceId, new CodeEvidenceRetentionPlan.Entry(
                        CodeEvidenceRetentionPlan.Level.REQUIRED, authority, groups));
            }
        }
        return CodeEvidenceRetentionPlan.of(entries);
    }

    private CodeEvidenceRetentionPlan externalRequiredRetentionPlan(List<CodeSearchResult> evidence) {
        Map<String, CodeEvidenceRetentionPlan.Entry> entries = new LinkedHashMap<>();
        for (CodeSearchResult result : evidence == null ? List.<CodeSearchResult>of() : evidence) {
            if (result == null || (!isConversationPinned(result)
                    && !Boolean.TRUE.equals(metadataBoolean(result, "llmEvidenceSlateMustUse")))) {
                continue;
            }
            String evidenceId = CodeEvidenceId.from(result);
            if (evidenceId.isBlank()) continue;
            entries.put(evidenceId, new CodeEvidenceRetentionPlan.Entry(
                    CodeEvidenceRetentionPlan.Level.REQUIRED,
                    CodeEvidenceItem.authority(result),
                    Set.of("external:" + evidenceId)));
        }
        return CodeEvidenceRetentionPlan.of(entries);
    }

    private Set<String> requiredEvidenceIds(CodeEvidenceRetentionPlan retentionPlan) {
        CodeEvidenceRetentionPlan safePlan = retentionPlan == null
                ? CodeEvidenceRetentionPlan.empty() : retentionPlan;
        return safePlan.entries().entrySet().stream()
                .filter(entry -> entry.getValue().level() == CodeEvidenceRetentionPlan.Level.REQUIRED)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    private CodeEvidenceAdjudicator.Adjudication adjudicateBoundedEvidence(
            String question,
            List<CodeSearchResult> evidence
    ) {
        return adjudicateBoundedEvidence(question, evidence, CodeEvidenceIr.empty());
    }

    private CodeEvidenceAdjudicator.Adjudication adjudicateBoundedEvidence(
            String question,
            List<CodeSearchResult> evidence,
            CodeEvidenceIr retainedIr
    ) {
        List<CodeSearchResult> bounded = evidence == null ? List.of() : evidence.stream()
                .filter(Objects::nonNull)
                .limit(PRESELECTION_IR_EVIDENCE_LIMIT)
                .toList();
        CodeEvidenceAccumulator.Accumulation operationEvidence = evidenceAccumulator.accumulate(
                retainedIr == null ? CodeEvidenceIr.empty() : retainedIr,
                new CodeEvidenceExtractionContext(
                        question, EvidenceExtractionStage.POST_OPERATION, bounded,
                        PRESELECTION_IR_EVIDENCE_LIMIT));
        CodeEvidenceAccumulator.Accumulation answerEvidence = evidenceAccumulator.accumulate(
                operationEvidence.accumulated(),
                new CodeEvidenceExtractionContext(
                        question, EvidenceExtractionStage.PRE_ANSWER, bounded,
                        PRESELECTION_IR_EVIDENCE_LIMIT));
        return evidenceAdjudicator.adjudicate(answerEvidence.accumulated());
    }

    private CodeEvidenceIr accumulateRetrievalEvidenceIr(
            CodeEvidenceIr current,
            String question,
            CodeQuestionMode questionMode,
            EvidenceExtractionStage stage,
            Collection<CodeSearchResult> evidence
    ) {
        List<CodeSearchResult> ranked = evidenceRanker.rank(
                question,
                questionMode == null ? CodeQuestionMode.OVERVIEW : questionMode,
                evidence == null ? List.of() : evidence.stream().filter(Objects::nonNull).toList())
                .stream()
                .limit(PRESELECTION_IR_EVIDENCE_LIMIT)
                .toList();
        return evidenceAccumulator.accumulate(
                current == null ? CodeEvidenceIr.empty() : current,
                new CodeEvidenceExtractionContext(
                        question, stage, ranked, PRESELECTION_IR_EVIDENCE_LIMIT))
                .accumulated();
    }

    private CodeEvidenceAdjudicator.Adjudication adjudicateAnswerEvidence(
            String question,
            List<CodeSearchResult> renderedEvidence,
            CodeEvidenceIr retrievalIr
    ) {
        Set<String> selectedEvidenceIds = (renderedEvidence == null
                ? List.<CodeSearchResult>of() : renderedEvidence).stream()
                .filter(Objects::nonNull)
                .map(CodeEvidenceId::from)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        CodeEvidenceIr retained = (retrievalIr == null ? CodeEvidenceIr.empty() : retrievalIr)
                .retainNavigationEvidence(selectedEvidenceIds);
        return adjudicateBoundedEvidence(question, renderedEvidence, retained);
    }

    private AnswerPromptSupport answerPromptSupport(
            String question,
            List<CodeSearchResult> renderedEvidence,
            CodeRetrieval retrieval
    ) {
        List<CodeSearchResult> safeEvidence = renderedEvidence == null ? List.of() : renderedEvidence;
        CodeEvidenceAdjudicator.Adjudication typedAdjudication = adjudicateAnswerEvidence(
                question, safeEvidence, retrieval == null ? CodeEvidenceIr.empty() : retrieval.evidenceIr());
        CodeEvidenceCoverageGate.Outcome responseCoverage = coverageGate.evaluate(
                retrieval == null ? null : retrieval.followUpPlan(),
                safeEvidence,
                retrieval == null ? "" : retrieval.indexVersion());
        String fidelitySuffix = CodeEvidenceIrFidelity.promptFacts(
                question, typedAdjudication.evidenceIr(), safeEvidence);
        String suffix = evidenceResponsePolicyContext(responseCoverage) + fidelitySuffix;
        return new AnswerPromptSupport(typedAdjudication, responseCoverage, suffix);
    }

    private List<String> typedEvidenceDiagnostics(
            List<String> diagnostics,
            CodeEvidenceAdjudicator.Adjudication adjudication
    ) {
        List<String> notes = new ArrayList<>(diagnostics == null ? List.of() : diagnostics);
        if (adjudication == null) {
            return notes;
        }
        var ir = adjudication.evidenceIr();
        notes.add("Code Intelligence IR: items=" + ir.evidenceItems().size()
                + ", facts=" + ir.facts().size()
                + ", constraints=" + ir.constraints().size()
                + ", signals=" + ir.signals().size()
                + ", navigationHandles=" + ir.navigationHandles().size()
                + ", constraintsSatisfied=" + adjudication.constraintsSatisfied()
                + ", violations=" + adjudication.violations().size() + ".");
        return notes;
    }

    private List<String> buildDiagnostics(
            CodeQuestionMode questionMode,
            List<CodeSearchResult> retrievedEvidence,
            List<CodeSearchResult> selectedEvidence,
            String answer,
            String doneReason,
            boolean llmUnavailable,
            boolean answerRewritten,
            boolean answerRetried,
            boolean answerContinued,
            boolean answerKeptAfterStreamValidation,
            AnswerQualityTrace answerQualityTrace,
            CodeRetrieval retrieval,
            int contextBudgetDropped,
            RagPipelineService.CodeRagRouteDecision routeDecision,
            boolean commitFallbackUsed,
            String originalQuestion,
            String effectiveQuestion,
            RagConversationContext conversationContext
    ) {
        CitationQuality citation = citationQuality(answer, selectedEvidence);
        CodeRagDiagnosticsBuilder.CitationTrace citationTrace = new CodeRagDiagnosticsBuilder.CitationTrace(
                citation.referencedCount(), citation.invalidCount(), citation.coveragePercent(), citation.summary());
        CodeRagDiagnosticsBuilder.QualityTrace qualityTrace = answerQualityTrace == null
                ? CodeRagDiagnosticsBuilder.QualityTrace.empty()
                : new CodeRagDiagnosticsBuilder.QualityTrace(
                        answerQualityTrace.observed(), answerQualityTrace.summary());
        CodeRagDiagnosticsBuilder.DeterministicPlanTrace deterministicPlan = retrieval == null
                || retrieval.deterministicPlan() == null
                ? null
                : new CodeRagDiagnosticsBuilder.DeterministicPlanTrace(
                        retrieval.deterministicPlan().intent(),
                        retrieval.deterministicPlan().queries(),
                        retrieval.deterministicPlan().originalOnlyFallback());
        CodeRagDiagnosticsBuilder.RetrievalTrace retrievalTrace = retrieval == null
                ? null
                : new CodeRagDiagnosticsBuilder.RetrievalTrace(
                        retrieval.assessment(), retrieval.queryPlan(), deterministicPlan,
                        retrieval.followUpPlan(), retrieval.followUpQueriesUsed(),
                        retrieval.followUpCandidateCount(), retrieval.iteration(),
                        retrieval.traceId(), retrieval.indexVersion(), retrieval.mapRevision(),
                        retrieval.terminalStatus());
        CodeRagDiagnosticsBuilder.ConversationTrace conversationTrace = conversationContext == null
                ? null
                : new CodeRagDiagnosticsBuilder.ConversationTrace(
                        conversationContext.contextual(),
                        conversationContext.codeAnchors() == null ? 0 : conversationContext.codeAnchors().size(),
                        retrieval == null ? 0 : retrieval.pinnedCandidateCount(),
                        retrieval == null ? 0 : retrieval.pinnedUsedCount(),
                        originalQuestion, effectiveQuestion);
        return diagnosticsBuilder.build(new CodeRagDiagnosticsBuilder.Request(
                questionMode,
                retrievedEvidence,
                selectedEvidence,
                answer,
                doneReason,
                "낮음".equals(confidence(retrievedEvidence)),
                llmUnavailable,
                answerRewritten,
                answerRetried,
                answerContinued,
                answerKeptAfterStreamValidation,
                citationTrace,
                qualityTrace,
                retrievalTrace,
                contextBudgetDropped,
                new CodeRagDiagnosticsBuilder.RouteTrace(routeDecision, commitFallbackUsed),
                conversationTrace
        ));
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
        return terms.stream()
                .map(this::normalizeCodeText)
                .filter(term -> term.length() >= 2 && !isQuestionStopWord(term))
                .distinct()
                .toList();
    }

    private boolean isQuestionStopWord(String term) {
        return List.of("관련", "파일", "어디", "있어", "있나요", "어떻게", "동작", "설명", "위치", "찾아", "찾기", "코드").contains(term);
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

    private void merge(Map<UUID, CodeSearchResult> merged, CodeSearchResult result) {
        CodeSearchResult current = merged.get(result.chunkId());
        if (current == null) {
            merged.put(result.chunkId(), result);
            return;
        }
        CodeSearchResult preferred = current;
        if (isConversationPinned(result) && !isConversationPinned(current)) {
            preferred = result;
        } else if (isConversationPinned(current) == isConversationPinned(result) && result.score() > current.score()) {
            preferred = result;
        }
        Map<String, Object> metadata = mergeEvidenceMetadata(preferred, current, result);
        merged.put(result.chunkId(), new CodeSearchResult(
                preferred.chunkId(), preferred.repositoryId(), preferred.fileId(), preferred.repositoryName(), preferred.filePath(),
                preferred.chunkType(), preferred.symbolName(), preferred.className(), preferred.methodName(), preferred.namespaceName(),
                preferred.controlName(), preferred.eventName(), preferred.chunkIndex(), preferred.lineStart(), preferred.lineEnd(),
                preferred.content(), Math.max(current.score(), result.score()), Map.copyOf(metadata)
        ));
    }

    public static Map<String, Object> mergeEvidenceMetadata(
            CodeSearchResult preferred,
            CodeSearchResult current,
            CodeSearchResult incoming
    ) {
        return CodeEvidenceAccumulator.mergeMetadata(preferred, current, incoming);
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
        if ("record".equals(result.chunkType()) || result.chunkType() != null && result.chunkType().endsWith("_summary")) {
            return "direct_code_support";
        }
        return "direct_code";
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

    private boolean isGraphExpanded(CodeSearchResult result) {
        return result != null && result.metadata() != null && Boolean.TRUE.equals(result.metadata().get("graphExpanded"));
    }

    private static String safe(String value, String fallback) {
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
            int pinnedUsedCount,
            String traceId,
            String indexVersion,
            long mapRevision,
            String terminalStatus,
            CodeEvidenceIr evidenceIr,
            RagPipelineService.CodeRagRouteDecision routeDecision
    ) {
        private CodeRetrieval {
            evidenceIr = evidenceIr == null ? CodeEvidenceIr.empty() : evidenceIr;
        }
    }

    private record AnswerPromptSupport(
            CodeEvidenceAdjudicator.Adjudication typedAdjudication,
            CodeEvidenceCoverageGate.Outcome responseCoverage,
            String suffix
    ) {
        private AnswerPromptSupport {
            suffix = suffix == null ? "" : suffix;
        }
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

}
