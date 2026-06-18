package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeProjectContextBuilderTest {
    @Test
    void createsProjectStructureAndSummaryChunks() {
        LearnBotProperties properties = properties(false);
        CodeProjectContextBuilder builder = new CodeProjectContextBuilder(properties, null);

        List<ParsedCodeChunk> chunks = builder.build(repository(), List.of(
                context("backend/src/main/java/com/example/web/AuthController.java", "java", "public class AuthController {}", "class", "AuthController"),
                context("backend/src/main/java/com/example/service/AuthService.java", "java", "public class AuthService {}", "class", "AuthService"),
                context("frontend/src/App.jsx", "javascript", "export default function App() {}", "method", "App")
        ));

        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.chunkType()).isEqualTo("project_structure");
            assertThat(chunk.content()).contains("Project structure context", "backend", "frontend");
            assertThat(chunk.metadata()).containsEntry("kind", "project_context");
        });
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.chunkType()).isEqualTo("repository_summary");
            assertThat(chunk.content()).contains("Repository summary", "Main language signals");
        });
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.chunkType()).isEqualTo("file_summary");
            assertThat(chunk.metadata()).containsEntry("sourceFile", "backend/src/main/java/com/example/web/AuthController.java");
        });
    }

    @Test
    void masksSecretsInGeneratedContext() {
        LearnBotProperties properties = properties(false);
        CodeProjectContextBuilder builder = new CodeProjectContextBuilder(properties, null);

        List<ParsedCodeChunk> chunks = builder.build(repository(), List.of(
                context("backend/src/main/resources/application.yml", "yaml", "token=super-secret-value\npassword=hunter2", "yaml_block", "token=super-secret-value")
        ));

        assertThat(chunks)
                .extracting(ParsedCodeChunk::content)
                .allSatisfy(content -> assertThat(content).doesNotContain("super-secret-value", "hunter2"));
        assertThat(chunks)
                .extracting(ParsedCodeChunk::content)
                .anySatisfy(content -> assertThat(content).contains("[REDACTED]"));
    }

    @Test
    void fallsBackToDeterministicSummaryWhenLlmFails() {
        LearnBotProperties properties = properties(true);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        when(ollamaClient.chat(any(), any(), any())).thenThrow(new RuntimeException("model unavailable"));
        CodeProjectContextBuilder builder = new CodeProjectContextBuilder(properties, ollamaClient);

        List<ParsedCodeChunk> chunks = builder.build(repository(), List.of(
                context("backend/src/main/java/com/example/service/AuthService.java", "java", "public class AuthService {}", "class", "AuthService")
        ));

        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.chunkType()).isEqualTo("repository_summary");
            assertThat(chunk.content()).contains("Repository summary");
            assertThat(chunk.metadata()).containsEntry("llmAttempted", true);
            assertThat(chunk.metadata()).containsEntry("llmSucceeded", false);
        });
    }

    private LearnBotProperties properties(boolean llmEnabled) {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getCode().getContext().setLlmSummaryEnabled(llmEnabled);
        properties.getCode().getContext().setMaxLlmDirectorySummaries(2);
        return properties;
    }

    private CodeRepositoryRecord repository() {
        return new CodeRepositoryRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "learnbot",
                "GIT",
                "https://example.com/learnbot.git",
                null,
                "https://example.com/learnbot.git",
                "main",
                "NONE",
                "/tmp/learnbot",
                "INDEXED",
                "abc123"
        );
    }

    private CodeProjectContextBuilder.IndexedFileContext context(
            String path,
            String language,
            String content,
            String chunkType,
            String symbol
    ) {
        ParsedCodeChunk chunk = new ParsedCodeChunk(
                0,
                chunkType,
                symbol,
                symbol,
                null,
                null,
                null,
                null,
                1,
                1,
                content,
                Map.of("language", language)
        );
        return new CodeProjectContextBuilder.IndexedFileContext(path, language, content, List.of(chunk));
    }
}
