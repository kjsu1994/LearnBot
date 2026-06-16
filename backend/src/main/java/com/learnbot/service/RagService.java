package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.AnswerEvidence;
import com.learnbot.dto.AskResponse;
import com.learnbot.dto.SearchFilter;
import com.learnbot.dto.SearchResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RagService {
    private final SearchService searchService;
    private final OllamaClient ollamaClient;
    private final LearnBotProperties properties;

    public RagService(SearchService searchService, OllamaClient ollamaClient, LearnBotProperties properties) {
        this.searchService = searchService;
        this.ollamaClient = ollamaClient;
        this.properties = properties;
    }

    public AskResponse ask(String question, SearchFilter filter, String mode) {
        AnswerMode answerMode = AnswerMode.from(mode);
        List<SearchResult> citations = searchService.search(question, filter, properties.getRag().getTopK());
        String context = buildContext(citations);

        String systemPrompt = """
                You are LearnBot, a private RAG assistant.
                Answer in Korean unless the user asks for another language.
                Use only the provided context.
                If the context is insufficient, say that you do not know.
                Include bracketed citation numbers such as [1] when using context.
                Do not invent sources.
                """ + "\n" + answerMode.instruction();

        String userPrompt = "Question:\n" + question + "\n\nContext:\n" + context;
        String answer;
        try {
            answer = ollamaClient.chat(systemPrompt, userPrompt);
        } catch (RuntimeException ex) {
            answer = fallbackAnswer(citations);
        }
        return new AskResponse(answerMode.value(), answer, citations, buildEvidence(citations));
    }

    private String buildContext(List<SearchResult> results) {
        if (results.isEmpty()) {
            return "No context retrieved.";
        }

        return results.stream()
                .map(result -> "[" + (results.indexOf(result) + 1) + "] "
                        + result.title() + " (" + result.sourceUri() + ")\n"
                        + result.content())
                .collect(Collectors.joining("\n\n"));
    }

    private String fallbackAnswer(List<SearchResult> results) {
        if (results.isEmpty()) {
            return "The chat LLM is unavailable, and no supporting context was found.";
        }

        String sources = results.stream()
                .map(result -> "[" + (results.indexOf(result) + 1) + "] " + result.title() + " - " + result.sourceUri())
                .collect(Collectors.joining("\n"));
        return "The chat LLM is unavailable, so only retrieved supporting context is returned.\n\n" + sources;
    }

    private List<AnswerEvidence> buildEvidence(List<SearchResult> results) {
        return java.util.stream.IntStream.range(0, results.size())
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

    private enum AnswerMode {
        QA("qa", "Answer the question directly. Keep the answer concise and cite each factual claim."),
        SUMMARY("summary", "Summarize the retrieved context into key points. Prefer bullets and cite important points."),
        TABLE("table", "Extract table-like facts from the context. If possible, answer with compact rows or field-value pairs."),
        QUOTE("quote", "Prefer short direct excerpts from the context, then explain what they mean. Cite every excerpt.");

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
