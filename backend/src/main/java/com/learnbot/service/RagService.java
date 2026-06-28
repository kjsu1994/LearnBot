package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.AdminTuningMetricSample;
import com.learnbot.dto.AnswerEvidence;
import com.learnbot.dto.AskResponse;
import com.learnbot.dto.DocumentChunkDetail;
import com.learnbot.dto.DocumentConversationAnchor;
import com.learnbot.dto.PreviousAnswerItem;
import com.learnbot.dto.RagConversationContext;
import com.learnbot.dto.RagConversationTurnContext;
import com.learnbot.dto.SearchFilter;
import com.learnbot.dto.SearchResult;
import com.learnbot.repository.DocumentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class RagService {
    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final Pattern SPREADSHEET_ROW = Pattern.compile("^Sheet\\s+(.+?)\\s+Row\\s+(\\d+):\\s*(.*)$");
    private static final int GENERAL_CONTEXT_RESULT_LIMIT = 8;
    private static final int GENERAL_CONTEXT_EXCERPT_CHARS = 760;
    private static final int TABLE_CONTEXT_EXCERPT_CHARS = 900;
    private static final int FALLBACK_RESULT_LIMIT = 4;
    private static final int FALLBACK_EXCERPT_CHARS = 260;
    private static final int FALLBACK_POINT_CHARS = 220;
    private static final double CONVERSATION_PINNED_BOOST = 0.18;
    private static final List<String> HEADER_WORDS = List.of(
            "연번", "직종", "이름", "성명", "직원명", "사원명", "본부", "직급", "성별", "비고", "대상자녀수", "name"
    );

    private final SearchService searchService;
    private final OllamaClient ollamaClient;
    private final DocumentRepository documentRepository;
    private final LearnBotProperties properties;
    private final RagPipelineService pipelineService;
    private final DocumentDomainProfileService domainProfileService;
    private final RagMetricsService ragMetricsService;

    public RagService(
            SearchService searchService,
            OllamaClient ollamaClient,
            DocumentRepository documentRepository,
            LearnBotProperties properties
    ) {
        this(searchService, ollamaClient, documentRepository, properties, new RagPipelineService(ollamaClient, properties), new DocumentDomainProfileService(), null);
    }

    public RagService(
            SearchService searchService,
            OllamaClient ollamaClient,
            DocumentRepository documentRepository,
            LearnBotProperties properties,
            RagPipelineService pipelineService
    ) {
        this(searchService, ollamaClient, documentRepository, properties, pipelineService, new DocumentDomainProfileService(), null);
    }

    @Autowired
    public RagService(
            SearchService searchService,
            OllamaClient ollamaClient,
            DocumentRepository documentRepository,
            LearnBotProperties properties,
            RagPipelineService pipelineService,
            DocumentDomainProfileService domainProfileService,
            RagMetricsService ragMetricsService
    ) {
        this.searchService = searchService;
        this.ollamaClient = ollamaClient;
        this.documentRepository = documentRepository;
        this.properties = properties;
        this.pipelineService = pipelineService;
        this.domainProfileService = domainProfileService == null ? new DocumentDomainProfileService() : domainProfileService;
        this.ragMetricsService = ragMetricsService;
    }

    public AskResponse ask(String question, SearchFilter filter, String mode) {
        return ask(question, filter, mode, null, null);
    }

    public AskResponse ask(String question, SearchFilter filter, String mode, List<UUID> spaceIds, UUID selectedSpaceId) {
        return ask(question, filter, mode, null, spaceIds, selectedSpaceId);
    }

    public AskResponse ask(String question, SearchFilter filter, String mode, String speedProfile, List<UUID> spaceIds, UUID selectedSpaceId) {
        ollamaClient.beginPrimaryRequest();
        try {
            return askPrioritized(question, null, filter, mode, speedProfile, spaceIds, selectedSpaceId, null);
        } finally {
            ollamaClient.finishPrimaryRequest();
        }
    }

    public AskResponse askConversational(String question, RagConversationContext conversationContext, SearchFilter filter, String mode, String speedProfile, List<UUID> spaceIds, UUID selectedSpaceId) {
        ollamaClient.beginPrimaryRequest();
        try {
            return askPrioritized(question, conversationContext, filter, mode, speedProfile, spaceIds, selectedSpaceId, null);
        } finally {
            ollamaClient.finishPrimaryRequest();
        }
    }

    public AskResponse askStreaming(String question, SearchFilter filter, String mode, String speedProfile, List<UUID> spaceIds, UUID selectedSpaceId, AnswerStreamSink streamSink) {
        ollamaClient.beginPrimaryRequest();
        try {
            return askPrioritized(question, null, filter, mode, speedProfile, spaceIds, selectedSpaceId, streamSink);
        } finally {
            ollamaClient.finishPrimaryRequest();
        }
    }

    public AskResponse askConversationalStreaming(String question, RagConversationContext conversationContext, SearchFilter filter, String mode, String speedProfile, List<UUID> spaceIds, UUID selectedSpaceId, AnswerStreamSink streamSink) {
        ollamaClient.beginPrimaryRequest();
        try {
            return askPrioritized(question, conversationContext, filter, mode, speedProfile, spaceIds, selectedSpaceId, streamSink);
        } finally {
            ollamaClient.finishPrimaryRequest();
        }
    }

    private AskResponse askPrioritized(String question, RagConversationContext conversationContext, SearchFilter filter, String mode, String speedProfile, List<UUID> spaceIds, UUID selectedSpaceId, AnswerStreamSink streamSink) {
        long askStarted = System.nanoTime();
        String originalQuestion = safe(question);
        String effectiveQuestion = effectiveQuestion(originalQuestion, conversationContext);
        AnswerMode answerMode = AnswerMode.from(mode);
        DocumentSpeedProfile requestedSpeedProfile = DocumentSpeedProfile.from(
                speedProfile,
                properties.getRag().getPipeline().getDefaultDocumentSpeedProfile()
        );
        DocumentQuestionType questionType = classifyDocumentQuestion(effectiveQuestion, answerMode);
        int topK = retrievalLimit(effectiveQuestion, answerMode, questionType, requestedSpeedProfile);
        long retrievalStarted = System.nanoTime();
        DocumentRetrieval retrieval = retrieveDocuments(effectiveQuestion, filter, answerMode, questionType, requestedSpeedProfile, topK, spaceIds, selectedSpaceId, conversationContext);
        long retrievalMs = elapsedMs(retrievalStarted);
        List<SearchResult> citations = retrieval.citations();
        if (citations.isEmpty()) {
            recordMetrics("document", answerMode.value(), requestedSpeedProfile.name(), retrieval, retrievalMs, 0, 0, 0, 0, 0, 0, false, false, elapsedMs(askStarted));
            return new AskResponse(
                    answerMode.value(),
                    "검색된 문서 근거가 부족해 답변을 생성할 수 없습니다.",
                    citations,
                    List.of(),
                    "낮음",
                    List.of("검색된 문서 근거가 없어 추측 답변 생성을 중단했습니다.")
            );
        }
        Optional<ComputedAnswer> computedAnswer = maybeAnswerSpreadsheetCount(effectiveQuestion, citations);
        if (computedAnswer.isPresent()) {
            ComputedAnswer computed = computedAnswer.get();
            recordMetrics("document", answerMode.value(), requestedSpeedProfile.name(), retrieval, retrievalMs, 0, 0, computed.citations().size(), 0, 0, 0, false, false, elapsedMs(askStarted));
            return new AskResponse(
                    answerMode.value(),
                    computed.answer(),
                    computed.citations(),
                    buildEvidence(computed.citations()),
                    "높음",
                    List.of("엑셀/CSV 집계값은 LLM이 아니라 서버가 문서 전체 청크를 기준으로 계산했습니다.")
            );
        }

        long contextStarted = System.nanoTime();
        String systemPrompt = systemPrompt(answerMode, questionType) + conversationSystemRule(conversationContext);
        String promptPrefix = questionPrompt(originalQuestion, effectiveQuestion, conversationContext)
                + conversationFocus(conversationContext);
        ContextBundle contextBundle = buildBudgetedContext(effectiveQuestion, answerMode, questionType, retrieval.effectiveProfile(), systemPrompt, promptPrefix, citations, conversationContext);
        citations = contextBundle.citations();
        String context = contextBundle.context();
        long contextMs = elapsedMs(contextStarted);
        if (streamSink != null) {
            streamSink.onEvidence(citations, buildEvidence(citations));
        }

        String userPrompt = promptPrefix + "\n\nContext:\n" + context;
        String answer;
        boolean llmUnavailable = false;
        boolean answerRewritten = false;
        boolean answerRetried = false;
        boolean answerKeptAfterStreamValidation = false;
        String answerDoneReason = null;
        OllamaClient.ChatResult finalChatResult = null;
        long llmMs = 0;
        StringBuilder streamedAnswer = new StringBuilder();
        try {
            long llmStarted = System.nanoTime();
            OllamaClient.ChatResult chatResult = streamSink == null
                    ? chatWithLimit(systemPrompt, userPrompt, maxOutputTokens(answerMode, questionType, retrieval.effectiveProfile()))
                    : streamWithLimit(systemPrompt, userPrompt, maxOutputTokens(answerMode, questionType, retrieval.effectiveProfile()), streamSink, streamedAnswer);
            llmMs += elapsedMs(llmStarted);
            finalChatResult = chatResult;
            answer = chatResult.content();
            answerDoneReason = chatResult.doneReason();
            String qualityReason = qualityFailureReason(answer, citations.size(), answerDoneReason);
            if (qualityReason != null && streamedAnswer.isEmpty() && shouldRepairAnswer(qualityReason, retrieval.effectiveProfile())) {
                log.info("RAG answer retry mode={} reason={} citations={} question={}",
                        answerMode.value(), qualityReason, citations.size(), abbreviate(question));
                List<SearchResult> retryCitations = compactRepairCitations(citations, answerMode, questionType, retrieval.effectiveProfile());
                String retryContext = buildContext(effectiveQuestion, answerMode, questionType, DocumentSpeedProfile.FAST, retryCitations);
                String retryPrompt = promptPrefix
                        + "\n\nCompact context:\n" + retryContext
                        + "\n\nPrevious answer failed validation because: " + qualityReason + "."
                        + "\nAnswer briefly in Korean and attach evidence numbers like [1] to every factual claim.";
                long retryStarted = System.nanoTime();
                OllamaClient.ChatResult retryResult = chatWithLimit(systemPrompt + "\nKeep the answer concise and citation-grounded.", retryPrompt, repairMaxOutputTokens(answerMode, retrieval.effectiveProfile()));
                llmMs += elapsedMs(retryStarted);
                String retryAnswer = retryResult.content();
                if (qualityFailureReason(retryAnswer, retryCitations.size(), retryResult.doneReason()) == null) {
                    answer = retryAnswer;
                    answerDoneReason = retryResult.doneReason();
                    finalChatResult = retryResult;
                    citations = retryCitations;
                    answerRetried = true;
                    if (streamSink != null) {
                        streamSink.onReplace(answer, "answer_repair");
                        streamSink.onEvidence(citations, buildEvidence(citations));
                    }
                }
            }
        } catch (RuntimeException ex) {
            if (streamSink != null && streamedAnswer.length() > 0) {
                throw ex;
            }
            log.warn("RAG LLM call failed mode={} citations={} question={}",
                    answerMode.value(), citations.size(), abbreviate(question), ex);
            answer = fallbackAnswer(answerMode, originalQuestion, citations);
            answerDoneReason = null;
            llmUnavailable = true;
            if (streamSink != null) {
                streamSink.onReplace(answer, "llm_unavailable_fallback");
            }
        }
        String lowQualityReason = qualityFailureReason(answer, citations.size(), answerDoneReason);
        if (lowQualityReason != null) {
            log.info("RAG LLM answer rewritten mode={} citations={} reason={} length={} hasCitation={} question={}",
                    answerMode.value(),
                    citations.size(),
                    lowQualityReason,
                    safe(answer).length(),
                    containsCitation(answer),
                    abbreviate(question));
            answerRewritten = true;
            boolean replaceWithFallback = streamedAnswer.isEmpty() && shouldReplaceAnswerWithFallback(answer, lowQualityReason);
            if (replaceWithFallback) {
                answer = fallbackAnswer(answerMode, originalQuestion, citations);
            } else {
                answerKeptAfterStreamValidation = true;
            }
            if (streamSink != null && replaceWithFallback) {
                streamSink.onReplace(answer, "quality_fallback");
            }
        }
        AnswerTiming timing = new AnswerTiming(
                retrievalMs,
                contextMs,
                llmMs,
                elapsedMs(askStarted),
                context.length(),
                finalChatResult == null ? 0 : finalChatResult.promptEvalCount(),
                finalChatResult == null ? 0 : finalChatResult.evalCount()
        );
        recordMetrics(
                "document",
                answerMode.value(),
                retrieval.effectiveProfile().name(),
                retrieval,
                timing.retrievalMs(),
                timing.contextMs(),
                timing.llmMs(),
                citations.size(),
                timing.promptTokens(),
                timing.outputTokens(),
                timing.contextChars(),
                llmUnavailable || answerRewritten,
                llmUnavailable,
                timing.totalMs()
        );
        log.info("RAG answer timing domain=document profile={} effectiveProfile={} retrievalMs={} embeddingMs={} vectorSearchMs={} keywordSearchMs={} rerankMs={} adjacentMs={} graphExpansionMs={} contextMs={} llmMs={} totalMs={} contextChars={} promptTokens={} outputTokens={} citations={} queryCount={} question={}",
                requestedSpeedProfile.name(),
                retrieval.effectiveProfile().name(),
                timing.retrievalMs(),
                retrieval.timing().embeddingMs(),
                retrieval.timing().vectorSearchMs(),
                retrieval.timing().keywordSearchMs(),
                retrieval.timing().rerankMs(),
                retrieval.timing().adjacentMs(),
                retrieval.timing().graphExpansionMs(),
                timing.contextMs(),
                timing.llmMs(),
                timing.totalMs(),
                timing.contextChars(),
                timing.promptTokens(),
                timing.outputTokens(),
                citations.size(),
                retrieval.queryCount(),
                abbreviate(question));
        return new AskResponse(
                answerMode.value(),
                answer,
                citations,
                buildEvidence(citations),
                confidence(citations, llmUnavailable, answerRewritten, retrieval.assessment()),
                conversationDiagnostics(
                        diagnostics(answerMode, citations, llmUnavailable, answerRewritten, answerRetried, answerKeptAfterStreamValidation, retrieval, questionType, timing),
                        originalQuestion,
                        effectiveQuestion,
                        conversationContext,
                        retrieval
                )
        );
    }

    private String effectiveQuestion(String originalQuestion, RagConversationContext conversationContext) {
        if (conversationContext == null || !conversationContext.contextual()) {
            return safe(originalQuestion);
        }
        if (conversationContext.previousAnswerExpansion()) {
            return safe(originalQuestion);
        }
        String rewritten = safe(conversationContext.rewrittenQuestion()).trim();
        return rewritten.isBlank() ? safe(originalQuestion) : rewritten;
    }

    private String questionPrompt(String originalQuestion, String effectiveQuestion, RagConversationContext conversationContext) {
        if (conversationContext != null && conversationContext.previousAnswerExpansion()) {
            return "Original user question:\n" + originalQuestion
                    + "\n\nThis is a request to expand the previous answer. Keep the previous answer outline and expand each item using only the current context.";
        }
        if (conversationContext == null || !conversationContext.contextual() || safe(originalQuestion).equals(safe(effectiveQuestion))) {
            return "Question:\n" + originalQuestion;
        }
        return "Original user question:\n" + originalQuestion
                + "\n\nConversation-aware search question:\n" + effectiveQuestion
                + "\n\nAnswer the original user question. Use the conversation-aware question only to resolve follow-up references.";
    }

    private String conversationSystemRule(RagConversationContext conversationContext) {
        if (conversationContext == null || !conversationContext.contextual()) {
            return "";
        }
        if (conversationContext.previousAnswerExpansion()) {
            return "\nPrevious-answer expansion rules:"
                    + "\nKeep the previous answer item structure. Do not invent a new conclusion."
                    + "\nFor each item, explain concrete clauses, criteria, limits, exceptions, and gaps found in the current context."
                    + "\nAttach at least one citation number like [1] to each item when evidence exists."
                    + "\nIf an item lacks current context evidence, write \"추가 근거 부족\" for that item.";
        }
        return "\nUse previous conversation only to resolve follow-up references."
                + "\nUse retrieved and pinned document context as the source of truth."
                + "\nDo not cite previous answers directly unless their cited chunks are present in the current context."
                + "\nIf the follow-up is unrelated, ignore previous conversation context.";
    }

    private String conversationFocus(RagConversationContext conversationContext) {
        if (conversationContext == null || !conversationContext.contextual()) {
            return "";
        }
        String turns = conversationContext.recentTurns() == null ? "" : conversationContext.recentTurns().stream()
                .limit(3)
                .map(this::conversationTurnSummary)
                .filter(summary -> !summary.isBlank())
                .collect(Collectors.joining("\n"));
        String anchors = conversationContext.documentAnchors() == null ? "" : conversationContext.documentAnchors().stream()
                .limit(5)
                .map(anchor -> "- " + safe(anchor.title())
                        + nullable(" / page=", anchor.pageNumber() == null ? null : String.valueOf(anchor.pageNumber()))
                        + nullable(" / clause=", anchor.clauseNumber())
                        + nullable(" / clauseLevel=", anchor.clauseLevel())
                        + nullable(" / section=", anchor.sectionTitle())
                        + nullable(" / heading=", anchor.headingPath())
                        + " / chunk=" + anchor.chunkIndex())
                .collect(Collectors.joining("\n"));
        if (turns.isBlank() && anchors.isBlank()) {
            return "";
        }
        String previousOutline = previousAnswerOutline(conversationContext);
        return "\n\nConversation focus:\n"
                + "Use this section only to resolve references such as 'that document', 'that condition', or 'the previous source'.\n"
                + (previousOutline.isBlank() ? "" : "Previous answer outline:\n" + previousOutline + "\n")
                + (turns.isBlank() ? "" : "Recent turns:\n" + turns + "\n")
                + (anchors.isBlank() ? "" : "Previous document evidence anchors:\n" + anchors);
    }

    private String previousAnswerOutline(RagConversationContext conversationContext) {
        if (conversationContext == null || conversationContext.previousAnswerItems().isEmpty()) {
            return "";
        }
        return conversationContext.previousAnswerItems().stream()
                .limit(12)
                .map(item -> "- " + safe(item.label())
                        + (item.citationNumbers().isEmpty() ? "" : " / previous citations=" + item.citationNumbers())
                        + (item.evidenceChunkIds().isEmpty() ? " / 추가 근거 부족" : " / requiredChunks=" + item.evidenceChunkIds()))
                .collect(Collectors.joining("\n"));
    }

    private String conversationTurnSummary(RagConversationTurnContext turn) {
        if (turn == null) {
            return "";
        }
        return "- Q: " + abbreviate(turn.question())
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
            String title = item.path("title").asText("");
            String chunk = item.path("chunkIndex").asText("");
            com.fasterxml.jackson.databind.JsonNode metadata = item.path("metadata");
            String page = metadata.path("pageNumber").asText("");
            if (!title.isBlank()) {
                values.add(title + (page.isBlank() ? "" : " p." + page) + (chunk.isBlank() ? "" : " chunk " + chunk));
            }
        }
        return values.isEmpty() ? "none" : String.join("; ", values);
    }

    private DocumentRetrieval retrieveDocuments(
            String question,
            SearchFilter filter,
            AnswerMode answerMode,
            DocumentQuestionType questionType,
            DocumentSpeedProfile speedProfile,
            int topK,
            List<UUID> spaceIds,
            UUID selectedSpaceId,
            RagConversationContext conversationContext
    ) {
        Map<UUID, SearchResult> merged = new LinkedHashMap<>();
        List<String> queriesUsed = new ArrayList<>();
        RetrievalTimingAccumulator timing = new RetrievalTimingAccumulator();
        DocumentSpeedProfile effectiveProfile = speedProfile == null ? DocumentSpeedProfile.BALANCED : speedProfile;
        boolean profileEscalated = false;
        int searchLimit = documentSearchLimit(topK, effectiveProfile);
        int pinnedCandidateCount = collectPinnedConversationDocuments(question, filter, spaceIds, selectedSpaceId, conversationContext, merged);
        boolean expansionFromPinnedEvidence = previousAnswerExpansion(conversationContext) && pinnedCandidateCount > 0;

        if (!expansionFromPinnedEvidence) {
            searchAndMergeDocuments(question, question, filter, searchLimit, effectiveProfile, spaceIds, selectedSpaceId, merged, queriesUsed, timing);
        }
        if (!expansionFromPinnedEvidence && usesAuxiliaryDocumentQueries(questionType)) {
            for (String query : overviewQueries(question, questionType, effectiveProfile)) {
                searchAndMergeDocuments(question, query, filter, searchLimit, effectiveProfile, spaceIds, selectedSpaceId, merged, queriesUsed, timing);
            }
        }
        long adjacentStarted = System.nanoTime();
        expandAdjacentDocumentChunks(question, answerMode, questionType, effectiveProfile, filter, spaceIds, selectedSpaceId, merged);
        expandContextRelatedDocumentChunks(question, questionType, effectiveProfile, filter, spaceIds, selectedSpaceId, merged);
        timing.addAdjacentMs(elapsedMs(adjacentStarted));
        if (effectiveProfile != DocumentSpeedProfile.FAST) {
            long graphStarted = System.nanoTime();
            expandGraphDocumentChunks(filter, spaceIds, selectedSpaceId, merged);
            timing.addGraphExpansionMs(elapsedMs(graphStarted));
        }
        List<SearchResult> citations = selectAnswerCitations(question, answerMode, questionType, effectiveProfile, List.copyOf(merged.values()));
        RagPipelineService.EvidenceAssessment assessment = pipelineService.assessDocuments(
                question,
                citations,
                minDocumentEvidence(answerMode),
                1
        );
        RagPipelineService.QueryPlan queryPlan = new RagPipelineService.QueryPlan(
                RagPipelineService.Domain.DOCUMENT,
                List.of(question),
                false,
                false,
                false,
                "initial search"
        );
        int iteration = 1;

        if (!isCountQuestion(question)
                && (!assessment.sufficient() || needsMoreOverviewEvidence(citations, questionType))
                && speedProfile != DocumentSpeedProfile.FAST
                && !expansionFromPinnedEvidence
                && shouldAttemptRewrite(citations, assessment, questionType, effectiveProfile)
                && pipelineService.maxIterations() > 1) {
            queryPlan = pipelineService.buildQueryPlan(
                    question,
                    RagPipelineService.Domain.DOCUMENT,
                    retryBaselineQueries(question, questionType, effectiveProfile)
            );
            List<String> retryQueries = queryPlan.queries().stream()
                    .map(query -> safe(query).trim())
                    .filter(query -> !query.isBlank())
                    .filter(query -> !queriesUsed.contains(query))
                    .distinct()
                    .limit(maxRetryQueryCount(effectiveProfile))
                    .toList();
            int retrySearchLimit = searchLimit;
            DocumentSpeedProfile retryProfile = effectiveProfile;
            List<QuerySearchResults> retryResults = retryQueries.parallelStream()
                    .map(query -> searchDocuments(question, query, filter, retrySearchLimit, retryProfile, spaceIds, selectedSpaceId))
                    .toList();
            for (QuerySearchResults result : retryResults) {
                queriesUsed.add(result.query());
                timing.add(result.timing());
                for (SearchResult searchResult : result.results()) {
                    mergeDocument(merged, result.query().equals(question) ? searchResult : boostDocument(searchResult, 0.03));
                }
            }
            iteration = 2;
            if (usesAuxiliaryDocumentQueries(questionType)) {
                for (String query : overviewQueries(question, questionType, effectiveProfile)) {
                    searchAndMergeDocuments(question, query, filter, searchLimit, effectiveProfile, spaceIds, selectedSpaceId, merged, queriesUsed, timing);
                }
            }
            adjacentStarted = System.nanoTime();
            expandAdjacentDocumentChunks(question, answerMode, questionType, effectiveProfile, filter, spaceIds, selectedSpaceId, merged);
            expandContextRelatedDocumentChunks(question, questionType, effectiveProfile, filter, spaceIds, selectedSpaceId, merged);
            timing.addAdjacentMs(elapsedMs(adjacentStarted));
            if (effectiveProfile != DocumentSpeedProfile.FAST) {
                long graphStarted = System.nanoTime();
                expandGraphDocumentChunks(filter, spaceIds, selectedSpaceId, merged);
                timing.addGraphExpansionMs(elapsedMs(graphStarted));
            }
            citations = selectAnswerCitations(question, answerMode, questionType, effectiveProfile, List.copyOf(merged.values()));
            assessment = pipelineService.assessDocuments(
                    question,
                    citations,
                    minDocumentEvidence(answerMode),
                    iteration
            );
        }

        log.info("RAG retrieval domain=document iterations={} candidates={} citations={} sufficient={} rewriteUsed={} rewriteFailed={} coverage={} question={}",
                iteration,
                merged.size(),
                citations.size(),
                assessment.sufficient(),
                queryPlan.rewriteUsed(),
                queryPlan.rewriteFailed(),
                String.format(java.util.Locale.ROOT, "%.2f", assessment.coverage()),
                abbreviate(question));
        int pinnedUsedCount = (int) citations.stream().filter(this::isConversationPinned).count();
        return new DocumentRetrieval(citations, assessment, queryPlan, iteration, merged.size(), queriesUsed.size(), speedProfile, effectiveProfile, profileEscalated, timing.snapshot(), pinnedCandidateCount, pinnedUsedCount);
    }

    private int collectPinnedConversationDocuments(
            String question,
            SearchFilter filter,
            List<UUID> spaceIds,
            UUID selectedSpaceId,
            RagConversationContext conversationContext,
            Map<UUID, SearchResult> merged
    ) {
        if (conversationContext == null) {
            return 0;
        }
        Set<UUID> requiredIds = requiredDocumentChunkIds(conversationContext);
        if (requiredIds.isEmpty() && (conversationContext.documentAnchors() == null || conversationContext.documentAnchors().isEmpty())) {
            return 0;
        }
        Set<UUID> chunkIds = new java.util.LinkedHashSet<>(requiredIds);
        (conversationContext.documentAnchors() == null ? List.<DocumentConversationAnchor>of() : conversationContext.documentAnchors()).stream()
                .map(DocumentConversationAnchor::chunkId)
                .filter(id -> id != null)
                .distinct()
                .limit(8)
                .forEach(chunkIds::add);
        if (chunkIds.isEmpty()) {
            return 0;
        }
        try {
            List<SearchResult> pinned = documentRepository.findActiveChunksByIds(List.copyOf(chunkIds), filter, spaceIds, selectedSpaceId);
            int added = 0;
            boolean weakQuestionTerms = queryTerms(question).size() <= 2;
            for (SearchResult result : pinned) {
                boolean required = requiredIds.contains(result.chunkId());
                boolean relevant = isRelevantPinnedDocument(question, result);
                if (!required && !previousAnswerExpansion(conversationContext) && !weakQuestionTerms && !relevant) {
                    continue;
                }
                mergeDocument(merged, markConversationPinned(result, required || added < 2 || relevant, required, previousItemLabel(conversationContext, result.chunkId())));
                added++;
            }
            return added;
        } catch (RuntimeException ex) {
            log.debug("Document conversation pinned evidence skipped reason={}", ex.getMessage());
            return 0;
        }
    }

    private boolean isRelevantPinnedDocument(String question, SearchResult result) {
        List<String> terms = queryTerms(question);
        if (terms.isEmpty()) {
            return true;
        }
        String target = normalizeForSearch(String.join(" ",
                safe(result.title()),
                safe(result.sourceUri()),
                safe(metadataString(result, "sectionTitle")),
                safe(metadataString(result, "headingPath")),
                safe(metadataString(result, "documentType")),
                safe(result.content())
        ));
        return terms.stream().anyMatch(target::contains);
    }

    private SearchResult markConversationPinned(SearchResult result, boolean boost) {
        return markConversationPinned(result, boost, false, "");
    }

    private SearchResult markConversationPinned(SearchResult result, boolean boost, boolean required, String previousItemLabel) {
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
        metadata.put("conversationPinned", true);
        metadata.put("evidenceRole", "conversation_pinned");
        metadata.put("evidenceRankReason", "Pinned from previous document conversation evidence");
        if (required) {
            metadata.put("conversationRequired", true);
        }
        if (previousItemLabel != null && !previousItemLabel.isBlank()) {
            metadata.put("previousAnswerItem", previousItemLabel);
        }
        return new SearchResult(
                result.chunkId(),
                result.documentId(),
                result.title(),
                result.sourceUri(),
                result.sourceType(),
                result.contentType(),
                result.chunkIndex(),
                result.content(),
                Map.copyOf(metadata),
                boost ? result.score() + CONVERSATION_PINNED_BOOST : result.score()
        );
    }

    private void searchAndMergeDocuments(
            String originalQuestion,
            String query,
            SearchFilter filter,
            int limit,
            DocumentSpeedProfile speedProfile,
            List<UUID> spaceIds,
            UUID selectedSpaceId,
            Map<UUID, SearchResult> merged,
            List<String> queriesUsed,
            RetrievalTimingAccumulator timing
    ) {
        String safeQuery = safe(query).trim();
        if (safeQuery.isBlank() || queriesUsed.contains(safeQuery)) {
            return;
        }
        queriesUsed.add(safeQuery);
        try {
            SearchService.SearchResponse response = searchWithProfile(safeQuery, filter, limit, spaceIds, selectedSpaceId, speedProfile);
            timing.add(response.timing());
            for (SearchResult result : response.results()) {
                mergeDocument(merged, safeQuery.equals(originalQuestion) ? result : boostDocument(result, 0.03));
            }
        } catch (RuntimeException ex) {
            log.warn("RAG retrieval query failed query={} question={}", abbreviate(safeQuery), abbreviate(originalQuestion), ex);
        }
    }

    private QuerySearchResults searchDocuments(
            String originalQuestion,
            String query,
            SearchFilter filter,
            int limit,
            DocumentSpeedProfile speedProfile,
            List<UUID> spaceIds,
            UUID selectedSpaceId
    ) {
        String safeQuery = safe(query).trim();
        try {
            SearchService.SearchResponse response = searchWithProfile(safeQuery, filter, limit, spaceIds, selectedSpaceId, speedProfile);
            return new QuerySearchResults(
                    safeQuery,
                    response.results(),
                    response.timing()
            );
        } catch (RuntimeException ex) {
            log.warn("RAG retrieval query failed query={} question={}", abbreviate(safeQuery), abbreviate(originalQuestion), ex);
            return new QuerySearchResults(safeQuery, List.of(), SearchService.SearchTiming.empty());
        }
    }

    private SearchService.SearchResponse searchWithProfile(
            String query,
            SearchFilter filter,
            int limit,
            List<UUID> spaceIds,
            UUID selectedSpaceId,
            DocumentSpeedProfile speedProfile
    ) {
        if (speedProfile == DocumentSpeedProfile.BALANCED) {
            SearchService.SearchResponse response = searchService.searchDetailed(query, filter, limit, spaceIds, selectedSpaceId, DocumentSpeedProfile.BALANCED.name());
            return response == null
                    ? new SearchService.SearchResponse(searchService.search(query, filter, limit, spaceIds, selectedSpaceId), SearchService.SearchTiming.empty())
                    : response;
        }
        SearchService.SearchResponse response = searchService.searchDetailed(query, filter, limit, spaceIds, selectedSpaceId, speedProfile.name());
        return response == null
                ? new SearchService.SearchResponse(searchService.search(query, filter, limit, spaceIds, selectedSpaceId, speedProfile.name()), SearchService.SearchTiming.empty())
                : response;
    }

    private OllamaClient.ChatResult chatWithLimit(String systemPrompt, String userPrompt, int maxOutputTokens) {
        OllamaClient.ChatResult result = ollamaClient.chatResult(systemPrompt, userPrompt, maxOutputTokens);
        return result == null ? ollamaClient.chatResult(systemPrompt, userPrompt) : result;
    }

    private OllamaClient.ChatResult streamWithLimit(String systemPrompt, String userPrompt, int maxOutputTokens, AnswerStreamSink streamSink, StringBuilder streamedAnswer) {
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

    private void mergeDocument(Map<UUID, SearchResult> merged, SearchResult result) {
        SearchResult current = merged.get(result.chunkId());
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

    private boolean isConversationPinned(SearchResult result) {
        return result != null && result.metadata() != null && Boolean.TRUE.equals(result.metadata().get("conversationPinned"));
    }

    private boolean isRequiredConversationPinned(SearchResult result) {
        return result != null && result.metadata() != null && Boolean.TRUE.equals(result.metadata().get("conversationRequired"));
    }

    private boolean previousAnswerExpansion(RagConversationContext conversationContext) {
        return conversationContext != null && conversationContext.previousAnswerExpansion();
    }

    private Set<UUID> requiredDocumentChunkIds(RagConversationContext conversationContext) {
        if (conversationContext == null || conversationContext.requiredDocumentChunkIds() == null) {
            return Set.of();
        }
        return new HashSet<>(conversationContext.requiredDocumentChunkIds());
    }

    private String previousItemLabel(RagConversationContext conversationContext, UUID chunkId) {
        if (conversationContext == null || chunkId == null || conversationContext.previousAnswerItems() == null) {
            return "";
        }
        return conversationContext.previousAnswerItems().stream()
                .filter(item -> item.evidenceChunkIds().contains(chunkId))
                .map(PreviousAnswerItem::label)
                .filter(label -> !safe(label).isBlank())
                .findFirst()
                .orElse("");
    }

    private void expandAdjacentDocumentChunks(
            String question,
            AnswerMode answerMode,
            DocumentQuestionType questionType,
            DocumentSpeedProfile speedProfile,
            SearchFilter filter,
            List<UUID> spaceIds,
            UUID selectedSpaceId,
            Map<UUID, SearchResult> merged
    ) {
        if (!properties.getRag().getPipeline().isDocumentAdjacentExpansionEnabled()
                || merged.isEmpty()
                || spaceIds == null
                || spaceIds.isEmpty()) {
            return;
        }
        int baseRadius = Math.max(0, properties.getRag().getPipeline().getDocumentAdjacentChunkRadius());
        int radius = adjacentRadius(answerMode, questionType, baseRadius);
        if (speedProfile == DocumentSpeedProfile.FAST) {
            radius = Math.min(radius, Math.max(1, baseRadius));
        } else if (speedProfile == DocumentSpeedProfile.DEEP && usesAdjacentDetail(questionType)) {
            radius = Math.max(radius, 2);
        }
        if (radius <= 0) {
            return;
        }
        int seedLimit = switch (speedProfile) {
            case FAST -> 6;
            case DEEP -> 16;
            default -> 12;
        };
        int addLimit = switch (speedProfile) {
            case FAST -> 12;
            case DEEP -> 32;
            default -> 24;
        };
        List<SearchResult> seeds = merged.values().stream()
                .filter(result -> !isDocumentContext(result))
                .sorted(Comparator.comparingDouble((SearchResult result) -> answerRelevance(question, answerMode, result)).reversed())
                .limit(seedLimit)
                .toList();
        try {
            int batchAdded = 0;
            List<DocumentRepository.AdjacentChunkSeed> batchSeeds = seeds.stream()
                    .map(seed -> new DocumentRepository.AdjacentChunkSeed(seed.chunkId(), seed.documentId(), seed.chunkIndex(), seed.score()))
                    .toList();
            for (DocumentRepository.AdjacentChunkCandidate candidate : documentRepository.adjacentChunksBatch(
                    batchSeeds,
                    radius,
                    filter,
                    spaceIds,
                    selectedSpaceId
            )) {
                if (merged.containsKey(candidate.result().chunkId())) {
                    continue;
                }
                mergeDocument(merged, withMetadata(candidate.result(), Map.of(
                        "adjacentExpanded", true,
                        "adjacentDistance", candidate.distance(),
                        "adjacentSeedChunkId", candidate.seedChunkId().toString(),
                        "evidenceRole", "adjacent"
                ), Math.max(0.0, candidate.seedScore() - (0.04 * Math.max(1, candidate.distance())))));
                batchAdded++;
                if (batchAdded >= addLimit) {
                    break;
                }
            }
            if (batchAdded > 0) {
                return;
            }
        } catch (RuntimeException ex) {
            log.warn("RAG adjacent batch expansion failed question={}; falling back to per-seed lookup", abbreviate(question), ex);
        }
        int added = 0;
        for (SearchResult seed : seeds) {
            if (added >= addLimit) {
                break;
            }
            try {
                for (SearchResult adjacent : documentRepository.adjacentChunks(
                        seed.documentId(),
                        seed.chunkIndex(),
                        radius,
                        filter,
                        spaceIds,
                        selectedSpaceId
                )) {
                    if (merged.containsKey(adjacent.chunkId())) {
                        continue;
                    }
                    int distance = Math.abs(adjacent.chunkIndex() - seed.chunkIndex());
                    mergeDocument(merged, withMetadata(adjacent, Map.of(
                            "adjacentExpanded", true,
                            "adjacentDistance", distance,
                            "adjacentSeedChunkId", seed.chunkId().toString(),
                            "evidenceRole", "adjacent"
                    ), Math.max(0.0, seed.score() - (0.04 * Math.max(1, distance)))));
                    added++;
                    if (added >= addLimit) {
                        break;
                    }
                }
            } catch (RuntimeException ex) {
                log.warn("RAG adjacent chunk expansion failed document={} chunk={} question={}",
                        seed.documentId(), seed.chunkIndex(), abbreviate(question), ex);
            }
        }
    }

    private void expandContextRelatedDocumentChunks(
            String question,
            DocumentQuestionType questionType,
            DocumentSpeedProfile speedProfile,
            SearchFilter filter,
            List<UUID> spaceIds,
            UUID selectedSpaceId,
            Map<UUID, SearchResult> merged
    ) {
        if (!usesMixedContextChunks(questionType)
                || merged.isEmpty()
                || spaceIds == null
                || spaceIds.isEmpty()) {
            return;
        }
        int seedLimit = speedProfile == DocumentSpeedProfile.DEEP ? 8 : 5;
        int addLimit = speedProfile == DocumentSpeedProfile.DEEP ? 16 : 10;
        List<DocumentRepository.ContextChunkSeed> seeds = merged.values().stream()
                .filter(this::isDocumentContext)
                .filter(result -> hasContextRoutingMetadata(result.metadata()))
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(seedLimit)
                .map(result -> new DocumentRepository.ContextChunkSeed(
                        result.chunkId(),
                        result.documentId(),
                        metadataString(result, "headingPath"),
                        metadataString(result, "sectionTitle"),
                        DocumentPageMetadata.canonicalPageNumber(result.metadata()),
                        metadataString(result, "tableId"),
                        result.score()
                ))
                .toList();
        if (seeds.isEmpty()) {
            return;
        }
        try {
            int added = 0;
            for (DocumentRepository.ContextRelatedChunkCandidate candidate : documentRepository.contextRelatedChunks(
                    seeds,
                    addLimit,
                    filter,
                    spaceIds,
                    selectedSpaceId
            )) {
                if (merged.containsKey(candidate.result().chunkId())) {
                    continue;
                }
                mergeDocument(merged, withMetadata(candidate.result(), Map.of(
                        "contextRelatedExpanded", true,
                        "contextRelatedReason", candidate.reason(),
                        "contextSeedChunkId", candidate.seedChunkId().toString(),
                        "evidenceRole", "context_related"
                ), Math.max(0.0, candidate.seedScore() - 0.05)));
                added++;
                if (added >= addLimit) {
                    break;
                }
            }
        } catch (RuntimeException ex) {
            log.warn("RAG context-related expansion failed question={}", abbreviate(question), ex);
        }
    }

    private int adjacentRadius(AnswerMode answerMode, DocumentQuestionType questionType, int baseRadius) {
        if (questionType == DocumentQuestionType.LOCATION || questionType == DocumentQuestionType.COUNT_OR_TABLE) {
            return Math.min(Math.max(baseRadius, 1), 1);
        }
        if (usesAdjacentDetail(questionType) || answerMode == AnswerMode.SUMMARY) {
            return Math.max(baseRadius, 2);
        }
        return baseRadius;
    }

    private boolean usesAdjacentDetail(DocumentQuestionType questionType) {
        return isOverviewQuestionType(questionType)
                || questionType == DocumentQuestionType.CLAUSE_EXPLANATION
                || questionType == DocumentQuestionType.COMPARISON
                || questionType == DocumentQuestionType.PROCEDURE;
    }

    private boolean isStructuredDetailQuestionType(DocumentQuestionType questionType) {
        return questionType == DocumentQuestionType.CLAUSE_EXPLANATION
                || questionType == DocumentQuestionType.COMPARISON
                || questionType == DocumentQuestionType.PROCEDURE;
    }

    private boolean hasContextRoutingMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        return metadata.containsKey("headingPath")
                || metadata.containsKey("sectionTitle")
                || metadata.containsKey("pageNumber")
                || metadata.containsKey("tableId");
    }

    private void expandGraphDocumentChunks(
            SearchFilter filter,
            List<UUID> spaceIds,
            UUID selectedSpaceId,
            Map<UUID, SearchResult> merged
    ) {
        if (!properties.getDocument().getGraph().isEnabled()
                || merged.isEmpty()
                || spaceIds == null
                || spaceIds.isEmpty()) {
            return;
        }
        List<UUID> seedChunkIds = merged.values().stream()
                .filter(result -> !isDocumentContext(result))
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(12)
                .map(SearchResult::chunkId)
                .toList();
        if (seedChunkIds.isEmpty()) {
            return;
        }
        try {
            int limit = Math.max(1, properties.getDocument().getGraph().getMaxExpandedResults());
            int maxHop = documentGraphMaxHop();
            for (SearchResult expanded : documentRepository.graphExpandedChunks(seedChunkIds, limit, maxHop, spaceIds, selectedSpaceId)) {
                if (merged.containsKey(expanded.chunkId())) {
                    continue;
                }
                mergeDocument(merged, withMetadata(expanded, Map.of(
                        "documentGraphExpanded", true,
                        "evidenceRole", "document_graph",
                        "evidenceRankReason", "Expanded from related document graph node"
                ), Math.max(0.0, expanded.score() * 0.75)));
            }
        } catch (RuntimeException ex) {
            log.warn("RAG document graph expansion failed seeds={}", seedChunkIds.size(), ex);
        }
    }

    private SearchResult boostDocument(SearchResult result, double value) {
        return new SearchResult(
                result.chunkId(),
                result.documentId(),
                result.title(),
                result.sourceUri(),
                result.sourceType(),
                result.contentType(),
                result.chunkIndex(),
                result.content(),
                result.metadata(),
                result.score() + value
        );
    }

    private int documentGraphMaxHop() {
        return Math.max(1, Math.min(properties.getDocument().getGraph().getMaxHop(), 3));
    }

    private SearchResult withMetadata(SearchResult result, Map<String, Object> additions) {
        return withMetadata(result, additions, result.score());
    }

    private SearchResult withMetadata(SearchResult result, Map<String, Object> additions, double score) {
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
        metadata.putAll(additions);
        return new SearchResult(
                result.chunkId(),
                result.documentId(),
                result.title(),
                result.sourceUri(),
                result.sourceType(),
                result.contentType(),
                result.chunkIndex(),
                result.content(),
                metadata,
                score
        );
    }

    private int minDocumentEvidence(AnswerMode answerMode) {
        return switch (answerMode) {
            case SUMMARY, TABLE -> 4;
            case QUOTE -> 1;
            default -> 2;
        };
    }

    private int retrievalLimit(String question, AnswerMode answerMode, DocumentQuestionType questionType, DocumentSpeedProfile speedProfile) {
        int configured = properties.getRag().getTopK();
        int profileFloor = switch (speedProfile) {
            case FAST -> 5;
            case DEEP -> 10;
            default -> 8;
        };
        if (isOverviewQuestionType(questionType)) {
            int overviewMin = properties.getRag().getOverview().getMinContextChunks()
                    + properties.getRag().getOverview().getMinOriginalChunks()
                    + 4;
            int floor = switch (speedProfile) {
                case FAST -> 8;
                case DEEP -> 14;
                default -> 10;
            };
            return Math.max(configured, Math.max(overviewMin, floor));
        }
        if (isCountQuestion(question) || answerMode == AnswerMode.TABLE) {
            return Math.max(configured, speedProfile == DocumentSpeedProfile.FAST ? 8 : 12);
        }
        if (questionType == DocumentQuestionType.COUNT_OR_TABLE) {
            return Math.max(configured, speedProfile == DocumentSpeedProfile.FAST ? 8 : 12);
        }
        if (questionType == DocumentQuestionType.LOCATION) {
            return Math.max(configured, speedProfile == DocumentSpeedProfile.FAST ? 5 : 7);
        }
        if (questionType == DocumentQuestionType.COMPARISON || questionType == DocumentQuestionType.PROCEDURE) {
            return Math.max(configured, speedProfile == DocumentSpeedProfile.FAST ? 8 : 12);
        }
        if (questionType == DocumentQuestionType.CLAUSE_EXPLANATION) {
            return Math.max(configured, speedProfile == DocumentSpeedProfile.FAST ? 8 : 10);
        }
        if (answerMode == AnswerMode.SUMMARY) {
            return Math.max(configured, speedProfile == DocumentSpeedProfile.FAST ? 7 : 10);
        }
        return Math.max(configured, profileFloor);
    }

    private int documentSearchLimit(int topK, DocumentSpeedProfile speedProfile) {
        int configured = pipelineService.documentSearchLimit(topK);
        return switch (speedProfile) {
            case FAST -> Math.max(topK, Math.min(configured, 16));
            case DEEP -> Math.max(configured, 24);
            default -> configured;
        };
    }

    private boolean shouldAttemptRewrite(
            List<SearchResult> citations,
            RagPipelineService.EvidenceAssessment assessment,
            DocumentQuestionType questionType,
            DocumentSpeedProfile speedProfile
    ) {
        if (speedProfile == DocumentSpeedProfile.FAST || !properties.getRag().getPipeline().isRewriteEnabled()) {
            return false;
        }
        if (speedProfile == DocumentSpeedProfile.DEEP) {
            return true;
        }
        int evidenceCount = citations == null ? 0 : citations.size();
        double topScore = assessment == null ? 0.0 : assessment.topScore();
        double coverage = assessment == null ? 0.0 : assessment.coverage();
        if (evidenceCount == 0) {
            return true;
        }
        if (isOverviewQuestionType(questionType)) {
            return evidenceCount < 4 || coverage < 0.10;
        }
        return evidenceCount < 2 || topScore < 0.25 || coverage < 0.08;
    }

    private int maxRetryQueryCount(DocumentSpeedProfile speedProfile) {
        return switch (speedProfile) {
            case FAST -> 0;
            case DEEP -> 4;
            default -> Math.max(1, properties.getRag().getPipeline().getMaxQueryCountBalanced());
        };
    }

    private List<String> retryBaselineQueries(String question, DocumentQuestionType questionType, DocumentSpeedProfile speedProfile) {
        List<String> queries = new ArrayList<>(searchService.expandedQueries(question));
        queries.addAll(overviewQueries(question, questionType, speedProfile));
        return queries.stream().filter(value -> !safe(value).isBlank()).distinct().toList();
    }

    private List<SearchResult> selectAnswerCitations(String question, AnswerMode answerMode, DocumentQuestionType questionType, DocumentSpeedProfile speedProfile, List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        int limit = switch (answerMode) {
            case SUMMARY, TABLE -> Math.min(results.size(), Math.max(1, pipelineService.documentContextLimit(12)));
            case QUOTE -> Math.min(results.size(), 6);
            default -> Math.min(results.size(), Math.max(1, pipelineService.documentContextLimit(Math.max(8, properties.getRag().getTopK()))));
        };
        List<SearchResult> ordered = orderDocumentEvidence(question, answerMode, results);
        if (isOverviewQuestionType(questionType)) {
            return annotateCitationMetadata(questionType, preservePinnedCitation(ordered, selectOverviewCitations(ordered, limit, speedProfile), limit));
        }
        if (answerMode == AnswerMode.QUOTE || answerMode == AnswerMode.TABLE || isCountQuestion(question) || questionType == DocumentQuestionType.COUNT_OR_TABLE) {
            List<SearchResult> originals = ordered.stream().filter(result -> !isDocumentContext(result)).limit(limit).toList();
            if (!originals.isEmpty()) {
                return annotateCitationMetadata(questionType, preservePinnedCitation(ordered, originals, limit));
            }
        }
        List<SearchResult> selected = new ArrayList<>();
        int contextCount = 0;
        for (SearchResult result : ordered) {
            if (isDocumentContext(result)) {
                if (contextCount >= 2) {
                    continue;
                }
                contextCount++;
            }
            selected.add(result);
            if (selected.size() >= limit) {
                break;
            }
        }
        return annotateCitationMetadata(questionType, preservePinnedCitation(ordered, selected, limit));
    }

    private List<SearchResult> annotateCitationMetadata(DocumentQuestionType questionType, List<SearchResult> citations) {
        if (citations == null || citations.isEmpty()) {
            return List.of();
        }
        return citations.stream()
                .map(result -> {
                    Map<String, Object> metadata = new LinkedHashMap<>(result.metadata() == null ? Map.of() : result.metadata());
                    metadata.put("questionType", questionType.name());
                    if (!metadataString(result, "sectionTitle").isBlank() || !metadataString(result, "headingPath").isBlank()) {
                        metadata.putIfAbsent("sectionMatched", true);
                    }
                    if (!metadataString(result, "clauseNumber").isBlank() || !metadataString(result, "clauseLevel").isBlank()) {
                        metadata.putIfAbsent("structureMatched", true);
                    }
                    return withMetadata(result, metadata);
                })
                .toList();
    }

    private List<SearchResult> preservePinnedCitation(List<SearchResult> ordered, List<SearchResult> selected, int limit) {
        if (ordered == null || selected == null || selected.stream().anyMatch(this::isConversationPinned)) {
            return preserveRequiredCitations(ordered, selected == null ? List.of() : selected, limit);
        }
        Optional<SearchResult> pinned = ordered.stream().filter(this::isConversationPinned).findFirst();
        if (pinned.isEmpty() || selected.stream().anyMatch(result -> result.chunkId().equals(pinned.get().chunkId()))) {
            return preserveRequiredCitations(ordered, selected, limit);
        }
        List<SearchResult> adjusted = new ArrayList<>(selected);
        if (adjusted.size() < limit) {
            adjusted.add(pinned.get());
            return preserveRequiredCitations(ordered, adjusted, limit);
        }
        for (int index = adjusted.size() - 1; index >= 0; index--) {
            if (!isConversationPinned(adjusted.get(index))) {
                adjusted.set(index, pinned.get());
                return preserveRequiredCitations(ordered, adjusted, limit);
            }
        }
        return preserveRequiredCitations(ordered, selected, limit);
    }

    private List<SearchResult> preserveRequiredCitations(List<SearchResult> ordered, List<SearchResult> selected, int limit) {
        List<SearchResult> adjusted = new ArrayList<>(selected == null ? List.of() : selected);
        List<SearchResult> required = ordered.stream()
                .filter(this::isRequiredConversationPinned)
                .filter(result -> adjusted.stream().noneMatch(current -> current.chunkId().equals(result.chunkId())))
                .toList();
        for (SearchResult result : required) {
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

    private List<SearchResult> orderDocumentEvidence(String question, AnswerMode answerMode, List<SearchResult> results) {
        if (!properties.getRag().getPipeline().isDocumentEvidenceRankingEnabled()) {
            return results.stream()
                    .sorted(Comparator.comparingDouble((SearchResult result) -> answerRelevance(question, answerMode, result)).reversed())
                    .toList();
        }
        try {
            Map<UUID, Integer> documentCounts = new LinkedHashMap<>();
            List<SearchResult> ranked = new ArrayList<>();
            for (SearchResult result : results.stream()
                    .sorted(Comparator.comparingDouble((SearchResult item) -> answerRelevance(question, answerMode, item)).reversed())
                    .toList()) {
                int seenForDocument = documentCounts.getOrDefault(result.documentId(), 0);
                EvidenceScore score = documentEvidenceScore(question, answerMode, result, seenForDocument);
                documentCounts.merge(result.documentId(), 1, Integer::sum);
                ranked.add(withMetadata(result, Map.of(
                        "evidenceScore", score.value(),
                        "evidenceRole", score.role(),
                        "evidenceRankReason", score.reason()
                )));
            }
            return ranked.stream()
                    .sorted(Comparator
                            .comparingDouble((SearchResult result) -> metadataDouble(result, "evidenceScore", answerRelevance(question, answerMode, result)))
                            .reversed())
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("RAG document evidence ranking failed question={}", abbreviate(question), ex);
            return results.stream()
                    .sorted(Comparator.comparingDouble((SearchResult result) -> answerRelevance(question, answerMode, result)).reversed())
                    .toList();
        }
    }

    private EvidenceScore documentEvidenceScore(String question, AnswerMode answerMode, SearchResult result, int seenForDocument) {
        String title = normalizeForSearch(result.title());
        String source = normalizeForSearch(result.sourceUri());
        String content = normalizeForSearch(result.content());
        double score = answerRelevance(question, answerMode, result);
        double termBoost = 0.0;
        for (String term : queryTerms(question)) {
            if (title.contains(term)) {
                termBoost += 0.08;
            }
            if (source.contains(term)) {
                termBoost += 0.04;
            }
            if (content.contains(term)) {
                termBoost += 0.05;
            }
        }
        score += Math.min(0.40, termBoost);
        String role = evidenceRole(answerMode, result);
        if ("direct".equals(role)) {
            score += 0.08;
        } else if ("table".equals(role) || "summary".equals(role) || "quote".equals(role)) {
            score += 0.10;
        } else if ("adjacent".equals(role)) {
            int distance = metadataInt(result, "adjacentDistance", 1);
            score += Math.max(0.01, 0.08 - (0.03 * distance));
        } else if ("context_related".equals(role) || "document_graph".equals(role)) {
            score += 0.07;
        }
        if (domainExpectedDocumentTypes(question).contains(metadataString(result, "documentType"))) {
            score += 0.08;
        }
        score -= Math.min(0.24, seenForDocument * 0.06);
        return new EvidenceScore(score, role, evidenceReason(role, termBoost, seenForDocument));
    }

    private Set<String> domainExpectedDocumentTypes(String question) {
        try {
            return domainProfileService.expectedDocumentTypes(question);
        } catch (RuntimeException ignored) {
            return Set.of();
        }
    }

    private String evidenceRole(AnswerMode answerMode, SearchResult result) {
        String existingRole = metadataString(result, "evidenceRole");
        if ("context_related".equals(existingRole) || "document_graph".equals(existingRole)) {
            return existingRole;
        }
        if (metadataBoolean(result, "adjacentExpanded")) {
            return "adjacent";
        }
        String contextType = contextType(result);
        if (answerMode == AnswerMode.SUMMARY && contextType.endsWith("_summary")) {
            return "summary";
        }
        if (answerMode == AnswerMode.TABLE && isSpreadsheet(result)) {
            return "table";
        }
        if (answerMode == AnswerMode.QUOTE) {
            return "quote";
        }
        return isDocumentContext(result) ? "context" : "direct";
    }

    private String evidenceReason(String role, double termBoost, int seenForDocument) {
        List<String> reasons = new ArrayList<>();
        reasons.add("role=" + role);
        if (termBoost > 0) {
            reasons.add("query-term-match");
        }
        if (seenForDocument > 0) {
            reasons.add("document-diversity-penalty");
        }
        return String.join(", ", reasons);
    }

    private boolean metadataBoolean(SearchResult result, String key) {
        Object value = result == null || result.metadata() == null ? null : result.metadata().get(key);
        return value instanceof Boolean booleanValue ? booleanValue : Boolean.parseBoolean(String.valueOf(value));
    }

    private String metadataString(SearchResult result, String key) {
        Object value = result == null || result.metadata() == null ? null : result.metadata().get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int metadataInt(SearchResult result, String key, int fallback) {
        Object value = result == null || result.metadata() == null ? null : result.metadata().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private double metadataDouble(SearchResult result, String key, double fallback) {
        Object value = result == null || result.metadata() == null ? null : result.metadata().get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private List<SearchResult> selectOverviewCitations(List<SearchResult> ordered, int limit, DocumentSpeedProfile speedProfile) {
        List<SearchResult> selected = new ArrayList<>();
        Set<UUID> seenChunks = new HashSet<>();
        int maxDocuments = Math.max(1, pipelineService.overviewMaxDocuments());
        int minContext = Math.max(1, properties.getRag().getOverview().getMinContextChunks());
        int minOriginal = Math.max(1, properties.getRag().getOverview().getMinOriginalChunks());
        if (speedProfile == DocumentSpeedProfile.FAST) {
            minContext = Math.min(minContext, 1);
            minOriginal = Math.min(minOriginal, 3);
            maxDocuments = Math.min(maxDocuments, 6);
        } else if (speedProfile == DocumentSpeedProfile.DEEP) {
            minContext = Math.max(minContext, 2);
            minOriginal = Math.max(minOriginal, 4);
        }

        for (SearchResult result : ordered) {
            if (isDocumentContext(result) && seenChunks.add(result.chunkId())) {
                selected.add(result);
            }
            if (selected.stream().filter(this::isDocumentContext).count() >= minContext || selected.size() >= limit) {
                break;
            }
        }
        Set<UUID> selectedDocuments = new HashSet<>();
        for (SearchResult result : selected) {
            selectedDocuments.add(result.documentId());
        }
        for (SearchResult result : ordered) {
            if (!isDocumentContext(result)
                    && selectedDocuments.size() < maxDocuments
                    && seenChunks.add(result.chunkId())) {
                selected.add(result);
                selectedDocuments.add(result.documentId());
            }
            if (selected.stream().filter(item -> !isDocumentContext(item)).count() >= minOriginal || selected.size() >= limit) {
                break;
            }
        }
        for (SearchResult result : ordered) {
            if (seenChunks.add(result.chunkId())) {
                selected.add(result);
            }
            if (selected.size() >= limit) {
                break;
            }
        }
        return selected.stream().limit(limit).toList();
    }

    private double answerRelevance(String question, AnswerMode answerMode, SearchResult result) {
        String normalizedQuestion = normalizeForSearch(question);
        String title = normalizeForSearch(result.title());
        String source = normalizeForSearch(result.sourceUri());
        String content = normalizeForSearch(result.content());
        double score = result.score();
        for (String term : queryTerms(question)) {
            if (title.contains(term)) {
                score += 0.18;
            }
            if (source.contains(term)) {
                score += 0.08;
            }
            if (content.contains(term)) {
                score += 0.04;
            }
        }
        if (answerMode == AnswerMode.TABLE && (isSpreadsheet(result) || content.contains("row ") || content.contains("table"))) {
            score += 0.25;
        }
        if (isDocumentContext(result)) {
            String contextType = contextType(result);
            if (answerMode == AnswerMode.SUMMARY && (contextType.endsWith("_summary") || contextType.endsWith("_structure"))) {
                score += contextType.endsWith("_summary") ? 0.30 : 0.12;
            } else if (isStructureQuestion(question) && contextType.endsWith("_structure")) {
                score += 0.22;
            } else if (answerMode == AnswerMode.QUOTE || answerMode == AnswerMode.TABLE || isCountQuestion(question)) {
                score -= 0.80;
            } else if (asksForOverviewAndDetail(question)) {
                score += contextType.endsWith("_summary") || contextType.endsWith("_structure") ? 0.16 : 0.04;
            } else {
                score -= 0.08;
            }
        }
        if (answerMode == AnswerMode.QUOTE && (content.contains("제") || content.contains("조") || content.contains("권고") || content.contains("원칙"))) {
            score += 0.08;
        }
        if (normalizedQuestion.contains("pdf") && safe(result.contentType()).toLowerCase().contains("pdf")) {
            score += 0.10;
        }
        return score;
    }

    private String buildContext(String question, AnswerMode answerMode, DocumentQuestionType questionType, DocumentSpeedProfile speedProfile, List<SearchResult> results) {
        if (results.isEmpty()) {
            return "No context retrieved.";
        }

        int limit = Math.min(results.size(), contextLimit(answerMode, questionType, speedProfile));
        int excerptChars = contextExcerptChars(answerMode, questionType, speedProfile);
        return IntStream.range(0, limit)
                .mapToObj(index -> {
                    SearchResult result = results.get(index);
                    return "[" + (index + 1) + "] " + result.title()
                            + " · " + result.sourceUri()
                            + " · " + safe(result.contentType())
                            + " · chunk=" + result.chunkIndex()
                            + " · chunkId=" + result.chunkId()
                            + contextMetadataLabel(result) + "\n"
                            + relevantExcerpt(question, result.content(), excerptChars);
                })
                .collect(Collectors.joining("\n\n"));
    }
    private ContextBundle buildBudgetedContext(
            String question,
            AnswerMode answerMode,
            DocumentQuestionType questionType,
            DocumentSpeedProfile speedProfile,
            String systemPrompt,
            String promptPrefix,
            List<SearchResult> results,
            RagConversationContext conversationContext
    ) {
        List<SearchResult> selected = new ArrayList<>(results == null ? List.of() : results);
        String context = buildContext(question, answerMode, questionType, speedProfile, selected);
        int budget = promptTokenBudget(speedProfile);
        int requiredCount = (int) selected.stream().filter(this::isRequiredConversationPinned).count();
        int minCitations = Math.min(selected.size(), Math.max(minContextCitations(answerMode, questionType), requiredCount));
        while (selected.size() > minCitations
                && estimateTokens(systemPrompt) + estimateTokens(promptPrefix) + estimateTokens(context) > budget) {
            removeBudgetCandidate(selected);
            context = buildContext(question, answerMode, questionType, speedProfile, selected);
        }
        return new ContextBundle(List.copyOf(selected), context);
    }

    private void removeBudgetCandidate(List<SearchResult> selected) {
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

    private int minContextCitations(AnswerMode answerMode, DocumentQuestionType questionType) {
        if (isOverviewQuestionType(questionType)) {
            return 4;
        }
        if (questionType == DocumentQuestionType.COUNT_OR_TABLE) {
            return 3;
        }
        return answerMode == AnswerMode.QUOTE || answerMode == AnswerMode.TABLE ? 3 : 2;
    }

    private int promptTokenBudget(DocumentSpeedProfile speedProfile) {
        int contextWindow = Math.max(2048, pipelineService.contextWindow());
        int configured = Math.max(512, pipelineService.promptTokenBudgetBalanced());
        return switch (speedProfile) {
            case FAST -> Math.min(configured, Math.max(1024, contextWindow - 900));
            case DEEP -> Math.max(configured, contextWindow - 700);
            default -> Math.min(configured, Math.max(1800, contextWindow - 700));
        };
    }

    private int estimateTokens(String value) {
        String compact = safe(value).trim();
        if (compact.isEmpty()) {
            return 0;
        }
        return Math.max(1, (compact.length() + 2) / 3);
    }

    private String contextMetadataLabel(SearchResult result) {
        if (result == null || result.metadata() == null || result.metadata().isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        addMetadataLabel(parts, "page", metadataString(result, "pageNumber"));
        addMetadataLabel(parts, "section", metadataString(result, "sectionTitle"));
        addMetadataLabel(parts, "heading", metadataString(result, "headingPath"));
        addMetadataLabel(parts, "table", metadataString(result, "tableId"));
        addMetadataLabel(parts, "documentType", metadataString(result, "documentType"));
        addMetadataLabel(parts, "schema", metadataString(result, "schemaName"));
        addMetadataLabel(parts, "role", metadataString(result, "evidenceRole"));
        addMetadataLabel(parts, "context", contextType(result));
        return parts.isEmpty() ? "" : " · " + String.join(" · ", parts);
    }
    private void addMetadataLabel(List<String> parts, String label, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(label + "=" + value);
        }
    }

    private int contextLimit(AnswerMode answerMode, DocumentQuestionType questionType, DocumentSpeedProfile speedProfile) {
        if (questionType == DocumentQuestionType.LOCATION) {
            return switch (speedProfile) {
                case FAST -> 4;
                case DEEP -> 7;
                default -> 6;
            };
        }
        if (questionType == DocumentQuestionType.COUNT_OR_TABLE) {
            return switch (speedProfile) {
                case FAST -> pipelineService.documentContextLimit(6);
                case DEEP -> pipelineService.documentContextLimit(12);
                default -> pipelineService.documentContextLimit(8);
            };
        }
        if (isStructuredDetailQuestionType(questionType)) {
            int baseline = pipelineService.documentContextLimit(questionType == DocumentQuestionType.COMPARISON ? 10 : 9);
            return switch (speedProfile) {
                case FAST -> Math.min(baseline, 7);
                case DEEP -> Math.max(baseline, 12);
                default -> baseline;
            };
        }
        if (isOverviewQuestionType(questionType)) {
            int baseline = pipelineService.documentContextLimit(Math.max(8,
                    properties.getRag().getOverview().getMinContextChunks()
                            + properties.getRag().getOverview().getMinOriginalChunks()
                            + 4));
            return switch (speedProfile) {
                case FAST -> Math.min(baseline, 8);
                case DEEP -> Math.max(baseline, 12);
                default -> Math.min(baseline, 10);
            };
        }
        int baseline = switch (answerMode) {
            case SUMMARY, TABLE -> pipelineService.documentContextLimit(8);
            case QUOTE -> 6;
            default -> pipelineService.documentContextLimit(GENERAL_CONTEXT_RESULT_LIMIT);
        };
        return switch (speedProfile) {
            case FAST -> Math.min(baseline, answerMode == AnswerMode.QUOTE ? 4 : 6);
            case DEEP -> Math.max(baseline, answerMode == AnswerMode.QUOTE ? 6 : 10);
            default -> baseline;
        };
    }

    private int contextExcerptChars(AnswerMode answerMode, DocumentQuestionType questionType, DocumentSpeedProfile speedProfile) {
        int baseline;
        if (questionType == DocumentQuestionType.LOCATION) {
            baseline = 650;
        } else if (questionType == DocumentQuestionType.COUNT_OR_TABLE || answerMode == AnswerMode.TABLE) {
            baseline = TABLE_CONTEXT_EXCERPT_CHARS;
        } else if (questionType == DocumentQuestionType.CLAUSE_EXPLANATION || questionType == DocumentQuestionType.PROCEDURE) {
            baseline = 1100;
        } else if (questionType == DocumentQuestionType.COMPARISON) {
            baseline = 1000;
        } else if (isOverviewQuestionType(questionType)) {
            baseline = 1200;
        } else {
            baseline = GENERAL_CONTEXT_EXCERPT_CHARS;
        }
        return switch (speedProfile) {
            case FAST -> Math.min(baseline, questionType == DocumentQuestionType.LOCATION ? 520 : answerMode == AnswerMode.TABLE ? 700 : 760);
            case DEEP -> Math.max(baseline, isOverviewQuestionType(questionType) || isStructuredDetailQuestionType(questionType) ? 1400 : 1000);
            default -> baseline;
        };
    }

    private String cleanSystemPrompt(AnswerMode answerMode, DocumentQuestionType questionType) {
        String overviewInstruction = isOverviewQuestionType(questionType)
                ? """
                개요/구조/흐름 질문 추가 규칙:
                - 먼저 전체 구조와 핵심 흐름을 요약하세요.
                - 여러 문서를 종합할 때는 문서별 역할과 공통점/차이점을 분리하세요.
                - 특정 문서 근거만 있는 주장은 그 한계를 명확히 적으세요.
                - 가능하면 "요약", "핵심 근거", "문서별 차이", "한계" 섹션을 사용하세요.
                """
                : "";
        return """
                당신은 LearnBot의 사내 문서 RAG 답변 도우미입니다.

                반드시 지켜야 할 규칙:
                - 최종 답변은 한국어로 작성하세요.
                - 제공된 Context 안의 정보만 사용하세요.
                - 사실 주장에는 반드시 [1], [2] 같은 근거 번호를 붙이세요.
                - 출처, 페이지, 조항, 파일명, 개수는 Context에 있는 경우에만 말하세요.
                - 근거가 부족하면 부족한 부분과 추가 확인이 필요한 내용을 명확히 말하세요.
                - 출처 목록만 나열하지 말고, 결론과 근거를 구조적으로 설명하세요.
                - 사용자가 영어로 질문해도 답변은 한국어로 작성하세요.
                - 고유명사, 제품명, API명, URL, 표 컬럼명은 원문 표기를 유지해도 됩니다.

                권장 답변 구조:
                - 일반 질문: "결론", "근거", "한계"
                - 요약/개요 질문: "요약", "주요 근거", "문서별 차이", "한계"
                - 표/건수 질문: "결과", "계산 기준", "표", "한계"
                - 원문 인용 질문: "원문 인용", "의미", "출처"
                """
                + "\n" + overviewInstruction
                + "\n" + cleanDocumentStructuredInstruction(questionType)
                + "\n" + cleanAnswerModeInstruction(answerMode);
    }

    private String cleanDocumentStructuredInstruction(DocumentQuestionType questionType) {
        if (isStructuredDetailQuestionType(questionType)) {
            return """
                    규정/조항 답변 추가 규칙:
                    - 가능하면 "결론", "적용 대상", "조건", "예외·제한", "절차·판단 기준", "근거", "불확실한 부분" 순서로 답하세요.
                    - 조건, 예외, 제한, 적용 대상이 Context에 있으면 반드시 분리해서 적으세요.
                    - Context에 없는 조건이나 예외를 만들지 말고 "근거에서 확인되지 않음"이라고 적으세요.
                    - 비교 질문은 항목별 공통점과 차이점을 분리하고, 각 항목마다 근거 번호를 붙이세요.
                    """;
        }
        if (questionType == DocumentQuestionType.LOCATION) {
            return """
                    위치/찾기 답변 추가 규칙:
                    - 먼저 문서명, 조항/섹션, 페이지, chunk 정보를 짧게 표시하세요.
                    - 위치 근거가 불확실하면 가장 가까운 후보와 한계를 분리해서 적으세요.
                    """;
        }
        if (questionType == DocumentQuestionType.COUNT_OR_TABLE) {
            return """
                    표/집계 답변 추가 규칙:
                    - 행, 열, sheet, tableId, 계산 기준과 근거를 함께 밝히세요.
                    - 전체 표를 확인하지 못했으면 부분 집계임을 명시하세요.
                    """;
        }
        return "";
    }

    private String cleanAnswerModeInstruction(AnswerMode answerMode) {
        return switch (answerMode) {
            case SUMMARY -> "검색된 문서를 한국어 Markdown bullet로 요약하세요. 관련 항목을 묶고 중요한 주장에는 근거 번호를 붙이세요.";
            case TABLE -> "문서에서 표 형태로 정리할 수 있는 사실만 추출하세요. 가능하면 간결한 Markdown 표를 사용하고, 없는 행·열·개수는 만들지 마세요.";
            case QUOTE -> "문서의 직접 인용을 우선 사용하세요. 각 인용은 Markdown blockquote로 쓰고, 바로 아래에 의미와 근거 번호를 붙이세요.";
            default -> "질문에 직접 답하세요. 먼저 결론을 한국어로 적고, 필요한 경우 핵심 근거를 2~5개 bullet로 정리하세요.";
        };
    }

    private String systemPrompt(AnswerMode answerMode, DocumentQuestionType questionType) {
        if (System.nanoTime() >= 0) {
            return cleanSystemPrompt(answerMode, questionType);
        }
        String overviewInstruction = isOverviewQuestionType(questionType)
                ? """
                개요/구조/흐름 질문 추가 규칙:
                - 먼저 전체 구조와 핵심 흐름을 요약하세요.
                - 여러 문서를 종합할 때는 문서별 역할과 공통점/차이점을 분리하세요.
                - 특정 문서 근거만 있는 주장에는 그 한계를 명확히 쓰세요.
                - 가능하면 "요약", "핵심 근거", "문서별 차이", "한계" 섹션을 사용하세요.
                """
                : "";
        String documentStructuredInstruction = documentStructuredInstruction(questionType);
        return """
                당신은 LearnBot의 사내 문서 RAG 답변 도우미입니다.

                반드시 지켜야 할 규칙:
                - 최종 답변은 한국어로 작성하세요.
                - 제공된 Context 안의 정보만 사용하세요.
                - 사실 주장에는 반드시 [1], [2] 같은 근거 번호를 붙이세요.
                - 출처, 페이지, 조항, 파일명, 개수는 Context에 있는 경우에만 말하세요.
                - 근거가 부족하면 부족한 부분과 확인이 필요한 내용을 명확히 말하세요.
                - 출처 목록만 나열하지 말고, 결론과 근거를 구조적으로 설명하세요.
                - 사용자가 영어로 질문해도 답변은 한국어로 작성하세요.
                - 고유명사, 제품명, API명, URL, 표 컬럼명은 원문 표기를 유지해도 됩니다.

                권장 답변 구조:
                - 일반 질문: "결론", "근거", "한계"
                - 요약/개요 질문: "요약", "주요 근거", "문서별 차이", "한계"
                - 표/수치 질문: "결과", "계산 근거", "한계"
                - 원문 인용 질문: 짧은 인용과 그 의미를 함께 설명
                """ + "\n" + overviewInstruction + "\n" + documentStructuredInstruction + "\n" + answerMode.instruction();
    }

    private String documentStructuredInstruction(DocumentQuestionType questionType) {
        if (System.nanoTime() >= 0) {
            return cleanDocumentStructuredInstruction(questionType);
        }
        if (isStructuredDetailQuestionType(questionType)) {
            return """
                    규정/조항형 답변 추가 규칙:
                    - 가능하면 "결론", "적용대상", "조건", "예외/제한", "절차/판단기준", "근거", "불확실한 부분" 순서로 답하세요.
                    - 조건, 예외, 제한, 적용대상이 Context에 있으면 반드시 분리해서 쓰세요.
                    - Context에 없는 조건이나 예외는 만들지 말고 "근거에서 확인되지 않음"이라고 쓰세요.
                    - 비교 질문은 항목별 공통점과 차이점을 분리하고, 각 항목마다 근거 번호를 붙이세요.
                    """;
        }
        if (questionType == DocumentQuestionType.LOCATION) {
            return """
                    위치/찾기 답변 추가 규칙:
                    - 먼저 문서명, 조항/섹션, 페이지, chunk 정보를 짧게 제시하세요.
                    - 위치 근거가 불확실하면 가장 가까운 후보와 한계를 분리해서 쓰세요.
                    """;
        }
        if (questionType == DocumentQuestionType.COUNT_OR_TABLE) {
            return """
                    표/엑셀/건수 답변 추가 규칙:
                    - 행, 열, sheet, tableId, 계산 기준을 근거와 함께 밝히세요.
                    - 전체 표를 확인하지 못했으면 부분 집계임을 명시하세요.
                    """;
        }
        return "";
    }

    private String cleanFallbackAnswer(AnswerMode answerMode, String question, List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "근거가 부족해 답변을 생성할 수 없습니다.";
        }
        if (isCountQuestion(question)) {
            String sources = IntStream.range(0, Math.min(results.size(), 3))
                    .mapToObj(index -> {
                        SearchResult result = results.get(index);
                        return "- [" + (index + 1) + "] " + result.title() + " (chunk " + result.chunkIndex() + ")";
                    })
                    .collect(Collectors.joining("\n"));
            return """
                    ## 결과
                    검색된 일부 근거만으로는 전체 건수를 확정할 수 없습니다.

                    ## 계산 기준
                    표/CSV처럼 행 단위로 해석 가능한 문서라면 문서 전체 chunk를 기준으로 서버가 집계합니다.

                    ## 근거
                    %s

                    ## 한계
                    검색 결과가 부분 근거일 수 있으므로 전체 표를 확인하지 못한 경우 부분 집계로 보아야 합니다.
                    """.formatted(sources).strip();
        }
        return switch (answerMode) {
            case SUMMARY -> cleanSummaryFallbackAnswer(question, results);
            case TABLE -> cleanTableFallbackAnswer(question, results);
            case QUOTE -> cleanQuoteFallbackAnswer(question, results);
            default -> cleanStructuredFallbackAnswer(question, results);
        };
    }

    private String cleanStructuredFallbackAnswer(String question, List<SearchResult> results) {
        List<EvidencePoint> points = uniqueFallbackPoints(question, results, FALLBACK_RESULT_LIMIT);
        String conclusion = points.isEmpty()
                ? "검색된 문서 근거만으로는 명확한 결론을 확정하기 어렵습니다."
                : points.get(0).text() + " [" + points.get(0).citationIndex() + "]";
        String evidence = points.stream()
                .map(point -> "- " + point.text() + " [" + point.citationIndex() + "]")
                .collect(Collectors.joining("\n"));
        return """
                ## 결론
                %s

                ## 근거
                %s

                ## 관련 문서
                %s

                ## 한계
                LLM 답변 생성에 실패했거나 품질 검증을 통과하지 못해, 검색된 문서 근거를 구조화해 반환했습니다.
                """.formatted(
                conclusion,
                evidence.isBlank() ? "- 직접 인용 가능한 근거가 부족합니다." : evidence,
                citedDocuments(results)
        ).strip();
    }

    private String cleanSummaryFallbackAnswer(String question, List<SearchResult> results) {
        String body = uniqueFallbackPoints(question, results, FALLBACK_RESULT_LIMIT + 2).stream()
                .map(point -> "- " + point.text() + " [" + point.citationIndex() + "]")
                .collect(Collectors.joining("\n"));
        return """
                ## 요약
                검색된 문서 근거를 기준으로 핵심 내용을 요약했습니다.

                ## 주요 근거
                %s

                ## 관련 문서
                %s

                ## 한계
                LLM 요약 생성이 실패했거나 품질 검증을 통과하지 못해, 추출 근거 중심의 요약으로 대체했습니다.
                """.formatted(
                body.isBlank() ? "- 요약 가능한 근거가 부족합니다." : body,
                citedDocuments(results)
        ).strip();
    }

    private String cleanTableFallbackAnswer(String question, List<SearchResult> results) {
        String rows = uniqueFallbackPoints(question, results, FALLBACK_RESULT_LIMIT + 2).stream()
                .map(point -> {
                    SearchResult result = results.get(point.citationIndex() - 1);
                    return "| [" + point.citationIndex() + "] | " + escapeTable(result.title())
                            + " | " + escapeTable(point.text()) + " |";
                })
                .collect(Collectors.joining("\n"));
        return """
                ## 결과
                표 형태로 추출 가능한 근거를 정리했습니다.

                | 근거 | 문서 | 추출 내용 |
                |---|---|---|
                %s

                ## 한계
                검색된 근거에 없는 행, 열, 개수는 만들지 않았습니다.
                """.formatted(rows).strip();
    }

    private String cleanQuoteFallbackAnswer(String question, List<SearchResult> results) {
        String quotes = IntStream.range(0, Math.min(results.size(), FALLBACK_RESULT_LIMIT + 2))
                .mapToObj(index -> {
                    SearchResult result = results.get(index);
                    return "> " + trimQuote(relevantExcerpt(question, result.content(), 180))
                            + "\n\n- 출처: " + result.title() + " [" + (index + 1) + "]";
                })
                .collect(Collectors.joining("\n\n"));
        return """
                ## 원문 인용
                %s

                ## 한계
                LLM 인용 답변 생성이 실패했거나 품질 검증을 통과하지 못해, 원문에 가까운 근거를 추출했습니다.
                """.formatted(quotes).strip();
    }

    private String fallbackAnswer(AnswerMode answerMode, String question, List<SearchResult> results) {
        if (System.nanoTime() >= 0) {
            return cleanFallbackAnswer(answerMode, question, results);
        }
        if (results.isEmpty()) {
            return "근거가 부족해 답변할 수 없습니다.";
        }

        if (isCountQuestion(question)) {
            String sources = IntStream.range(0, Math.min(results.size(), 3))
                    .mapToObj(index -> {
                        SearchResult result = results.get(index);
                        return "[" + (index + 1) + "] " + result.title() + " · chunk " + result.chunkIndex();
                    })
                    .collect(Collectors.joining("\n"));
            return "검색된 일부 근거만으로는 전체 건수를 확정할 수 없습니다. 엑셀/CSV처럼 행 단위로 파싱 가능한 문서라면 문서 전체 청크를 기준으로 계산합니다.\n\n" + sources;
        }

        return switch (answerMode) {
            case SUMMARY -> structuredSummaryFallbackAnswer(question, results);
            case TABLE -> tableFallbackAnswer(question, results);
            case QUOTE -> quoteFallbackAnswer(question, results);
            default -> structuredExtractiveFallbackAnswer(question, results);
        };
    }

    private String structuredExtractiveFallbackAnswer(String question, List<SearchResult> results) {
        if (isRecruitmentCautionQuestion(question)) {
            String answer = recruitmentCautionFallback(results);
            if (!answer.isBlank()) {
                return answer;
            }
        }

        if (isDiscriminationImprovementQuestion(question)) {
            String answer = discriminationImprovementFallback(results);
            if (!answer.isBlank()) {
                return answer;
            }
        }

        List<EvidencePoint> points = uniqueFallbackPoints(question, results, FALLBACK_RESULT_LIMIT);
        String conclusion = points.isEmpty()
                ? "검색된 문서 근거만으로는 명확한 결론을 확정하기 어렵습니다."
                : points.get(0).text() + " [" + points.get(0).citationIndex() + "]";
        String evidence = points.stream()
                .map(point -> "- " + point.text() + " [" + point.citationIndex() + "]")
                .collect(Collectors.joining("\n"));
        return """
                ## 결론
                %s

                ## 근거
                %s

                ## 관련 문서
                %s

                ## 한계
                LLM 답변 생성이 실패했거나 품질 기준을 통과하지 못해, 검색된 문서 근거를 구조화해 반환했습니다.
                """.formatted(
                conclusion,
                evidence.isBlank() ? "- 직접 인용 가능한 근거가 부족합니다." : evidence,
                citedDocuments(results)
        ).strip();
    }

    private String structuredSummaryFallbackAnswer(String question, List<SearchResult> results) {
        String body = uniqueFallbackPoints(question, results, FALLBACK_RESULT_LIMIT + 2).stream()
                .map(point -> "- " + point.text() + " [" + point.citationIndex() + "]")
                .collect(Collectors.joining("\n"));
        return """
                ## 요약
                검색된 문서 근거를 기준으로 핵심 내용을 요약했습니다.

                ## 주요 근거
                %s

                ## 문서별 포인트
                %s

                ## 한계
                LLM 요약 생성이 실패했거나 품질 기준을 통과하지 못해, 추출 근거 중심의 요약으로 대체했습니다.
                """.formatted(
                body.isBlank() ? "- 요약 가능한 근거가 부족합니다." : body,
                citedDocuments(results)
        ).strip();
    }

    private String citedDocuments(List<SearchResult> results) {
        return IntStream.range(0, results.size())
                .limit(FALLBACK_RESULT_LIMIT + 2)
                .mapToObj(index -> {
                    SearchResult result = results.get(index);
                    return "- [" + (index + 1) + "] " + result.title() + " (chunk " + result.chunkIndex() + ")";
                })
                .collect(Collectors.joining("\n"));
    }

    private String extractiveFallbackAnswer(String question, List<SearchResult> results) {
        if (isRecruitmentCautionQuestion(question)) {
            String answer = recruitmentCautionFallback(results);
            if (!answer.isBlank()) {
                return answer;
            }
        }

        if (isDiscriminationImprovementQuestion(question)) {
            String answer = discriminationImprovementFallback(results);
            if (!answer.isBlank()) {
                return answer;
            }
        }

        String sources = uniqueFallbackPoints(question, results, FALLBACK_RESULT_LIMIT).stream()
                .map(point -> "- " + point.text() + " [" + point.citationIndex() + "]")
                .collect(Collectors.joining("\n"));
        return "답변 생성 품질이 낮아 검색된 근거 중심으로 정리합니다.\n\n" + sources;
    }

    private String summaryFallbackAnswer(String question, List<SearchResult> results) {
        String body = uniqueFallbackPoints(question, results, FALLBACK_RESULT_LIMIT + 2).stream()
                .map(point -> "- " + point.text() + " [" + point.citationIndex() + "]")
                .collect(Collectors.joining("\n"));
        return "답변 생성 품질이 낮아 검색된 근거를 요약합니다.\n\n" + body;
    }

    private String tableFallbackAnswer(String question, List<SearchResult> results) {
        String rows = uniqueFallbackPoints(question, results, FALLBACK_RESULT_LIMIT + 2).stream()
                .map(point -> {
                    SearchResult result = results.get(point.citationIndex() - 1);
                    return "| [" + point.citationIndex() + "] | " + escapeTable(result.title())
                            + " | " + escapeTable(point.text()) + " |";
                })
                .collect(Collectors.joining("\n"));
        return "답변 생성 품질이 낮아 표 형태로 추출 가능한 근거를 정리합니다.\n\n"
                + "| 근거 | 문서 | 추출 내용 |\n"
                + "|---|---|---|\n"
                + rows;
    }

    private String quoteFallbackAnswer(String question, List<SearchResult> results) {
        String quotes = IntStream.range(0, Math.min(results.size(), FALLBACK_RESULT_LIMIT + 2))
                .mapToObj(index -> {
                    SearchResult result = results.get(index);
                    return "> " + trimQuote(relevantExcerpt(question, result.content(), 180))
                            + "\n\n- 출처: " + result.title() + " [" + (index + 1) + "]";
                })
                .collect(Collectors.joining("\n\n"));
        return "답변 생성 품질이 낮아 원문에 가까운 짧은 인용 중심으로 정리합니다.\n\n" + quotes;
    }

    private List<EvidencePoint> uniqueFallbackPoints(String question, List<SearchResult> results, int limit) {
        List<EvidencePoint> points = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < results.size() && points.size() < limit; index++) {
            SearchResult result = results.get(index);
            String text = trimPoint(relevantExcerpt(question, result.content(), FALLBACK_EXCERPT_CHARS));
            String key = normalizeForSearch(text);
            if (key.length() > 160) {
                key = key.substring(0, 160);
            }
            if (!key.isBlank() && seen.add(key)) {
                points.add(new EvidencePoint(text, index + 1));
            }
        }
        return points;
    }

    private String escapeTable(String value) {
        return safe(value).replace("|", "\\|").replaceAll("\\s+", " ").trim();
    }

    private String trimQuote(String value) {
        String trimmed = safe(value).replaceAll("\\s+", " ").trim();
        return trimmed.length() <= 180 ? trimmed : trimmed.substring(0, 180).trim() + "...";
    }

    private boolean isRecruitmentCautionQuestion(String question) {
        String normalized = normalizeForSearch(question);
        boolean asksAboutCautions = normalized.contains("유의")
                || normalized.contains("주의")
                || normalized.contains("주의사항")
                || normalized.contains("챙겨")
                || normalized.contains("확인")
                || normalized.contains("공정");
        return normalized.contains("채용") && asksAboutCautions;
    }

    private String recruitmentCautionFallback(List<SearchResult> results) {
        List<EvidencePoint> points = new ArrayList<>();
        addEvidencePoint(
                points,
                results,
                List.of("공개경쟁시험", "공개경쟁", "불특정 다수", "신규채용"),
                "신규채용은 불특정 다수에게 기회를 여는 공개경쟁시험을 원칙으로 잡아야 합니다."
        );
        addEvidencePoint(
                points,
                results,
                List.of("성별", "신체조건", "용모", "학력", "연령", "불합리한 제한", "공평한 기회"),
                "응시 기회는 성별, 신체조건, 용모, 학력, 연령 등으로 불합리하게 제한하면 안 됩니다."
        );
        addEvidencePoint(
                points,
                results,
                List.of("가족", "친척", "친인척", "우대채용", "가족채용"),
                "임직원 가족이나 친척을 우대하는 채용은 금지되며, 친인척 채용 현황 공개 대상도 확인해야 합니다."
        );
        addEvidencePoint(
                points,
                results,
                List.of("사전협의", "공고기간 단축", "시험단계 축소", "외부전문가", "협의 내용", "간소화"),
                "공고기간 단축, 시험단계 축소, 외부전문가 비율 조정 같은 예외 운영은 사전협의와 명확한 근거가 필요합니다."
        );

        if (points.size() < 2) {
            return "";
        }

        String body = points.stream()
                .limit(5)
                .map(point -> "- " + point.text() + " [" + point.citationIndex() + "]")
                .collect(Collectors.joining("\n"));
        return "공직유관단체 채용에서 핵심 유의사항은 공개경쟁 원칙, 차별 금지, 친인척 우대 방지, 예외절차의 근거 관리입니다.\n\n"
                + body;
    }

    private boolean isDiscriminationImprovementQuestion(String question) {
        String normalized = normalizeForSearch(question);
        return normalized.contains("차별")
                && (normalized.contains("개선") || normalized.contains("예방") || normalized.contains("방지"));
    }

    private String discriminationImprovementFallback(List<SearchResult> results) {
        List<EvidencePoint> points = new ArrayList<>();
        addEvidencePoint(
                points,
                results,
                List.of("기간제", "단시간", "파견", "차별", "임금", "근로조건", "복리후생"),
                "기간제·단시간·파견 근로자라는 이유만으로 임금, 상여금, 성과금, 근로조건, 복리후생에서 차별적 처우를 하지 않도록 점검합니다."
        );
        addEvidencePoint(
                points,
                results,
                List.of("임금", "업무", "난이도", "업무량", "객관적", "기준"),
                "임금과 수당은 업무 내용, 난이도, 업무량 같은 객관적 기준을 반영해 합리적 이유 없는 격차를 조정합니다."
        );
        addEvidencePoint(
                points,
                results,
                List.of("식비", "교통보조비", "위험수당"),
                "식비, 교통보조비, 위험수당처럼 복리후생 성격의 금품도 합리적 이유 없이 제외하지 않도록 개선합니다."
        );
        addEvidencePoint(
                points,
                results,
                List.of("교육", "훈련", "휴가", "휴게", "고충"),
                "교육훈련, 휴가·휴게, 고충처리 등 근로조건 전반에서 불합리한 차이를 줄이는 방향으로 자율 개선합니다."
        );
        addEvidencePoint(
                points,
                results,
                List.of("자율점검", "권고", "사례", "개선"),
                "자율점검표와 권고 사례를 기준으로 사업장이 차별 요소를 스스로 찾아 개선하도록 유도합니다."
        );

        if (points.size() < 2) {
            return "";
        }

        String body = points.stream()
                .limit(4)
                .map(point -> "- " + point.text() + " [" + point.citationIndex() + "]")
                .collect(Collectors.joining("\n"));
        return "검색된 가이드라인 기준으로, 차별 예방을 위해 개선되는 핵심은 고용형태만을 이유로 한 불합리한 차이를 없애고 객관적 기준으로 근로조건을 맞추는 것입니다.\n\n" + body;
    }

    private void addEvidencePoint(
            List<EvidencePoint> points,
            List<SearchResult> results,
            List<String> keywords,
            String text
    ) {
        if (points.stream().anyMatch(point -> point.text().equals(text))) {
            return;
        }
        for (int index = 0; index < results.size(); index++) {
            String normalizedContent = normalizeForSearch(results.get(index).content());
            boolean hasKeyword = keywords.stream()
                    .map(this::normalizeForSearch)
                    .anyMatch(keyword -> !keyword.isBlank() && normalizedContent.contains(keyword));
            if (hasKeyword) {
                points.add(new EvidencePoint(text, index + 1));
                return;
            }
        }
    }

    private String trimPoint(String value) {
        String compact = safe(value).replaceAll("\\s+", " ").trim();
        if (compact.length() <= FALLBACK_POINT_CHARS) {
            return compact;
        }
        return compact.substring(0, FALLBACK_POINT_CHARS).trim() + "...";
    }

    private Optional<ComputedAnswer> maybeAnswerSpreadsheetCount(String question, List<SearchResult> citations) {
        if (!isCountQuestion(question)) {
            return Optional.empty();
        }

        Map<UUID, SearchResult> spreadsheetDocuments = new LinkedHashMap<>();
        for (SearchResult citation : citations) {
            if (isSpreadsheet(citation)) {
                spreadsheetDocuments.putIfAbsent(citation.documentId(), citation);
            }
        }
        if (spreadsheetDocuments.isEmpty()) {
            return Optional.empty();
        }

        List<CountEntry> entries = new ArrayList<>();
        List<SearchResult> computedCitations = new ArrayList<>();
        for (SearchResult source : spreadsheetDocuments.values()) {
            List<DocumentChunkDetail> chunks = documentRepository.listDocumentChunks(source.documentId());
            chunks = chunks.stream().filter(chunk -> !isDocumentContext(chunk.metadata())).toList();
            SpreadsheetStats stats = analyzeSpreadsheet(chunks);
            if (stats.dataRowCount() <= 0) {
                continue;
            }

            int startCitation = computedCitations.size() + 1;
            List<SearchResult> evidence = toSearchResults(source, selectEvidenceChunks(chunks, stats));
            computedCitations.addAll(evidence);
            int endCitation = computedCitations.size();
            entries.add(new CountEntry(source.title(), stats, startCitation, endCitation));
        }

        if (entries.isEmpty()) {
            return Optional.empty();
        }
        String deterministicAnswer = buildCountAnswer(entries);
        String answer = buildCountAnswerWithLlm(question, entries, computedCitations, deterministicAnswer);
        return Optional.of(new ComputedAnswer(answer, computedCitations));
    }

    private SpreadsheetStats analyzeSpreadsheet(List<DocumentChunkDetail> chunks) {
        Map<String, SpreadsheetRow> rows = new LinkedHashMap<>();
        for (DocumentChunkDetail chunk : chunks) {
            for (String line : safe(chunk.content()).split("\\R")) {
                Matcher matcher = SPREADSHEET_ROW.matcher(line.trim());
                if (!matcher.matches()) {
                    continue;
                }
                String sheet = matcher.group(1).trim();
                int rowNumber = Integer.parseInt(matcher.group(2));
                Map<String, String> fields = parseSpreadsheetFields(matcher.group(3));
                SpreadsheetRow row = new SpreadsheetRow(sheet, rowNumber, fields, line.trim());
                String key = sheet + ":" + rowNumber;
                SpreadsheetRow existing = rows.get(key);
                if (existing == null || row.rawLine().length() > existing.rawLine().length()) {
                    rows.put(key, row);
                }
            }
        }

        if (rows.isEmpty()) {
            return new SpreadsheetStats(0, 0, null, null, null, null, List.of());
        }

        List<SpreadsheetRow> orderedRows = rows.values().stream()
                .sorted(Comparator.comparing(SpreadsheetRow::sheet).thenComparingInt(SpreadsheetRow::rowNumber))
                .toList();
        Optional<SpreadsheetRow> header = orderedRows.stream()
                .filter(this::looksLikeHeader)
                .findFirst();
        String nameColumn = header.map(this::findNameColumn).orElse(null);

        int count = 0;
        Integer minDataRow = null;
        Integer maxDataRow = null;
        for (SpreadsheetRow row : orderedRows) {
            if (header.isPresent()
                    && row.sheet().equals(header.get().sheet())
                    && row.rowNumber() == header.get().rowNumber()) {
                continue;
            }
            if (!hasData(row, nameColumn)) {
                continue;
            }
            count++;
            minDataRow = minDataRow == null ? row.rowNumber() : Math.min(minDataRow, row.rowNumber());
            maxDataRow = maxDataRow == null ? row.rowNumber() : Math.max(maxDataRow, row.rowNumber());
        }

        List<String> sheets = orderedRows.stream().map(SpreadsheetRow::sheet).distinct().toList();
        return new SpreadsheetStats(count, orderedRows.size(), header.map(SpreadsheetRow::rowNumber).orElse(null), minDataRow, maxDataRow, nameColumn, sheets);
    }

    private Map<String, String> parseSpreadsheetFields(String fieldsText) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String part : safe(fieldsText).split("\\s+\\|\\s+")) {
            int equals = part.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String column = part.substring(0, equals).trim();
            if (column.matches("C\\d+")) {
                fields.put(column, part.substring(equals + 1).trim());
            }
        }
        return fields;
    }

    private boolean looksLikeHeader(SpreadsheetRow row) {
        int matches = 0;
        for (String value : row.fields().values()) {
            if (HEADER_WORDS.contains(normalizeHeader(value))) {
                matches++;
            }
        }
        return matches >= 2;
    }

    private String findNameColumn(SpreadsheetRow header) {
        for (Map.Entry<String, String> field : header.fields().entrySet()) {
            String value = normalizeHeader(field.getValue());
            if (List.of("이름", "성명", "직원명", "사원명", "name").contains(value)) {
                return field.getKey();
            }
        }
        return null;
    }

    private boolean hasData(SpreadsheetRow row, String nameColumn) {
        if (nameColumn != null) {
            String value = safe(row.fields().get(nameColumn));
            return !value.isBlank() && !isHeaderValue(value);
        }
        return row.fields().values().stream().anyMatch(value -> !safe(value).isBlank() && !isHeaderValue(value));
    }

    private boolean isHeaderValue(String value) {
        return HEADER_WORDS.contains(normalizeHeader(value));
    }

    private String normalizeHeader(String value) {
        return safe(value).replaceAll("\\s+", "").toLowerCase();
    }

    private List<DocumentChunkDetail> selectEvidenceChunks(List<DocumentChunkDetail> chunks, SpreadsheetStats stats) {
        List<DocumentChunkDetail> ordered = chunks.stream()
                .filter(chunk -> !isDocumentContext(chunk.metadata()))
                .sorted(Comparator.comparingInt(DocumentChunkDetail::chunkIndex))
                .toList();
        if (ordered.size() <= 10) {
            return ordered;
        }

        Map<UUID, DocumentChunkDetail> selected = new LinkedHashMap<>();
        addChunk(selected, ordered.get(0));
        for (DocumentChunkDetail chunk : ordered) {
            if (containsRow(chunk, stats.headerRowNumber())
                    || containsRow(chunk, stats.minDataRow())
                    || containsRow(chunk, stats.maxDataRow())) {
                addChunk(selected, chunk);
            }
        }
        addChunk(selected, ordered.get(ordered.size() - 1));
        return selected.values().stream()
                .sorted(Comparator.comparingInt(DocumentChunkDetail::chunkIndex))
                .limit(10)
                .toList();
    }

    private void addChunk(Map<UUID, DocumentChunkDetail> selected, DocumentChunkDetail chunk) {
        selected.putIfAbsent(chunk.id(), chunk);
    }

    private boolean containsRow(DocumentChunkDetail chunk, Integer rowNumber) {
        return rowNumber != null && safe(chunk.content()).contains(" Row " + rowNumber + ":");
    }

    private List<SearchResult> toSearchResults(SearchResult source, List<DocumentChunkDetail> chunks) {
        return chunks.stream()
                .map(chunk -> new SearchResult(
                        chunk.id(),
                        source.documentId(),
                        source.title(),
                        source.sourceUri(),
                        source.sourceType(),
                        source.contentType(),
                        chunk.chunkIndex(),
                        chunk.content(),
                        chunk.metadata(),
                        source.score()
                ))
                .toList();
    }

    private String buildCountAnswer(List<CountEntry> entries) {
        if (entries.size() == 1) {
            CountEntry entry = entries.get(0);
            SpreadsheetStats stats = entry.stats();
            return entry.title() + " 기준으로 대상자는 총 " + stats.dataRowCount() + "명입니다. "
                    + "계산 기준은 " + String.join(", ", stats.sheetNames()) + "에서 "
                    + headerText(stats)
                    + dataRangeText(stats) + "에서 " + columnText(stats.nameColumn()) + " 값이 있는 행을 센 것입니다. "
                    + "근거 청크는 [" + entry.startCitation() + "]부터 [" + entry.endCitation() + "]까지입니다.";
        }

        int total = entries.stream().mapToInt(entry -> entry.stats().dataRowCount()).sum();
        String details = entries.stream()
                .map(entry -> "- " + entry.title() + ": " + entry.stats().dataRowCount()
                        + "명 (근거 [" + entry.startCitation() + "]-[" + entry.endCitation() + "])")
                .collect(Collectors.joining("\n"));
        return "검색된 엑셀/CSV 문서 기준 총 대상자는 " + total + "명입니다.\n\n" + details;
    }

    private String buildCountAnswerWithLlm(
            String question,
            List<CountEntry> entries,
            List<SearchResult> computedCitations,
            String deterministicAnswer
    ) {
        int expectedTotal = entries.stream().mapToInt(entry -> entry.stats().dataRowCount()).sum();
        String facts = entries.stream()
                .map(entry -> {
                    SpreadsheetStats stats = entry.stats();
                    return "- " + entry.title()
                            + ": count=" + stats.dataRowCount()
                            + ", sheets=" + String.join(",", stats.sheetNames())
                            + ", headerRow=" + stats.headerRowNumber()
                            + ", dataRows=" + dataRangeText(stats)
                            + ", countColumn=" + columnText(stats.nameColumn())
                            + ", evidence=[" + entry.startCitation() + "]-[" + entry.endCitation() + "]";
                })
                .collect(Collectors.joining("\n"));
        String evidence = IntStream.range(0, Math.min(computedCitations.size(), 8))
                .mapToObj(index -> {
                    SearchResult citation = computedCitations.get(index);
                    return "[" + (index + 1) + "] " + citation.title()
                            + " chunk " + citation.chunkIndex()
                            + "\n" + preview(citation.content());
                })
                .collect(Collectors.joining("\n\n"));

        String systemPrompt = """
                당신은 사내 문서 RAG 도우미 LearnBot입니다.
                반드시 한국어로 답하세요.
                아래 숫자 정보는 서버가 결정적으로 계산한 값입니다. 숫자를 바꾸지 마세요.
                사용자가 이해하기 쉬운 자연어 답변으로 간결하게 작성하세요.
                정확한 총계와 계산 기준을 짧게 설명하세요.
                근거는 [1] 같은 대괄호 번호로 표시하세요.
                사용자가 쓴 단위를 우선 사용하고, 사람 수는 '명', 일반 항목은 '개' 또는 '건'을 사용하세요.
                """;
        String userPrompt = "Question:\n" + question
                + "\n\nComputed facts:\nExpected total: " + expectedTotal
                + "\n" + facts
                + "\n\nEvidence previews:\n" + evidence;

        try {
            OllamaClient.ChatResult chatResult = ollamaClient.chatResult(systemPrompt, userPrompt);
            String answer = chatResult.content();
            boolean acceptable = pipelineService.assessAnswer(
                    answer,
                    computedCitations.size(),
                    true,
                    chatResult.doneReason()
            ).acceptable();
            if (acceptable && !isLowQualityAnswer(answer) && mentionsCount(answer, expectedTotal) && answer.contains("[")) {
                return answer;
            }
        } catch (RuntimeException ignored) {
            // Keep the deterministic answer when the LLM is unavailable.
        }
        return deterministicAnswer;
    }

    private boolean mentionsCount(String answer, int expectedTotal) {
        return safe(answer).replace(",", "").contains(String.valueOf(expectedTotal));
    }

    private String dataRangeText(SpreadsheetStats stats) {
        if (stats.minDataRow() == null || stats.maxDataRow() == null) {
            return "데이터 행";
        }
        if (stats.minDataRow().equals(stats.maxDataRow())) {
            return stats.minDataRow() + "행";
        }
        return stats.minDataRow() + "~" + stats.maxDataRow() + "행";
    }

    private String headerText(SpreadsheetStats stats) {
        if (stats.headerRowNumber() == null) {
            return "별도 헤더 행을 확정하지 않고 ";
        }
        return stats.headerRowNumber() + "행을 헤더로 보고, ";
    }

    private String columnText(String nameColumn) {
        return nameColumn == null ? "값이 있는 행" : nameColumn + "(이름)";
    }

    private boolean isCountQuestionClean(String question) {
        String normalized = normalizeForSearch(question);
        String compact = safe(question).replaceAll("\\s+", "").toLowerCase();
        return containsAny(normalized,
                "count", "total", "row", "table", "sheet", "excel",
                "총", "전체", "건수", "개수", "몇 명", "몇명", "몇 개", "몇개", "몇 건", "몇건",
                "합계", "인원", "대상자", "행 수", "표")
                || compact.contains("총몇")
                || compact.contains("몇명이")
                || compact.contains("몇개")
                || compact.contains("몇건");
    }

    private boolean isCountQuestion(String question) {
        if (System.nanoTime() >= 0) {
            return isCountQuestionClean(question);
        }
        String normalized = safe(question).replaceAll("\\s+", "").toLowerCase();
        return normalized.contains("총몇")
                || normalized.contains("몇명")
                || normalized.contains("몇개")
                || normalized.contains("몇건")
                || normalized.contains("인원")
                || normalized.contains("대상자")
                || normalized.contains("총원")
                || normalized.contains("건수")
                || normalized.contains("개수")
                || normalized.contains("합계")
                || normalized.contains("count")
                || normalized.contains("total");
    }

    private boolean asksForOverviewAndDetailClean(String question) {
        String normalized = normalizeForSearch(question);
        boolean overviewIntent = containsAny(normalized,
                "overview", "summary", "summarize", "main", "key",
                "개요", "요약", "정리", "전체", "주요", "핵심");
        boolean detailIntent = containsAny(normalized,
                "condition", "exception", "limit", "scope", "clause", "criteria", "detail",
                "조건", "예외", "제한", "범위", "조항", "기준", "상세", "적용 대상", "적용대상");
        return overviewIntent && detailIntent;
    }

    private boolean asksForOverviewAndDetail(String question) {
        if (System.nanoTime() >= 0) {
            return asksForOverviewAndDetailClean(question);
        }
        String normalized = normalizeForSearch(question);
        boolean overviewIntent = containsAny(normalized,
                "overview", "summary", "summarize", "main", "key",
                "개요", "요약", "정리", "전체", "주요", "핵심");
        boolean detailIntent = containsAny(normalized,
                "condition", "exception", "limit", "scope", "clause", "criteria", "detail",
                "조건", "예외", "제한", "범위", "조항", "기준", "세부", "상세", "적용대상");
        return overviewIntent && detailIntent;
    }

    private boolean isSpreadsheet(SearchResult result) {
        String contentType = safe(result.contentType()).toLowerCase();
        String title = safe(result.title()).toLowerCase();
        return contentType.contains("spreadsheet")
                || contentType.contains("excel")
                || contentType.contains("csv")
                || title.endsWith(".xlsx")
                || title.endsWith(".xls")
                || title.endsWith(".csv");
    }

    private boolean isStructureQuestionClean(String question) {
        String normalized = normalizeForSearch(question);
        return containsAny(normalized,
                "structure", "section", "page", "slide", "sheet", "table", "where", "outline",
                "구조", "목차", "섹션", "페이지", "슬라이드", "시트", "표", "테이블",
                "조항", "위치", "어디", "구성", "문서맵");
    }

    private boolean isStructureQuestion(String question) {
        if (System.nanoTime() >= 0) {
            return isStructureQuestionClean(question);
        }
        String normalized = normalizeForSearch(question);
        return containsAny(normalized,
                "structure", "section", "page", "slide", "sheet", "table", "where",
                "구조", "목차", "섹션", "페이지", "슬라이드", "시트", "표", "테이블", "조항", "위치", "어디");
    }
    private DocumentQuestionType classifyDocumentQuestionClean(String question, AnswerMode answerMode) {
        if (!properties.getRag().getOverview().isEnabled()) {
            return DocumentQuestionType.GENERAL;
        }
        if (answerMode == AnswerMode.SUMMARY) {
            return DocumentQuestionType.OVERVIEW;
        }
        String normalized = normalizeForSearch(question);
        String compact = safe(question).replaceAll("\\s+", "").toLowerCase();
        if (answerMode == AnswerMode.TABLE || isCountQuestionClean(question)
                || containsAny(normalized, "table", "row", "column", "sheet", "excel", "표", "테이블", "행", "열", "시트", "집계", "건수", "개수")) {
            return DocumentQuestionType.COUNT_OR_TABLE;
        }
        if (containsAny(normalized, "where", "location", "page", "section", "which article",
                "위치", "어디", "몇 조", "몇조", "몇 항", "몇항", "몇 페이지", "몇페이지", "페이지", "조항 위치", "섹션 위치")) {
            return DocumentQuestionType.LOCATION;
        }
        if (containsAny(normalized, "compare", "comparison", "difference", "versus", " vs ",
                "비교", "차이", "각각", "대비", "공통점", "차이점")) {
            return DocumentQuestionType.COMPARISON;
        }
        if (containsAny(normalized, "procedure", "step", "how to", "approval", "apply",
                "절차", "단계", "방법", "처리", "신청", "승인", "판단 기준", "판단기준")) {
            return DocumentQuestionType.PROCEDURE;
        }
        if (containsAny(normalized, "clause", "article", "criteria", "condition", "exception", "limit", "scope", "applies",
                "조항", "규정", "기준", "적용", "적용 대상", "적용대상", "조건", "예외", "제한", "범위")) {
            return DocumentQuestionType.CLAUSE_EXPLANATION;
        }
        if (containsAny(normalized, "architecture", "아키텍처", "구성", "구조", "전체 구조", "컴포넌트", "모듈")) {
            return DocumentQuestionType.ARCHITECTURE;
        }
        if (containsAny(normalized, "flow", "process", "workflow", "sequence", "흐름", "과정", "순서", "프로세스")) {
            return DocumentQuestionType.PROCESS_FLOW;
        }
        if (isStructureQuestionClean(question) || containsAny(normalized, "목차", "섹션", "어떻게 구성")) {
            return DocumentQuestionType.STRUCTURE;
        }
        if (containsAny(normalized, "overview", "summary", "summarize", "개요", "요약", "정리", "전체", "주요", "핵심", "무엇")
                || compact.contains("뭐하는")
                || compact.contains("어떤문서")) {
            return DocumentQuestionType.OVERVIEW;
        }
        return DocumentQuestionType.GENERAL;
    }

    private DocumentQuestionType classifyDocumentQuestion(String question, AnswerMode answerMode) {
        if (System.nanoTime() >= 0) {
            return classifyDocumentQuestionClean(question, answerMode);
        }
        if (!properties.getRag().getOverview().isEnabled()) {
            return DocumentQuestionType.GENERAL;
        }
        if (answerMode == AnswerMode.SUMMARY) {
            return DocumentQuestionType.OVERVIEW;
        }
        String normalized = normalizeForSearch(question);
        String compact = safe(question).replaceAll("\\s+", "").toLowerCase();
        if (answerMode == AnswerMode.TABLE || isCountQuestion(question)
                || containsAny(normalized, "table", "row", "column", "sheet", "excel", "count", "표", "테이블", "행", "열", "시트", "엑셀", "건수", "몇 명", "몇명", "개수")) {
            return DocumentQuestionType.COUNT_OR_TABLE;
        }
        if (containsAny(normalized, "where", "location", "page", "section", "which article", "위치", "어디", "몇 조", "몇조", "몇 페이지", "페이지", "조항 위치", "섹션")) {
            return DocumentQuestionType.LOCATION;
        }
        if (containsAny(normalized, "compare", "comparison", "difference", "versus", " vs ", "비교", "차이", "각각", "대비", "공통점", "차이점")) {
            return DocumentQuestionType.COMPARISON;
        }
        if (containsAny(normalized, "procedure", "step", "how to", "approval", "apply", "절차", "단계", "방법", "처리", "신청", "승인")) {
            return DocumentQuestionType.PROCEDURE;
        }
        if (containsAny(normalized, "clause", "article", "criteria", "condition", "exception", "limit", "scope", "applies", "조항", "규정", "기준", "적용", "대상", "조건", "예외", "제한", "범위")) {
            return DocumentQuestionType.CLAUSE_EXPLANATION;
        }
        if (containsAny(normalized, "architecture", "아키텍처", "구성", "구조", "전체 구조", "컴포넌트", "모듈")) {
            return DocumentQuestionType.ARCHITECTURE;
        }
        if (containsAny(normalized, "flow", "process", "workflow", "sequence", "흐름", "과정", "절차", "순서", "프로세스")) {
            return DocumentQuestionType.PROCESS_FLOW;
        }
        if (isStructureQuestion(question) || containsAny(normalized, "목차", "섹션", "어떻게 구성")) {
            return DocumentQuestionType.STRUCTURE;
        }
        if (containsAny(normalized, "overview", "summary", "summarize", "개요", "요약", "정리", "전체", "주요", "핵심", "무엇")
                || compact.contains("뭐하는")
                || compact.contains("어떤문서")) {
            return DocumentQuestionType.OVERVIEW;
        }
        return DocumentQuestionType.GENERAL;
    }
    private boolean isOverviewQuestionType(DocumentQuestionType questionType) {
        return questionType == DocumentQuestionType.OVERVIEW
                || questionType == DocumentQuestionType.STRUCTURE
                || questionType == DocumentQuestionType.PROCESS_FLOW
                || questionType == DocumentQuestionType.ARCHITECTURE;
    }

    private boolean usesMixedContextChunks(DocumentQuestionType questionType) {
        return isOverviewQuestionType(questionType) || isStructuredDetailQuestionType(questionType);
    }

    private boolean usesAuxiliaryDocumentQueries(DocumentQuestionType questionType) {
        return isOverviewQuestionType(questionType) || isStructuredDetailQuestionType(questionType);
    }

    private boolean needsMoreOverviewEvidence(List<SearchResult> results, DocumentQuestionType questionType) {
        if (!isOverviewQuestionType(questionType)) {
            return false;
        }
        long contextCount = results.stream().filter(this::isDocumentContext).count();
        long originalCount = results.stream().filter(result -> !isDocumentContext(result)).count();
        return contextCount < properties.getRag().getOverview().getMinContextChunks()
                || originalCount < properties.getRag().getOverview().getMinOriginalChunks();
    }

    private List<String> overviewQueriesClean(String question, DocumentQuestionType questionType, DocumentSpeedProfile speedProfile) {
        String base = safe(question).trim();
        List<String> queries = switch (questionType) {
            case ARCHITECTURE -> List.of(base + " architecture structure components", "문서 구조 구성 아키텍처 개요", "source structure document map overview");
            case PROCESS_FLOW -> List.of(base + " process flow workflow steps", "흐름 과정 절차 단계 순서", "source structure process sequence overview");
            case PROCEDURE -> List.of(base + " procedure process steps conditions exceptions", "절차 단계 조건 예외 제한 처리 기준");
            case CLAUSE_EXPLANATION -> List.of(base + " clause policy criteria conditions exceptions scope", "조항 규정 기준 조건 예외 제한 적용 대상 범위");
            case COMPARISON -> List.of(base + " compare difference conditions exceptions", "비교 차이 공통점 조건 예외 제한");
            case STRUCTURE -> List.of(base + " structure outline sections", "문서 구조 목차 섹션 구성 문서맵", "document structure source structure map");
            case OVERVIEW -> List.of(base + " overview summary main topics", "개요 요약 주요 내용 전체 구조", "source summary document summary representative documents");
            default -> List.of();
        };
        int limit = switch (speedProfile) {
            case FAST -> 1;
            case DEEP -> 3;
            default -> questionType == DocumentQuestionType.OVERVIEW ? 2 : 1;
        };
        return queries.stream().limit(limit).toList();
    }

    private List<String> overviewQueries(String question, DocumentQuestionType questionType, DocumentSpeedProfile speedProfile) {
        if (System.nanoTime() >= 0) {
            return overviewQueriesClean(question, questionType, speedProfile);
        }
        String base = safe(question).trim();
        List<String> queries = switch (questionType) {
            case ARCHITECTURE -> List.of(base + " architecture structure components", "source structure document map overview", "문서 구조 구성 아키텍처 개요");
            case PROCESS_FLOW -> List.of(base + " process flow workflow steps", "source structure process sequence overview", "흐름 과정 절차 단계 구조");
            case PROCEDURE -> List.of(base + " procedure process steps conditions exceptions", "절차 단계 조건 예외 제한 처리 기준");
            case CLAUSE_EXPLANATION -> List.of(base + " clause policy criteria conditions exceptions scope", "조항 규정 기준 조건 예외 제한 적용대상");
            case COMPARISON -> List.of(base + " compare difference conditions exceptions", "비교 차이 공통점 조건 예외 제한");
            case STRUCTURE -> List.of(base + " structure outline sections", "document structure source structure map", "구조 목차 섹션 구성 문서맵");
            case OVERVIEW -> List.of(base + " overview summary main topics", "source summary document summary representative documents", "개요 요약 주요 내용 전체 구조");
            default -> List.of();
        };
        int limit = switch (speedProfile) {
            case FAST -> 1;
            case DEEP -> 3;
            default -> questionType == DocumentQuestionType.OVERVIEW ? 2 : 1;
        };
        return queries.stream().limit(limit).toList();
    }
    private boolean containsAny(String value, String... needles) {
        String safeValue = safe(value);
        for (String needle : needles) {
            if (safeValue.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDocumentContext(SearchResult result) {
        return result != null && isDocumentContext(result.metadata());
    }

    private boolean isDocumentContext(Map<String, Object> metadata) {
        Object value = metadata == null ? null : metadata.get("kind");
        return "document_context".equals(value == null ? "" : String.valueOf(value));
    }

    private String contextType(SearchResult result) {
        Object value = result == null || result.metadata() == null ? null : result.metadata().get("contextType");
        return value == null ? "" : String.valueOf(value);
    }

    private boolean isLowQualityAnswer(String answer) {
        return lowQualityReason(answer) != null;
    }

    private String qualityFailureReason(String answer, int evidenceCount) {
        return qualityFailureReason(answer, evidenceCount, null);
    }

    private String qualityFailureReason(String answer, int evidenceCount, String doneReason) {
        String lowQualityReason = lowQualityReason(answer);
        if (lowQualityReason != null) {
            return lowQualityReason;
        }
        RagPipelineService.AnswerAssessment assessment = pipelineService.assessAnswer(answer, evidenceCount, true, doneReason);
        return assessment.acceptable() ? null : assessment.reason();
    }

    private boolean shouldRepairAnswer(String reason, DocumentSpeedProfile speedProfile) {
        if (!properties.getRag().getPipeline().isAnswerRepairEnabled()
                || speedProfile == DocumentSpeedProfile.FAST
                || pipelineService.maxIterations() <= 1) {
            return false;
        }
        String normalized = safe(reason).toLowerCase();
        return normalized.contains("length")
                || normalized.contains("incomplete")
                || normalized.contains("too_short")
                || normalized.contains("blank");
    }

    private boolean shouldReplaceAnswerWithFallback(String answer, String reason) {
        String normalized = safe(reason).toLowerCase();
        if (normalized.contains("missing_citation")) {
            return safe(answer).trim().length() < 12;
        }
        return normalized.contains("blank")
                || normalized.contains("placeholder")
                || normalized.contains("too_short")
                || normalized.contains("llm_unavailable")
                || normalized.contains("empty");
    }

    private List<SearchResult> compactRepairCitations(
            List<SearchResult> citations,
            AnswerMode answerMode,
            DocumentQuestionType questionType,
            DocumentSpeedProfile speedProfile
    ) {
        if (citations == null || citations.isEmpty()) {
            return List.of();
        }
        int limit = answerMode == AnswerMode.SUMMARY || isOverviewQuestionType(questionType) ? 5 : 4;
        if (speedProfile == DocumentSpeedProfile.DEEP) {
            limit++;
        }
        return citations.stream().limit(Math.min(citations.size(), limit)).toList();
    }

    private String lowQualityReason(String answer) {
        String trimmed = safe(answer).trim();
        if (trimmed.isBlank()) {
            return "blank";
        }
        if ("the".equalsIgnoreCase(trimmed) || "-".equals(trimmed)) {
            return "placeholder";
        }
        if (trimmed.startsWith("The chat LLM is unavailable")) {
            return "llm_unavailable_message";
        }
        if (!containsCitation(trimmed)) {
            return "missing_citation";
        }
        boolean hasConcreteValue = trimmed.matches("(?s).*\\d+\\s*(명|건|개|%|원|페이지|page|row|행).*" );
        if (!hasConcreteValue && trimmed.length() < 12) {
            return "too_short";
        }
        return null;
    }

    private boolean containsCitation(String answer) {
        return answer != null && answer.matches("(?s).*\\[\\d+].*");
    }

    private String cleanConfidence(List<SearchResult> results, boolean llmUnavailable, boolean answerRewritten) {
        if (results == null || results.isEmpty()) {
            return "낮음";
        }
        double topScore = results.get(0).score();
        long distinctDocuments = results.stream().map(SearchResult::documentId).distinct().count();
        long citationCount = results.stream().filter(result -> safe(result.content()).length() >= 80).count();
        String baseConfidence;
        if (topScore >= 0.65 && citationCount >= 3 && distinctDocuments <= 4) {
            baseConfidence = "높음";
        } else if (topScore >= 0.30 || citationCount >= 2) {
            baseConfidence = "보통";
        } else {
            baseConfidence = "낮음";
        }
        if ((llmUnavailable || answerRewritten) && "높음".equals(baseConfidence)) {
            return "보통";
        }
        return baseConfidence;
    }

    private String cleanConfidenceKorean(List<SearchResult> results, boolean llmUnavailable, boolean answerRewritten) {
        if (results == null || results.isEmpty()) {
            return "낮음";
        }
        double topScore = results.get(0).score();
        long distinctDocuments = results.stream().map(SearchResult::documentId).distinct().count();
        long citationCount = results.stream().filter(result -> safe(result.content()).length() >= 80).count();
        String baseConfidence;
        if (topScore >= 0.65 && citationCount >= 3 && distinctDocuments <= 4) {
            baseConfidence = "높음";
        } else if (topScore >= 0.30 || citationCount >= 2) {
            baseConfidence = "보통";
        } else {
            baseConfidence = "낮음";
        }
        if ((llmUnavailable || answerRewritten) && "높음".equals(baseConfidence)) {
            return "보통";
        }
        return baseConfidence;
    }

    private String confidence(List<SearchResult> results, boolean llmUnavailable, boolean answerRewritten) {
        if (System.nanoTime() >= 0) {
            return cleanConfidenceKorean(results, llmUnavailable, answerRewritten);
        }
        if (results == null || results.isEmpty()) {
            return "낮음";
        }
        double topScore = results.get(0).score();
        long distinctDocuments = results.stream().map(SearchResult::documentId).distinct().count();
        long citationCount = results.stream().filter(result -> safe(result.content()).length() >= 80).count();
        String baseConfidence;
        if (topScore >= 0.65 && citationCount >= 3 && distinctDocuments <= 4) {
            baseConfidence = "높음";
        } else if (topScore >= 0.30 || citationCount >= 2) {
            baseConfidence = "보통";
        } else {
            baseConfidence = "낮음";
        }
        if ((llmUnavailable || answerRewritten) && "높음".equals(baseConfidence)) {
            return "보통";
        }
        return baseConfidence;
    }

    private String confidence(
            List<SearchResult> results,
            boolean llmUnavailable,
            boolean answerRewritten,
            RagPipelineService.EvidenceAssessment assessment
    ) {
        if (System.nanoTime() >= 0) {
            String value = cleanConfidenceKorean(results, llmUnavailable, answerRewritten);
            if (assessment != null && !assessment.sufficient() && "높음".equals(value)) {
                return "보통";
            }
            return assessment != null && !assessment.sufficient() && "높음".equals(value) ? "보통" : value;
        }
        String value = confidence(results, llmUnavailable, answerRewritten);
        if (assessment != null && !assessment.sufficient() && "높음".equals(value)) {
            return "보통";
        }
        return value;
    }
    private List<String> diagnostics(
            AnswerMode answerMode,
            List<SearchResult> results,
            boolean llmUnavailable,
            boolean answerRewritten,
            boolean answerRetried,
            boolean answerKeptAfterStreamValidation,
            DocumentRetrieval retrieval,
            DocumentQuestionType questionType,
            AnswerTiming timing
    ) {
        List<String> notes = new ArrayList<>(diagnostics(answerMode, results, llmUnavailable, answerRewritten && !answerKeptAfterStreamValidation));
        if (timing != null) {
            notes.add("Document RAG timing: retrieval=" + timing.retrievalMs()
                    + "ms, embedding=" + retrieval.timing().embeddingMs()
                    + "ms, vector=" + retrieval.timing().vectorSearchMs()
                    + "ms, keyword=" + retrieval.timing().keywordSearchMs()
                    + "ms, rerank=" + retrieval.timing().rerankMs()
                    + "ms, adjacent=" + retrieval.timing().adjacentMs()
                    + "ms, graph=" + retrieval.timing().graphExpansionMs()
                    + "ms, context=" + timing.contextMs()
                    + "ms, llm=" + timing.llmMs()
                    + "ms, total=" + timing.totalMs()
                    + "ms, contextChars=" + timing.contextChars()
                    + ", promptTokens=" + timing.promptTokens()
                    + ", outputTokens=" + timing.outputTokens()
                    + ", citations=" + results.size()
                    + ", queries=" + retrieval.queryCount()
                    + ", embeddingCacheHits=" + retrieval.timing().embeddingCacheHits()
                    + ", expandedQueries=" + retrieval.timing().expandedQueryCount()
                    + ", profile=" + retrieval.effectiveProfile().name() + ".");
        }
        if (isOverviewQuestionType(questionType)) {
            long contextCount = results.stream().filter(this::isDocumentContext).count();
            long originalCount = results.size() - contextCount;
            long distinctDocuments = results.stream().map(SearchResult::documentId).distinct().count();
            long contextRelatedCount = results.stream().filter(result -> metadataBoolean(result, "contextRelatedExpanded")).count();
            notes.add("Question type was classified as " + questionType.name()
                    + "; overview retrieval used " + contextCount + " context chunks, "
                    + originalCount + " original chunks, " + contextRelatedCount
                    + " context-related chunks, and " + distinctDocuments + " distinct documents.");
        }
        if (properties.getDocument().getGraph().isEnabled()) {
            long graphCount = results.stream().filter(result -> metadataBoolean(result, "documentGraphExpanded")).count();
            notes.add("Document graph retrieval enabled with maxHop="
                    + documentGraphMaxHop()
                    + "; graph-expanded citations=" + graphCount + ".");
        }
        if (results != null && !results.isEmpty()) {
            Map<String, Long> schemaCounts = results.stream()
                    .map(result -> metadataString(result, "schemaName"))
                    .filter(value -> !value.isBlank())
                    .collect(Collectors.groupingBy(value -> value, LinkedHashMap::new, Collectors.counting()));
            Map<String, Long> documentTypeCounts = results.stream()
                    .map(result -> metadataString(result, "documentType"))
                    .filter(value -> !value.isBlank())
                    .collect(Collectors.groupingBy(value -> value, LinkedHashMap::new, Collectors.counting()));
            if (!schemaCounts.isEmpty() || !documentTypeCounts.isEmpty()) {
                notes.add("Schema-aware retrieval context: schemas=" + schemaCounts
                        + ", documentTypes=" + documentTypeCounts + ".");
            }
        }
        if (retrieval != null && retrieval.iteration() > 1) {
            notes.add("RAG pipeline retried retrieval once because the first evidence set was weak.");
        }
        if (retrieval != null) {
            notes.add("Document RAG speed profile requested=" + retrieval.requestedProfile().name()
                    + ", effective=" + retrieval.effectiveProfile().name()
                    + (retrieval.profileEscalated() ? " after evidence-based fallback." : "."));
        }
        if (retrieval != null && retrieval.queryPlan() != null) {
            RagPipelineService.QueryPlan plan = retrieval.queryPlan();
            notes.add("Document query rewrite status: attempted=" + plan.rewriteAttempted()
                    + ", used=" + plan.rewriteUsed()
                    + ", failed=" + plan.rewriteFailed()
                    + ", reason=" + plan.reason()
                    + ", queryCount=" + plan.queries().size() + ".");
        }
        if (retrieval != null && retrieval.queryPlan().rewriteUsed()) {
            notes.add("RAG pipeline used query rewrite as an auxiliary retrieval signal.");
        }
        if (retrieval != null && retrieval.queryPlan().rewriteFailed()) {
            notes.add("RAG query rewrite failed, so deterministic hybrid search was used.");
        }
        if (retrieval != null && !retrieval.assessment().sufficient()) {
            notes.add("Evidence sufficiency check remained weak: " + String.join(", ", retrieval.assessment().reasons()));
        }
        if (answerRetried) {
            notes.add("Answer self-check retried generation once before returning the final answer.");
        }
        if (answerKeptAfterStreamValidation) {
            notes.add("Streaming answer was kept after self-check flagged the final text; review citations and confidence before relying on it.");
        }
        return notes;
    }

    private List<String> conversationDiagnostics(
            List<String> diagnostics,
            String originalQuestion,
            String effectiveQuestion,
            RagConversationContext conversationContext,
            DocumentRetrieval retrieval
    ) {
        List<String> notes = new ArrayList<>(diagnostics == null ? List.of() : diagnostics);
        if (conversationContext == null || !conversationContext.contextual()) {
            return notes;
        }
        notes.add("Document conversation context was used: anchors="
                + (conversationContext.documentAnchors() == null ? 0 : conversationContext.documentAnchors().size())
                + ", pinnedCandidates=" + retrieval.pinnedCandidateCount()
                + ", pinnedCitations=" + retrieval.pinnedUsedCount() + ".");
        if (!safe(originalQuestion).equals(safe(effectiveQuestion))) {
            notes.add("Conversation-aware document search question: " + abbreviate(effectiveQuestion));
        }
        if (retrieval.pinnedCandidateCount() == 0
                && conversationContext.documentAnchors() != null
                && !conversationContext.documentAnchors().isEmpty()) {
            notes.add("Previous document evidence could not be pinned, so normal document search fallback was used.");
        }
        return notes;
    }

    private long elapsedMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private void recordMetrics(
            String domain,
            String mode,
            String profile,
            DocumentRetrieval retrieval,
            long retrievalMs,
            long contextMs,
            long llmMs,
            int contextChunkCount,
            int promptTokens,
            int outputTokens,
            int contextChars,
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
                    domain,
                    mode,
                    totalMs,
                    llmMs,
                    retrievalMs,
                    retrieval.timing().embeddingMs(),
                    retrieval.timing().rerankMs(),
                    contextMs,
                    pipelineService.promptTokenBudgetBalanced(),
                    promptTokens,
                    outputTokens,
                    contextChunkCount,
                    retrieval.queryCount(),
                    fallbackUsed,
                    llmUnavailable,
                    profile == null ? "" : profile
            ));
        } catch (RuntimeException ex) {
            log.debug("Document RAG metrics skipped contextChars={} reason={}", contextChars, ex.getMessage());
        }
    }

    private int maxOutputTokens(AnswerMode answerMode, DocumentQuestionType questionType, DocumentSpeedProfile speedProfile) {
        int configured = pipelineService.maxOutputTokens();
        if (configured > 0) {
            return configured;
        }
        return 0;
    }

    private int repairMaxOutputTokens(AnswerMode answerMode, DocumentSpeedProfile speedProfile) {
        int base = answerMode == AnswerMode.SUMMARY || answerMode == AnswerMode.TABLE ? 640 : 448;
        return speedProfile == DocumentSpeedProfile.DEEP ? Math.min(768, base + 128) : base;
    }

    private List<String> cleanDiagnostics(AnswerMode answerMode, List<SearchResult> results, boolean llmUnavailable, boolean answerRewritten) {
        long distinctDocuments = results == null ? 0 : results.stream().map(SearchResult::documentId).distinct().count();
        int resultCount = results == null ? 0 : results.size();
        List<String> notes = new ArrayList<>();
        notes.add("검색된 문서 근거 " + resultCount + "개를 "
                + distinctDocuments + "개 문서에서 선별해 " + cleanModeLabel(answerMode) + " 후보로 사용했습니다.");
        if (llmUnavailable) {
            notes.add("LLM 호출에 실패해 검색 근거 기반 fallback 답변을 반환했습니다.");
        }
        if (answerRewritten) {
            notes.add("LLM 답변이 너무 짧거나 citation이 부족해 검색 근거 기반 답변으로 대체했습니다.");
        }
        if ("낮음".equals(cleanConfidence(results, llmUnavailable, answerRewritten))) {
            notes.add("직접적인 근거 수가 적어 답변을 후보 수준으로 검토해야 합니다.");
        }
        return notes;
    }

    private List<String> diagnostics(AnswerMode answerMode, List<SearchResult> results, boolean llmUnavailable, boolean answerRewritten) {
        if (System.nanoTime() >= 0) {
            return cleanDiagnostics(answerMode, results, llmUnavailable, answerRewritten);
        }
        long distinctDocuments = results.stream().map(SearchResult::documentId).distinct().count();
        List<String> notes = new ArrayList<>();
        notes.add("검색된 문서 근거 " + results.size() + "개를 " + distinctDocuments + "개 문서에서 선별해 " + getModeLabel(answerMode) + " 후보로 사용했습니다.");
        if (llmUnavailable) {
            notes.add("LLM 호출이 실패해 검색 근거 기반 fallback 답변을 반환했습니다.");
        }
        if (answerRewritten) {
            notes.add("LLM 답변이 너무 짧거나 citation이 부족해 검색 근거 기반 답변으로 대체했습니다.");
        }
        if ("낮음".equals(confidence(results, llmUnavailable, answerRewritten))) {
            notes.add("직접적인 근거 수가 적어 답변을 후보 수준으로 검토해야 합니다.");
        }
        return notes;
    }
    private String cleanModeLabel(AnswerMode answerMode) {
        return switch (answerMode) {
            case SUMMARY -> "요약";
            case TABLE -> "표 추출";
            case QUOTE -> "원문 인용";
            default -> "질문 답변";
        };
    }

    private String getModeLabel(AnswerMode answerMode) {
        if (System.nanoTime() >= 0) {
            return cleanModeLabel(answerMode);
        }
        return switch (answerMode) {
            case SUMMARY -> "요약";
            case TABLE -> "표/수치 추출";
            case QUOTE -> "원문 인용";
            default -> "질문 답변";
        };
    }
    private String relevantExcerpt(String question, String content, int maxChars) {
        String compact = safe(content).replaceAll("\\s+", " ").trim();
        if (compact.length() <= maxChars) {
            return compact;
        }

        List<String> terms = queryTerms(question);
        List<String> selected = new ArrayList<>();
        for (String sentence : splitSentences(compact)) {
            if (selected.size() >= 4) {
                break;
            }
            String normalizedSentence = normalizeForSearch(sentence);
            boolean matches = terms.stream().anyMatch(normalizedSentence::contains);
            if (matches && sentence.length() >= 12) {
                selected.add(sentence.trim());
            }
        }

        String excerpt = selected.isEmpty() ? compact.substring(0, Math.min(compact.length(), maxChars)) : String.join(" ", selected);
        if (excerpt.length() > maxChars) {
            excerpt = excerpt.substring(0, maxChars);
        }
        return excerpt.trim() + (excerpt.length() < compact.length() ? "..." : "");
    }

    private List<String> splitSentences(String content) {
        return List.of(content.split("(?<=[.!?。！？])\\s+|(?<=다\\.)\\s+|(?<=요\\.)\\s+|(?<=함\\.)\\s+|(?<=음\\.)\\s+|(?<=니다\\.)\\s+"));
    }

    private boolean isCleanStopWord(String token) {
        return List.of(
                "관련", "대해", "무엇", "뭐", "어떤", "어디", "있는", "없는", "설명", "알려줘", "보여줘",
                "the", "and", "for", "with", "what", "where", "when", "how", "about", "please", "show", "tell"
        ).contains(token);
    }

    private List<String> cleanQueryTerms(String question) {
        String normalized = normalizeForSearch(question);
        List<String> terms = new ArrayList<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2 && !isCleanStopWord(token)) {
                terms.add(token);
            }
        }
        if (containsAny(normalized, "차별", "예방", "개선")) {
            terms.addAll(List.of("차별", "개선", "예방", "처우", "임금", "복리후생", "교육", "고충"));
        }
        if (containsAny(normalized, "근로자", "기간제", "단시간", "파견")) {
            terms.addAll(List.of("기간제", "단시간", "파견", "근로자", "근로조건"));
        }
        if (containsAny(normalized, "조항", "규정", "조건", "예외", "제한", "범위")) {
            terms.addAll(List.of("조항", "규정", "조건", "예외", "제한", "범위", "적용"));
        }
        if (containsAny(normalized, "구조", "목차", "섹션", "페이지", "위치")) {
            terms.addAll(List.of("구조", "목차", "섹션", "페이지", "위치", "heading", "section"));
        }
        return terms.stream().map(this::normalizeForSearch).filter(term -> term.length() >= 2).distinct().toList();
    }

    private List<String> queryTerms(String question) {
        if (System.nanoTime() >= 0) {
            return cleanQueryTerms(question);
        }
        String normalized = normalizeForSearch(question);
        List<String> terms = new ArrayList<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2 && !isStopWord(token)) {
                terms.add(token);
            }
        }
        if (normalized.contains("차별")) {
            terms.addAll(List.of("차별", "개선", "예방", "처우", "임금", "복리후생", "교육", "고충"));
        }
        if (normalized.contains("근로자")) {
            terms.addAll(List.of("기간제", "단시간", "파견", "근로자"));
        }
        return terms.stream().distinct().toList();
    }

    private String normalizeForSearch(String value) {
        return safe(value)
                .toLowerCase()
                .replaceAll("[^\\p{IsHangul}\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isStopWord(String token) {
        return List.of("위해", "뭐가", "무엇", "어떤", "되는거야", "되나요", "있어", "있나요", "관련", "대해").contains(token);
    }

    private List<AnswerEvidence> buildEvidence(List<SearchResult> results) {
        return IntStream.range(0, results.size())
                .mapToObj(index -> {
                    SearchResult result = results.get(index);
                    return new AnswerEvidence(
                            index + 1,
                            result.chunkId(),
                            result.documentId(),
                            result.title(),
                            result.sourceUri(),
                            result.sourceType(),
                            result.chunkIndex(),
                            preview(result.content()),
                            result.score(),
                            evidenceMetadata(result)
                    );
                })
                .toList();
    }

    private Map<String, Object> evidenceMetadata(SearchResult result) {
        if (result == null || result.metadata() == null || result.metadata().isEmpty()) {
            return Map.of();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        copyMetadata(result.metadata(), metadata, "evidenceScore");
        copyMetadata(result.metadata(), metadata, "evidenceRole");
        copyMetadata(result.metadata(), metadata, "evidenceRankReason");
        copyMetadata(result.metadata(), metadata, "conversationPinned");
        copyMetadata(result.metadata(), metadata, "conversationRequired");
        copyMetadata(result.metadata(), metadata, "previousAnswerItem");
        copyMetadata(result.metadata(), metadata, "adjacentExpanded");
        copyMetadata(result.metadata(), metadata, "adjacentDistance");
        copyMetadata(result.metadata(), metadata, "contextRelatedExpanded");
        copyMetadata(result.metadata(), metadata, "contextRelatedReason");
        copyMetadata(result.metadata(), metadata, "rerankerUsed");
        copyMetadata(result.metadata(), metadata, "rerankerScore");
        copyMetadata(result.metadata(), metadata, "rerankerStatus");
        copyMetadata(result.metadata(), metadata, "rerankerDurationMs");
        copyMetadata(result.metadata(), metadata, "documentGraphExpanded");
        copyMetadata(result.metadata(), metadata, "graphDepth");
        copyMetadata(result.metadata(), metadata, "graphEdgeType");
        copyMetadata(result.metadata(), metadata, "contextType");
        copyMetadata(result.metadata(), metadata, "sectionTitle");
        copyMetadata(result.metadata(), metadata, "headingPath");
        copyMetadata(result.metadata(), metadata, "clauseNumber");
        copyMetadata(result.metadata(), metadata, "clauseLevel");
        copyMetadata(result.metadata(), metadata, "structureMatched");
        copyMetadata(result.metadata(), metadata, "sectionMatched");
        copyMetadata(result.metadata(), metadata, "questionType");
        copyMetadata(result.metadata(), metadata, "pageNumber");
        copyMetadata(result.metadata(), metadata, "tableId");
        copyMetadata(result.metadata(), metadata, "sourceUrl");
        copyMetadata(result.metadata(), metadata, "parentDocumentId");
        copyMetadata(result.metadata(), metadata, "schemaName");
        copyMetadata(result.metadata(), metadata, "documentType");
        copyMetadata(result.metadata(), metadata, "documentTypeConfidence");
        return metadata;
    }

    private void copyMetadata(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private String preview(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String compact = content.replaceAll("\\s+", " ").trim();
        return compact.length() <= 420 ? compact : compact.substring(0, 420) + "...";
    }

    private String abbreviate(String value) {
        String compact = safe(value).replaceAll("\\s+", " ").trim();
        return compact.length() <= 120 ? compact : compact.substring(0, 120) + "...";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String nullable(String prefix, String value) {
        return value == null || value.isBlank() ? "" : prefix + value;
    }

    public interface AnswerStreamSink {
        void onEvidence(List<SearchResult> citations, List<AnswerEvidence> evidence);

        void onDelta(String text);

        void onReplace(String answer, String reason);
    }

    private record ComputedAnswer(String answer, List<SearchResult> citations) {
    }

    private record DocumentRetrieval(
            List<SearchResult> citations,
            RagPipelineService.EvidenceAssessment assessment,
            RagPipelineService.QueryPlan queryPlan,
            int iteration,
            int candidateCount,
            int queryCount,
            DocumentSpeedProfile requestedProfile,
            DocumentSpeedProfile effectiveProfile,
            boolean profileEscalated,
            RetrievalTiming timing,
            int pinnedCandidateCount,
            int pinnedUsedCount
    ) {
    }

    private record QuerySearchResults(String query, List<SearchResult> results, SearchService.SearchTiming timing) {
    }

    private record CountEntry(String title, SpreadsheetStats stats, int startCitation, int endCitation) {
    }

    private record EvidencePoint(String text, int citationIndex) {
    }

    private record EvidenceScore(double value, String role, String reason) {
    }

    private record AnswerTiming(
            long retrievalMs,
            long contextMs,
            long llmMs,
            long totalMs,
            int contextChars,
            int promptTokens,
            int outputTokens
    ) {
    }

    private record ContextBundle(List<SearchResult> citations, String context) {
    }

    private record RetrievalTiming(
            long embeddingMs,
            long vectorSearchMs,
            long keywordSearchMs,
            long rerankMs,
            long adjacentMs,
            long graphExpansionMs,
            int embeddingCacheHits,
            int expandedQueryCount
    ) {
    }

    private static class RetrievalTimingAccumulator {
        private long embeddingMs;
        private long vectorSearchMs;
        private long keywordSearchMs;
        private long rerankMs;
        private long adjacentMs;
        private long graphExpansionMs;
        private int embeddingCacheHits;
        private int expandedQueryCount;

        void add(SearchService.SearchTiming timing) {
            if (timing == null) {
                return;
            }
            embeddingMs += timing.embeddingMs();
            vectorSearchMs += timing.vectorSearchMs();
            keywordSearchMs += timing.keywordSearchMs();
            rerankMs += timing.rerankMs();
            if (timing.embeddingCacheHit()) {
                embeddingCacheHits++;
            }
            expandedQueryCount += timing.expandedQueryCount();
        }

        void addAdjacentMs(long value) {
            adjacentMs += value;
        }

        void addGraphExpansionMs(long value) {
            graphExpansionMs += value;
        }

        RetrievalTiming snapshot() {
            return new RetrievalTiming(
                    embeddingMs,
                    vectorSearchMs,
                    keywordSearchMs,
                    rerankMs,
                    adjacentMs,
                    graphExpansionMs,
                    embeddingCacheHits,
                    expandedQueryCount
            );
        }
    }

    private record SpreadsheetStats(
            int dataRowCount,
            int labelledRowCount,
            Integer headerRowNumber,
            Integer minDataRow,
            Integer maxDataRow,
            String nameColumn,
            List<String> sheetNames
    ) {
    }

    private record SpreadsheetRow(
            String sheet,
            int rowNumber,
            Map<String, String> fields,
            String rawLine
    ) {
    }

    private enum AnswerMode {
        QA("qa", "질문에 직접 답하세요. 먼저 결론을 한국어로 쓰고, 필요하면 핵심 근거를 2~5개 bullet로 덧붙이세요. 모든 사실 주장에는 근거 번호를 붙이세요."),
        SUMMARY("summary", "검색된 문맥을 한국어 Markdown bullet로 요약하세요. 관련 항목을 묶고 중요한 주장에는 근거 번호를 붙이세요."),
        TABLE("table", "문맥에서 표 형태로 정리할 수 있는 사실만 추출하세요. 가능하면 간결한 Markdown 표를 사용하세요. 없는 행, 열, 개수, 값을 만들지 마세요."),
        QUOTE("quote", "문맥의 짧은 직접 인용을 우선 사용하세요. 각 인용은 Markdown blockquote로 쓰고, 바로 아래에 의미를 한국어로 짧게 설명하세요. 모든 인용에는 근거 번호를 붙이세요.");

        private final String value;
        private final String instruction;

        AnswerMode(String value, String instruction) {
            this.value = value;
            this.instruction = instruction;
        }

        static AnswerMode from(String value) {
            if (value == null || value.isBlank()) {
                return QA;
            }
            for (AnswerMode mode : values()) {
                if (mode.value.equalsIgnoreCase(value.trim())) {
                    return mode;
                }
            }
            return QA;
        }

        String value() {
            return value;
        }

        String instruction() {
            return instruction;
        }
    }

    private enum DocumentQuestionType {
        GENERAL,
        OVERVIEW,
        STRUCTURE,
        PROCESS_FLOW,
        ARCHITECTURE,
        CLAUSE_EXPLANATION,
        COMPARISON,
        PROCEDURE,
        COUNT_OR_TABLE,
        LOCATION
    }

    private enum DocumentSpeedProfile {
        FAST,
        BALANCED,
        DEEP;

        static DocumentSpeedProfile from(String value, String fallback) {
            String candidate = value == null || value.isBlank() ? fallback : value;
            if (candidate == null || candidate.isBlank()) {
                return BALANCED;
            }
            for (DocumentSpeedProfile profile : values()) {
                if (profile.name().equalsIgnoreCase(candidate.trim())) {
                    return profile;
                }
            }
            return BALANCED;
        }
    }
}
