package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.AnswerEvidence;
import com.learnbot.dto.AskResponse;
import com.learnbot.dto.DocumentChunkDetail;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class RagService {
    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final Pattern SPREADSHEET_ROW = Pattern.compile("^Sheet\\s+(.+?)\\s+Row\\s+(\\d+):\\s*(.*)$");
    private static final int GENERAL_CONTEXT_RESULT_LIMIT = 4;
    private static final int GENERAL_CONTEXT_EXCERPT_CHARS = 360;
    private static final int TABLE_CONTEXT_EXCERPT_CHARS = 520;
    private static final int FALLBACK_RESULT_LIMIT = 4;
    private static final int FALLBACK_EXCERPT_CHARS = 260;
    private static final int FALLBACK_POINT_CHARS = 220;
    private static final List<String> HEADER_WORDS = List.of(
            "연번", "직종", "이름", "성명", "직원명", "사원명", "본부", "직급", "성별", "비고", "대상자녀수", "name"
    );

    private final SearchService searchService;
    private final OllamaClient ollamaClient;
    private final DocumentRepository documentRepository;
    private final LearnBotProperties properties;
    private final RagPipelineService pipelineService;

    public RagService(
            SearchService searchService,
            OllamaClient ollamaClient,
            DocumentRepository documentRepository,
            LearnBotProperties properties
    ) {
        this(searchService, ollamaClient, documentRepository, properties, new RagPipelineService(ollamaClient, properties));
    }

    @Autowired
    public RagService(
            SearchService searchService,
            OllamaClient ollamaClient,
            DocumentRepository documentRepository,
            LearnBotProperties properties,
            RagPipelineService pipelineService
    ) {
        this.searchService = searchService;
        this.ollamaClient = ollamaClient;
        this.documentRepository = documentRepository;
        this.properties = properties;
        this.pipelineService = pipelineService;
    }

    public AskResponse ask(String question, SearchFilter filter, String mode) {
        return ask(question, filter, mode, null, null);
    }

    public AskResponse ask(String question, SearchFilter filter, String mode, List<UUID> spaceIds, UUID selectedSpaceId) {
        AnswerMode answerMode = AnswerMode.from(mode);
        int topK = retrievalLimit(question, answerMode);
        DocumentRetrieval retrieval = retrieveDocuments(question, filter, answerMode, topK, spaceIds, selectedSpaceId);
        List<SearchResult> citations = retrieval.citations();
        if (citations.isEmpty()) {
            return new AskResponse(
                    answerMode.value(),
                    "근거가 부족해 답변할 수 없습니다.",
                    citations,
                    List.of(),
                    "낮음",
                    List.of("검색된 문서 근거가 없어 추측 답변을 생성하지 않았습니다.")
            );
        }

        Optional<ComputedAnswer> computedAnswer = maybeAnswerSpreadsheetCount(question, citations);
        if (computedAnswer.isPresent()) {
            ComputedAnswer computed = computedAnswer.get();
            return new AskResponse(
                    answerMode.value(),
                    computed.answer(),
                    computed.citations(),
                    buildEvidence(computed.citations()),
                    "높음",
                    List.of("엑셀/CSV 집계값은 LLM이 아니라 서버가 문서 전체 청크를 기준으로 계산했습니다.")
            );
        }

        String context = buildContext(question, answerMode, citations);
        String systemPrompt = systemPrompt(answerMode);

        String userPrompt = "Question:\n" + question + "\n\nContext:\n" + context;
        String answer;
        boolean llmUnavailable = false;
        boolean answerRewritten = false;
        boolean answerRetried = false;
        String answerDoneReason = null;
        try {
            OllamaClient.ChatResult chatResult = ollamaClient.chatResult(systemPrompt, userPrompt);
            answer = chatResult.content();
            answerDoneReason = chatResult.doneReason();
            String qualityReason = qualityFailureReason(answer, citations.size(), answerDoneReason);
            if (qualityReason != null && pipelineService.maxIterations() > 1) {
                log.info("RAG answer retry mode={} reason={} citations={} question={}",
                        answerMode.value(), qualityReason, citations.size(), abbreviate(question));
                String retryPrompt = userPrompt
                        + "\n\nPrevious answer failed quality check: " + qualityReason + "."
                        + "\nRewrite the answer using only the cited context. Cite every factual claim with [n].";
                OllamaClient.ChatResult retryResult = ollamaClient.chatResult(systemPrompt + "\nBe concise and citation-strict.", retryPrompt);
                String retryAnswer = retryResult.content();
                if (qualityFailureReason(retryAnswer, citations.size(), retryResult.doneReason()) == null) {
                    answer = retryAnswer;
                    answerDoneReason = retryResult.doneReason();
                    answerRetried = true;
                }
            }
        } catch (RuntimeException ex) {
            log.warn("RAG LLM call failed mode={} citations={} question={}",
                    answerMode.value(), citations.size(), abbreviate(question), ex);
            answer = fallbackAnswer(answerMode, question, citations);
            answerDoneReason = null;
            llmUnavailable = true;
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
            answer = fallbackAnswer(answerMode, question, citations);
            answerRewritten = true;
        }
        return new AskResponse(
                answerMode.value(),
                answer,
                citations,
                buildEvidence(citations),
                confidence(citations, llmUnavailable, answerRewritten, retrieval.assessment()),
                diagnostics(answerMode, citations, llmUnavailable, answerRewritten, answerRetried, retrieval)
        );
    }

    private DocumentRetrieval retrieveDocuments(
            String question,
            SearchFilter filter,
            AnswerMode answerMode,
            int topK,
            List<UUID> spaceIds,
            UUID selectedSpaceId
    ) {
        Map<UUID, SearchResult> merged = new LinkedHashMap<>();
        List<String> queriesUsed = new ArrayList<>();
        int searchLimit = pipelineService.documentSearchLimit(topK);

        searchAndMergeDocuments(question, question, filter, searchLimit, spaceIds, selectedSpaceId, merged, queriesUsed);
        List<SearchResult> citations = selectAnswerCitations(question, answerMode, List.copyOf(merged.values()));
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
                "initial search"
        );
        int iteration = 1;

        if (!isCountQuestion(question) && !assessment.sufficient() && pipelineService.maxIterations() > 1) {
            queryPlan = pipelineService.buildQueryPlan(
                    question,
                    RagPipelineService.Domain.DOCUMENT,
                    searchService.expandedQueries(question)
            );
            for (String query : queryPlan.queries()) {
                searchAndMergeDocuments(question, query, filter, searchLimit, spaceIds, selectedSpaceId, merged, queriesUsed);
            }
            iteration = 2;
            citations = selectAnswerCitations(question, answerMode, List.copyOf(merged.values()));
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
        return new DocumentRetrieval(citations, assessment, queryPlan, iteration, merged.size(), queriesUsed.size());
    }

    private void searchAndMergeDocuments(
            String originalQuestion,
            String query,
            SearchFilter filter,
            int limit,
            List<UUID> spaceIds,
            UUID selectedSpaceId,
            Map<UUID, SearchResult> merged,
            List<String> queriesUsed
    ) {
        String safeQuery = safe(query).trim();
        if (safeQuery.isBlank() || queriesUsed.contains(safeQuery)) {
            return;
        }
        queriesUsed.add(safeQuery);
        try {
            for (SearchResult result : searchService.search(safeQuery, filter, limit, spaceIds, selectedSpaceId)) {
                mergeDocument(merged, safeQuery.equals(originalQuestion) ? result : boostDocument(result, 0.03));
            }
        } catch (RuntimeException ex) {
            log.warn("RAG retrieval query failed query={} question={}", abbreviate(safeQuery), abbreviate(originalQuestion), ex);
        }
    }

    private void mergeDocument(Map<UUID, SearchResult> merged, SearchResult result) {
        SearchResult current = merged.get(result.chunkId());
        if (current == null || result.score() > current.score()) {
            merged.put(result.chunkId(), result);
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

    private int minDocumentEvidence(AnswerMode answerMode) {
        return switch (answerMode) {
            case SUMMARY, TABLE -> 4;
            case QUOTE -> 1;
            default -> 2;
        };
    }

    private int retrievalLimit(String question, AnswerMode answerMode) {
        int configured = properties.getRag().getTopK();
        if (isCountQuestion(question) || answerMode == AnswerMode.TABLE) {
            return Math.max(configured, 12);
        }
        if (answerMode == AnswerMode.SUMMARY) {
            return Math.max(configured, 10);
        }
        return Math.max(configured, 8);
    }

    private List<SearchResult> selectAnswerCitations(String question, AnswerMode answerMode, List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        int limit = switch (answerMode) {
            case SUMMARY, TABLE -> Math.min(results.size(), 10);
            case QUOTE -> Math.min(results.size(), 6);
            default -> Math.min(results.size(), Math.max(6, properties.getRag().getTopK()));
        };
        List<SearchResult> ordered = results.stream()
                .sorted(Comparator
                        .comparingDouble((SearchResult result) -> answerRelevance(question, answerMode, result))
                        .reversed())
                .toList();
        if (answerMode == AnswerMode.QUOTE || answerMode == AnswerMode.TABLE || isCountQuestion(question)) {
            List<SearchResult> originals = ordered.stream().filter(result -> !isDocumentContext(result)).limit(limit).toList();
            if (!originals.isEmpty()) {
                return originals;
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
        return selected;
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

    private String buildContext(String question, AnswerMode answerMode, List<SearchResult> results) {
        if (results.isEmpty()) {
            return "No context retrieved.";
        }

        int limit = Math.min(results.size(), contextLimit(answerMode));
        int excerptChars = answerMode == AnswerMode.TABLE ? TABLE_CONTEXT_EXCERPT_CHARS : GENERAL_CONTEXT_EXCERPT_CHARS;
        return IntStream.range(0, limit)
                .mapToObj(index -> {
                    SearchResult result = results.get(index);
                    return "[" + (index + 1) + "] " + result.title()
                            + " · " + result.sourceUri()
                            + " · " + safe(result.contentType())
                            + " · chunk " + result.chunkIndex() + "\n"
                            + relevantExcerpt(question, result.content(), excerptChars);
                })
                .collect(Collectors.joining("\n\n"));
    }

    private int contextLimit(AnswerMode answerMode) {
        return switch (answerMode) {
            case SUMMARY, TABLE -> pipelineService.documentContextLimit(8);
            case QUOTE -> 6;
            default -> pipelineService.documentContextLimit(GENERAL_CONTEXT_RESULT_LIMIT);
        };
    }

    private String systemPrompt(AnswerMode answerMode) {
        return """
                You are LearnBot, a private RAG assistant.
                Answer in Korean using Markdown.
                Use only the provided context.
                Cite factual claims with bracket numbers such as [1].
                If the evidence is insufficient, say exactly which part is insufficient.
                Do not invent sources, counts, filenames, pages, or legal clauses.
                Do not answer with only a source list.
                """ + "\n" + answerMode.instruction();
    }

    private String fallbackAnswer(AnswerMode answerMode, String question, List<SearchResult> results) {
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
            case SUMMARY -> summaryFallbackAnswer(question, results);
            case TABLE -> tableFallbackAnswer(question, results);
            case QUOTE -> quoteFallbackAnswer(question, results);
            default -> extractiveFallbackAnswer(question, results);
        };
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
                You are LearnBot, a private RAG assistant.
                Answer in Korean.
                The numeric facts below were computed deterministically by the server. Do not change the count.
                Write a concise natural-language answer for the user.
                Include the exact total count and a short explanation of the counting basis.
                Cite evidence with bracket numbers like [1].
                Use the same unit as the user when possible. For people use 명; for generic items use 개 or 건.
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

    private boolean isCountQuestion(String question) {
        String normalized = safe(question).replaceAll("\\s+", "").toLowerCase();
        return normalized.contains("총몇")
                || normalized.contains("몇명")
                || normalized.contains("몇명이")
                || normalized.contains("몇개")
                || normalized.contains("몇개의")
                || normalized.contains("인원")
                || normalized.contains("대상자수")
                || normalized.contains("총원")
                || normalized.contains("몇건")
                || normalized.contains("건수")
                || normalized.contains("개수")
                || normalized.contains("총개수")
                || normalized.contains("총건수")
                || normalized.contains("count")
                || normalized.contains("total");
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

    private boolean isStructureQuestion(String question) {
        String normalized = normalizeForSearch(question);
        return normalized.contains("structure")
                || normalized.contains("section")
                || normalized.contains("page")
                || normalized.contains("slide")
                || normalized.contains("sheet")
                || normalized.contains("table")
                || normalized.contains("where")
                || normalized.contains("구조")
                || normalized.contains("목차")
                || normalized.contains("섹션")
                || normalized.contains("페이지")
                || normalized.contains("표")
                || normalized.contains("시트")
                || normalized.contains("어디");
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

    private String lowQualityReason(String answer) {
        String trimmed = safe(answer).trim();
        if (trimmed.isBlank()) {
            return "blank";
        }
        if ("the".equalsIgnoreCase(trimmed) || "제".equals(trimmed)) {
            return "placeholder";
        }
        if (trimmed.startsWith("The chat LLM is unavailable")) {
            return "llm_unavailable_message";
        }
        if (!containsCitation(trimmed)) {
            return "missing_citation";
        }
        boolean hasConcreteValue = trimmed.matches("(?s).*\\d+\\s*(명|건|개|행).*");
        if (!hasConcreteValue && trimmed.length() < 12) {
            return "too_short";
        }
        return null;
    }

    private boolean containsCitation(String answer) {
        return answer != null && answer.matches("(?s).*\\[\\d+].*");
    }

    private String confidence(List<SearchResult> results, boolean llmUnavailable, boolean answerRewritten) {
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
            DocumentRetrieval retrieval
    ) {
        List<String> notes = new ArrayList<>(diagnostics(answerMode, results, llmUnavailable, answerRewritten));
        if (retrieval != null && retrieval.iteration() > 1) {
            notes.add("RAG pipeline retried retrieval once because the first evidence set was weak.");
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
        return notes;
    }

    private List<String> diagnostics(AnswerMode answerMode, List<SearchResult> results, boolean llmUnavailable, boolean answerRewritten) {
        long distinctDocuments = results.stream().map(SearchResult::documentId).distinct().count();
        List<String> notes = new ArrayList<>();
        notes.add("검색된 문서 근거 " + results.size() + "개, 문서 " + distinctDocuments + "개를 " + getModeLabel(answerMode) + " 후보로 사용했습니다.");
        if (llmUnavailable) {
            notes.add("LLM 호출이 실패해 검색 근거 기반 fallback 답변을 반환했습니다.");
        }
        if (answerRewritten) {
            notes.add("LLM 응답이 너무 짧거나 인용이 부족해, 검색 근거 기반 답변으로 대체했습니다.");
        }
        if ("낮음".equals(confidence(results, llmUnavailable, answerRewritten))) {
            notes.add("직접적인 근거 점수가 낮아 답변을 후보 수준으로 검토해야 합니다.");
        }
        return notes;
    }

    private String getModeLabel(AnswerMode answerMode) {
        return switch (answerMode) {
            case SUMMARY -> "요약";
            case TABLE -> "표 추출";
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

    private List<String> queryTerms(String question) {
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
                            result.score()
                    );
                })
                .toList();
    }

    private String preview(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String compact = content.replaceAll("\\s+", " ").trim();
        return compact.length() <= 260 ? compact : compact.substring(0, 260) + "...";
    }

    private String abbreviate(String value) {
        String compact = safe(value).replaceAll("\\s+", " ").trim();
        return compact.length() <= 120 ? compact : compact.substring(0, 120) + "...";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record ComputedAnswer(String answer, List<SearchResult> citations) {
    }

    private record DocumentRetrieval(
            List<SearchResult> citations,
            RagPipelineService.EvidenceAssessment assessment,
            RagPipelineService.QueryPlan queryPlan,
            int iteration,
            int candidateCount,
            int queryCount
    ) {
    }

    private record CountEntry(String title, SpreadsheetStats stats, int startCitation, int endCitation) {
    }

    private record EvidencePoint(String text, int citationIndex) {
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
        QA("qa", "Answer the question directly. Start with the conclusion, then add 2-5 concise supporting bullets when useful. Cite each factual claim."),
        SUMMARY("summary", "Summarize the retrieved context into Korean Markdown bullets. Group related points and cite important points."),
        TABLE("table", "Extract table-like facts from the context. Prefer a compact Markdown table. Do not invent missing rows, columns, counts, or values."),
        QUOTE("quote", "Prefer short direct excerpts from the context. Put each excerpt in a Markdown blockquote, then explain its meaning briefly. Cite every excerpt.");

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
}
