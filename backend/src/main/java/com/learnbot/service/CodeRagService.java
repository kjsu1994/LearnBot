package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeAskResponse;
import com.learnbot.dto.CodeEvidence;
import com.learnbot.dto.CodeSearchResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class CodeRagService {
    private final CodeSearchService searchService;
    private final OllamaClient ollamaClient;
    private final LearnBotProperties properties;

    public CodeRagService(CodeSearchService searchService, OllamaClient ollamaClient, LearnBotProperties properties) {
        this.searchService = searchService;
        this.ollamaClient = ollamaClient;
        this.properties = properties;
    }

    public CodeAskResponse ask(UUID repositoryId, String question, String mode, Integer limit) {
        CodeQuestionMode questionMode = CodeQuestionMode.from(mode);
        int safeLimit = limit == null ? properties.getCode().getTopK() : Math.max(1, Math.min(limit, 20));
        List<CodeSearchResult> results = searchService.search(repositoryId, question, safeLimit);
        String systemPrompt = """
                You are LearnBot Code, a private source-code RAG assistant inspired by Sourcegraph Cody.
                Answer in Korean unless the user asks for another language.
                Use only the provided source-code context.
                Do not invent files, classes, methods, or behavior not shown in the context.
                Always cite evidence with bracket numbers like [1].
                Mention file path and line range when explaining code.
                If evidence is insufficient, say what is missing and list the closest files found.
                """ + "\n" + questionMode.instruction();

        String userPrompt = "Question:\n" + question + "\n\nSource-code context:\n" + buildContext(results);
        String answer;
        try {
            answer = ollamaClient.chat(systemPrompt, userPrompt);
        } catch (RuntimeException ex) {
            answer = fallbackAnswer(results);
        }
        return new CodeAskResponse(questionMode.value(), answer, buildEvidence(results));
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
            return "LLM 응답을 생성하지 못했고, 관련 코드 근거도 찾지 못했습니다.";
        }
        String sources = IntStream.range(0, results.size())
                .mapToObj(index -> {
                    CodeSearchResult result = results.get(index);
                    return "[" + (index + 1) + "] " + result.filePath()
                            + ":" + result.lineStart() + "-" + result.lineEnd()
                            + nullable(" / " + result.chunkType() + " ", result.symbolName());
                })
                .collect(Collectors.joining("\n"));
        return "LLM 응답을 생성하지 못해 검색된 코드 근거만 반환합니다.\n\n" + sources;
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

    private String nullable(String prefix, String value) {
        return value == null || value.isBlank() ? "" : prefix + value;
    }

    private enum CodeQuestionMode {
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
