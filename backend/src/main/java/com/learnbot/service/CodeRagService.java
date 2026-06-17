package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeAskResponse;
import com.learnbot.dto.CodeEvidence;
import com.learnbot.dto.CodeSearchResult;
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
    private static final int OVERVIEW_CONTEXT_LIMIT = 8;
    private static final int DEFAULT_CONTEXT_LIMIT = 6;
    private static final int OVERVIEW_CONTEXT_CHARS = 520;
    private static final int DEFAULT_CONTEXT_CHARS = 720;
    private static final int FALLBACK_EXCERPT_CHARS = 180;

    private final CodeSearchService searchService;
    private final CodeReferenceService referenceService;
    private final CommitInsightService commitInsightService;
    private final OllamaClient ollamaClient;
    private final LearnBotProperties properties;
    private final RagPipelineService pipelineService;

    @Autowired
    public CodeRagService(
            CodeSearchService searchService,
            CodeReferenceService referenceService,
            CommitInsightService commitInsightService,
            OllamaClient ollamaClient,
            LearnBotProperties properties,
            RagPipelineService pipelineService
    ) {
        this.searchService = searchService;
        this.referenceService = referenceService;
        this.commitInsightService = commitInsightService;
        this.ollamaClient = ollamaClient;
        this.properties = properties;
        this.pipelineService = pipelineService;
    }

    CodeRagService(
            CodeSearchService searchService,
            CodeReferenceService referenceService,
            CommitInsightService commitInsightService,
            OllamaClient ollamaClient,
            LearnBotProperties properties
    ) {
        this(searchService, referenceService, commitInsightService, ollamaClient, properties, new RagPipelineService(ollamaClient, properties));
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
        CodeQuestionMode questionMode = CodeQuestionMode.from(mode);
        int safeLimit = safeLimit(questionMode, limit);
        CodeRetrieval retrieval = retrieveCodeEvidence(repositoryId, selectedSpaceId, spaceIds, question, questionMode, safeLimit);
        List<CodeSearchResult> results = retrieval.results();
        if (results.isEmpty()) {
            return new CodeAskResponse(
                    questionMode.value(),
                    "코드 근거가 부족해 답변할 수 없습니다. 질문 범위를 좁히거나 파일명, 화면명, 메서드명 같은 단서를 더 넣어주세요.",
                    List.of(),
                    "낮음",
                    List.of("검색된 코드 근거가 없어 추측 답변을 생성하지 않았습니다.")
            );
        }

        List<CodeSearchResult> answerResults = answerContextResults(questionMode, question, results);
        String systemPrompt = """
                You are LearnBot Code, a private source-code RAG assistant.
                Answer in Korean using only the provided source-code context.
                Do not invent files, classes, methods, or behavior not shown in the context.
                Always cite evidence with bracket numbers like [1].
                Mention file path and line range when explaining code.
                If evidence is insufficient, say what is missing and list the closest files found.
                Include a short reliability note when evidence is weak or indirect.
                """ + "\n" + questionMode.instruction();

        String userPrompt = "Question:\n" + question + "\n\nSource-code context:\n" + buildContext(question, questionMode, answerResults);
        String answer;
        boolean llmUnavailable = false;
        boolean answerRewritten = false;
        boolean answerRetried = false;
        try {
            answer = ollamaClient.chat(systemPrompt, userPrompt);
            String qualityReason = qualityFailureReason(answer, answerResults.size());
            if (qualityReason != null && pipelineService.maxIterations() > 1) {
                String retryPrompt = userPrompt
                        + "\n\nPrevious answer failed quality check: " + qualityReason + "."
                        + "\nRewrite the answer using only the cited code context. Cite every factual claim with [n].";
                String retryAnswer = ollamaClient.chat(systemPrompt + "\nBe concise and citation-strict.", retryPrompt);
                if (qualityFailureReason(retryAnswer, answerResults.size()) == null) {
                    answer = retryAnswer;
                    answerRetried = true;
                }
            }
        } catch (RuntimeException ex) {
            answer = fallbackAnswer(questionMode, question, answerResults);
            llmUnavailable = true;
        }
        if (qualityFailureReason(answer, answerResults.size()) != null) {
            answer = questionMode == CodeQuestionMode.OVERVIEW
                    ? overviewFallbackAnswer(answerResults)
                    : fallbackAnswer(questionMode, question, answerResults);
            answerRewritten = true;
        }
        return new CodeAskResponse(
                questionMode.value(),
                answer,
                buildEvidence(answerResults),
                confidence(answerResults, retrieval.assessment()),
                diagnostics(results, answerResults, llmUnavailable, answerRewritten, answerRetried, retrieval)
        );
    }

    private int safeLimit(CodeQuestionMode questionMode, Integer limit) {
        int defaultLimit = questionMode == CodeQuestionMode.OVERVIEW
                ? Math.max(properties.getCode().getTopK(), 14)
                : properties.getCode().getTopK();
        return limit == null ? defaultLimit : Math.max(1, Math.min(limit, 24));
    }

    private CodeRetrieval retrieveCodeEvidence(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            String question,
            CodeQuestionMode questionMode,
            int limit
    ) {
        Map<UUID, CodeSearchResult> merged = new LinkedHashMap<>();
        int searchLimit = pipelineService.codeSearchLimit(questionMode == CodeQuestionMode.OVERVIEW ? limit + 12 : limit + 8);
        collectEvidenceForQuery(repositoryId, selectedSpaceId, spaceIds, question, questionMode, searchLimit, merged);
        List<CodeSearchResult> results = rankedCodeEvidence(question, questionMode, merged, limit);
        RagPipelineService.EvidenceAssessment assessment = pipelineService.assessCode(
                question,
                results,
                minCodeEvidence(questionMode),
                1
        );
        RagPipelineService.QueryPlan queryPlan = new RagPipelineService.QueryPlan(
                RagPipelineService.Domain.CODE,
                List.of(question),
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

        return new CodeRetrieval(results, assessment, queryPlan, iteration, merged.size());
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
        return merged.values().stream()
                .sorted(Comparator.comparingDouble((CodeSearchResult result) -> answerRelevance(question, questionMode, result)).reversed())
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
        return merged.values().stream()
                .sorted(Comparator.comparingDouble((CodeSearchResult result) -> answerRelevance(question, questionMode, result)).reversed())
                .limit(limit)
                .toList();
    }

    private List<CodeSearchResult> answerContextResults(CodeQuestionMode questionMode, String question, List<CodeSearchResult> results) {
        int limit = pipelineService.codeContextLimit(questionMode == CodeQuestionMode.OVERVIEW ? OVERVIEW_CONTEXT_LIMIT : DEFAULT_CONTEXT_LIMIT);
        List<CodeSearchResult> ranked = results.stream()
                .sorted(Comparator.comparingDouble((CodeSearchResult result) -> answerRelevance(question, questionMode, result)).reversed())
                .toList();
        if (questionMode == CodeQuestionMode.CALL_FLOW) {
            return ranked.stream()
                    .sorted(Comparator.comparingInt(this::flowRank).thenComparing(Comparator.comparingDouble(CodeSearchResult::score).reversed()))
                    .limit(limit)
                    .toList();
        }
        if (questionMode == CodeQuestionMode.OVERVIEW || questionMode == CodeQuestionMode.IMPACT) {
            return diverseByCategory(ranked, limit);
        }
        return ranked.stream().limit(limit).toList();
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
        return selected.values().stream().limit(limit).toList();
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
                            + "\n" + codeExcerpt(question, result, maxChars);
                })
                .collect(Collectors.joining("\n\n"));
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
                            result.metadata()
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
        double topScore = results.get(0).score();
        long distinctFiles = results.stream().map(CodeSearchResult::filePath).distinct().count();
        boolean hasStructuredEvidence = results.stream().anyMatch(result ->
                isStructured(result.chunkType()) || notBlank(result.methodName()) || notBlank(result.className()) || notBlank(result.symbolName())
        );
        if (hasStructuredEvidence && results.size() >= 4 && topScore >= 0.55 && distinctFiles <= 6) {
            return "높음";
        }
        if (hasStructuredEvidence || results.size() >= 3 || topScore >= 0.35) {
            return "보통";
        }
        return "낮음";
    }

    private String confidence(List<CodeSearchResult> results, RagPipelineService.EvidenceAssessment assessment) {
        String value = confidence(results);
        if (assessment != null && !assessment.sufficient() && "?믪쓬".equals(value)) {
            return "蹂댄넻";
        }
        return value;
    }

    private List<String> diagnostics(
            List<CodeSearchResult> results,
            List<CodeSearchResult> answerResults,
            boolean llmUnavailable,
            boolean answerRewritten,
            boolean answerRetried,
            CodeRetrieval retrieval
    ) {
        List<String> notes = new ArrayList<>(diagnostics(results, answerResults, llmUnavailable, answerRewritten));
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
        RagPipelineService.AnswerAssessment assessment = pipelineService.assessAnswer(answer, evidenceCount, true);
        return assessment.acceptable() ? null : assessment.reason();
    }

    private boolean containsCitation(String answer) {
        return answer != null && answer.matches("(?s).*\\[\\d+].*");
    }

    private double answerRelevance(String question, CodeQuestionMode mode, CodeSearchResult result) {
        double score = result.score();
        List<String> terms = primaryQuestionTerms(question);
        String path = normalizeCodeText(result.filePath());
        String symbolText = normalizeCodeText(String.join(" ",
                safe(result.symbolName(), ""),
                safe(result.className(), ""),
                safe(result.methodName(), ""),
                safe(result.controlName(), ""),
                safe(result.eventName(), "")
        ));
        String content = normalizeCodeText(result.content());

        for (String term : terms) {
            if (path.contains(term)) {
                score += 0.55;
            }
            if (symbolText.contains(term)) {
                score += 0.45;
            }
            if (content.contains(term)) {
                score += 0.12;
            }
        }
        if (isStructured(result.chunkType())) {
            score += 0.08;
        }
        if (mode == CodeQuestionMode.CALL_FLOW) {
            score += Math.max(0, 0.08 * (5 - flowRank(result)));
        }
        if (isLoginQuestion(question) && path.contains("git") && !path.contains("auth") && !path.contains("login")) {
            score -= 0.6;
        }
        return score;
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
        if (current == null || result.score() > current.score()) {
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
                || "xaml_view".equals(chunkType);
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String nullable(String prefix, String value) {
        return value == null || value.isBlank() ? "" : prefix + value;
    }

    private record CodeRetrieval(
            List<CodeSearchResult> results,
            RagPipelineService.EvidenceAssessment assessment,
            RagPipelineService.QueryPlan queryPlan,
            int iteration,
            int candidateCount
    ) {
    }

    private enum CodeQuestionMode {
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
