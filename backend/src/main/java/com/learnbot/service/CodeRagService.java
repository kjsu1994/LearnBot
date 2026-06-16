package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeAskResponse;
import com.learnbot.dto.CodeEvidence;
import com.learnbot.dto.CodeSearchResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class CodeRagService {
    private final CodeSearchService searchService;
    private final CodeReferenceService referenceService;
    private final OllamaClient ollamaClient;
    private final LearnBotProperties properties;

    public CodeRagService(
            CodeSearchService searchService,
            CodeReferenceService referenceService,
            OllamaClient ollamaClient,
            LearnBotProperties properties
    ) {
        this.searchService = searchService;
        this.referenceService = referenceService;
        this.ollamaClient = ollamaClient;
        this.properties = properties;
    }

    public CodeAskResponse ask(UUID repositoryId, String question, String mode, Integer limit) {
        return ask(repositoryId, null, java.util.List.of(com.learnbot.repository.SecurityRepository.DEFAULT_SPACE_ID), question, mode, limit);
    }

    public CodeAskResponse ask(UUID repositoryId, UUID selectedSpaceId, List<UUID> spaceIds, String question, String mode, Integer limit) {
        CodeQuestionMode questionMode = CodeQuestionMode.from(mode);
        int safeLimit = safeLimit(questionMode, limit);
        List<CodeSearchResult> results = collectEvidence(repositoryId, selectedSpaceId, spaceIds, question, questionMode, safeLimit);
        if (results.isEmpty()) {
            return new CodeAskResponse(
                    questionMode.value(),
                    "코드 근거가 부족해 답변할 수 없습니다. 질문 범위를 좁히거나 파일명, 화면명, 메서드명 같은 단서를 더 넣어주세요.",
                    List.of(),
                    "낮음",
                    List.of("검색된 코드 근거가 없어 추측 답변을 생성하지 않았습니다.")
            );
        }

        String systemPrompt = """
                You are LearnBot Code, a private source-code RAG assistant inspired by Sourcegraph Cody.
                Answer in Korean unless the user asks for another language.
                Use only the provided source-code context.
                Do not invent files, classes, methods, or behavior not shown in the context.
                Always cite evidence with bracket numbers like [1].
                Mention file path and line range when explaining code.
                If evidence is insufficient, say what is missing and list the closest files found.
                Include a short reliability note when evidence is weak or indirect.
                """ + "\n" + questionMode.instruction();

        String userPrompt = "Question:\n" + question + "\n\nSource-code context:\n" + buildContext(results);
        String answer;
        boolean llmUnavailable = false;
        boolean answerRewritten = false;
        try {
            answer = ollamaClient.chat(systemPrompt, userPrompt);
        } catch (RuntimeException ex) {
            answer = fallbackAnswer(results);
            llmUnavailable = true;
        }
        if (isLowQualityAnswer(answer, questionMode)) {
            answer = questionMode == CodeQuestionMode.OVERVIEW
                    ? overviewFallbackAnswer(results)
                    : fallbackAnswer(results);
            answerRewritten = true;
        }
        return new CodeAskResponse(questionMode.value(), answer, buildEvidence(results), confidence(results), diagnostics(results, llmUnavailable, answerRewritten));
    }

    private int safeLimit(CodeQuestionMode questionMode, Integer limit) {
        int defaultLimit = questionMode == CodeQuestionMode.OVERVIEW
                ? Math.max(properties.getCode().getTopK(), 14)
                : properties.getCode().getTopK();
        return limit == null ? defaultLimit : Math.max(1, Math.min(limit, 24));
    }

    private List<CodeSearchResult> collectEvidence(
            UUID repositoryId,
            UUID selectedSpaceId,
            List<UUID> spaceIds,
            String question,
            CodeQuestionMode questionMode,
            int limit
    ) {
        if (questionMode != CodeQuestionMode.OVERVIEW) {
            return searchService.search(repositoryId, question, limit, spaceIds, selectedSpaceId);
        }

        Map<UUID, CodeSearchResult> merged = new LinkedHashMap<>();
        for (CodeSearchResult result : searchService.search(repositoryId, question, Math.min(30, limit + 8), spaceIds, selectedSpaceId)) {
            merge(merged, result);
        }
        for (String identifier : searchService.identifiersFrom(question)) {
            try {
                var references = referenceService.findReferences(repositoryId, selectedSpaceId, spaceIds, identifier, 10);
                for (CodeSearchResult definition : references.definitions()) {
                    merge(merged, boost(definition, 0.28));
                }
                for (CodeSearchResult reference : references.references()) {
                    merge(merged, boost(reference, 0.12));
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid symbol candidates should not block a natural-language overview answer.
            }
        }
        return merged.values().stream()
                .sorted(Comparator.comparingDouble(CodeSearchResult::score).reversed())
                .limit(limit)
                .toList();
    }

    private String buildContext(List<CodeSearchResult> results) {
        if (results.isEmpty()) {
            return "No source-code context retrieved.";
        }
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
                            + "\n" + result.content();
                })
                .collect(Collectors.joining("\n\n"));
    }

    private String fallbackAnswer(List<CodeSearchResult> results) {
        if (results.isEmpty()) {
            return "LLM 답변을 생성하지 못했고 관련 코드 근거도 찾지 못했습니다.";
        }
        String sources = IntStream.range(0, results.size())
                .mapToObj(index -> {
                    CodeSearchResult result = results.get(index);
                    return "[" + (index + 1) + "] " + result.filePath()
                            + ":" + result.lineStart() + "-" + result.lineEnd()
                            + nullable(" / " + result.chunkType() + " ", result.symbolName());
                })
                .collect(Collectors.joining("\n"));
        return "LLM 답변을 생성하지 못해 검색된 코드 근거 중심으로 반환합니다.\n\n" + sources;
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

    private List<String> diagnostics(List<CodeSearchResult> results, boolean llmUnavailable, boolean answerRewritten) {
        long distinctFiles = results.stream().map(CodeSearchResult::filePath).distinct().count();
        List<String> notes = new ArrayList<>();
        notes.add("검색된 코드 근거 " + results.size() + "개, 파일 " + distinctFiles + "개를 기반으로 답변했습니다.");
        if (llmUnavailable) {
            notes.add("LLM 호출이 실패해 자연어 종합 대신 근거 목록 중심의 fallback 답변을 반환했습니다.");
        }
        if (answerRewritten) {
            notes.add("LLM 응답이 너무 짧거나 인용이 부족해, 검색 근거 기반 자연어 요약으로 대체했습니다.");
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
        return questionMode == CodeQuestionMode.OVERVIEW && !trimmed.matches("(?s).*\\[\\d+].*");
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

    private String nullable(String prefix, String value) {
        return value == null || value.isBlank() ? "" : prefix + value;
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
