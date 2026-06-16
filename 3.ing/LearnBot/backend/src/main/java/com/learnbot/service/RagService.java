package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
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

    public AskResponse ask(String question, SearchFilter filter) {
        List<SearchResult> citations = searchService.search(question, filter, properties.getRag().getTopK());
        String context = buildContext(citations);

        String systemPrompt = """
                You are LearnBot, a private RAG assistant.
                Answer in Korean unless the user asks for another language.
                Use only the provided context.
                If the context is insufficient, say that you do not know.
                Include bracketed citation numbers such as [1] when using context.
                Do not invent sources.
                """;

        String userPrompt = "Question:\n" + question + "\n\nContext:\n" + context;
        String answer;
        try {
            answer = ollamaClient.chat(systemPrompt, userPrompt);
        } catch (RuntimeException ex) {
            answer = fallbackAnswer(citations);
        }
        return new AskResponse(answer, citations);
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
}
