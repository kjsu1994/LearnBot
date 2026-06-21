package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentContextBuilderTest {
    @Test
    void createsDocumentStructureAndSummaryChunks() {
        LearnBotProperties properties = properties(false);
        DocumentContextBuilder builder = new DocumentContextBuilder(properties, null);

        List<Chunk> chunks = builder.buildDocumentContext(document("guide.md", "text/markdown"), List.of(
                chunk(0, "# Intro\nWelcome", Map.of("strategy", "markdown_heading", "blockType", "section", "headingPath", "Intro")),
                chunk(1, "## Setup\nInstall steps", Map.of("strategy", "markdown_heading", "blockType", "section", "headingPath", "Intro > Setup"))
        ));

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(3);
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.content()).contains("Document structure context", "Headings", "Intro > Setup");
            assertThat(chunk.metadata()).containsEntry("kind", "document_context");
            assertThat(chunk.metadata()).containsEntry("contextType", "document_structure");
            assertThat(chunk.metadata()).containsEntry("generatedBy", "deterministic");
        });
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.content()).contains("Document summary", "guide.md");
            assertThat(chunk.metadata()).containsEntry("contextType", "document_summary");
        });
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.content()).contains("Section summary", "Intro");
            assertThat(chunk.metadata()).containsEntry("contextType", "section_summary");
            assertThat(chunk.metadata()).containsEntry("sectionTitle", "Intro");
        });
    }

    @Test
    void summarizesSpreadsheetStructureWithoutCopyingRowsAsCountEvidence() {
        LearnBotProperties properties = properties(false);
        DocumentContextBuilder builder = new DocumentContextBuilder(properties, null);

        List<Chunk> chunks = builder.buildDocumentContext(document("people.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"), List.of(
                chunk(0, "Sheet Members Row 1: C1=name | C2=dept\nSheet Members Row 2: C1=Kim | C2=Ops", Map.of(
                        "strategy", "table_rows",
                        "blockType", "table",
                        "sheetName", "Members",
                        "rowStart", 1,
                        "rowEnd", 2,
                        "header", "Sheet Members Row 1: C1=name | C2=dept"
                ))
        ));

        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.metadata()).containsEntry("contextType", "document_structure");
            assertThat(chunk.content()).contains("Sheets and row ranges: Members rows 1-2");
        });
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.metadata()).containsEntry("contextType", "table_summary");
            assertThat(chunk.metadata()).containsEntry("tableId", "sheet:Members");
            assertThat(chunk.content()).contains("Table summary", "Rows: 1-2");
        });
    }

    @Test
    void createsSourceContextForMultipleDocuments() {
        LearnBotProperties properties = properties(false);
        DocumentContextBuilder builder = new DocumentContextBuilder(properties, null);

        List<Chunk> chunks = builder.buildSourceContext(List.of(
                new DocumentContextBuilder.DocumentContextInput(document("page-a", "text/html"), List.of(
                        chunk(0, "A", Map.of("strategy", "html_blocks", "headingPath", "A"))
                )),
                new DocumentContextBuilder.DocumentContextInput(document("page-b", "text/html"), List.of(
                        chunk(0, "B", Map.of("strategy", "html_blocks", "headingPath", "B"))
                ))
        ));

        assertThat(chunks).hasSize(2);
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.content()).contains("Source structure context", "page-a", "page-b");
            assertThat(chunk.metadata()).containsEntry("contextType", "source_structure");
            assertThat(chunk.metadata()).containsEntry("summaryLevel", "source");
        });
        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.metadata()).containsEntry("contextType", "source_summary"));
    }

    @Test
    void fallsBackToDeterministicSummaryWhenLlmFails() {
        LearnBotProperties properties = properties(true);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        when(ollamaClient.chat(any(), any(), any())).thenThrow(new RuntimeException("model unavailable"));
        DocumentContextBuilder builder = new DocumentContextBuilder(properties, ollamaClient);

        List<Chunk> chunks = builder.buildDocumentContext(document("guide.md", "text/markdown"), List.of(
                chunk(0, "# Intro\nWelcome", Map.of("strategy", "markdown_heading", "headingPath", "Intro"))
        ));

        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.metadata()).containsEntry("contextType", "document_summary");
            assertThat(chunk.metadata()).containsEntry("generatedBy", "deterministic");
            assertThat(chunk.metadata()).containsEntry("llmAttempted", true);
            assertThat(chunk.metadata()).containsEntry("llmSucceeded", false);
            assertThat(chunk.content()).contains("Document summary");
        });
    }

    @Test
    void usesMapReduceSummaryWhenEnabled() {
        LearnBotProperties properties = properties(true);
        properties.getRag().getDocumentContext().setMapWindowChunks(1);
        properties.getRag().getDocumentContext().setMaxMapWindowsPerDocument(2);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        when(ollamaClient.chat(any(), any(), any()))
                .thenReturn("Window summary one with setup details.")
                .thenReturn("Window summary two with usage details.")
                .thenReturn("Reduced document summary with setup and usage details.");
        DocumentContextBuilder builder = new DocumentContextBuilder(properties, ollamaClient);

        List<Chunk> chunks = builder.buildDocumentContext(document("guide.md", "text/markdown"), List.of(
                chunk(0, "# Setup\nInstall the service", Map.of("strategy", "markdown_heading", "headingPath", "Setup")),
                chunk(1, "# Usage\nRun the service", Map.of("strategy", "markdown_heading", "headingPath", "Usage"))
        ));

        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.metadata()).containsEntry("contextType", "document_summary");
            assertThat(chunk.metadata()).containsEntry("generatedBy", "llm_auxiliary_map_reduce");
            assertThat(chunk.metadata()).containsEntry("llmSucceeded", true);
            assertThat(chunk.content()).contains("Reduced document summary");
        });
    }

    @Test
    void returnsNoChunksWhenDisabled() {
        LearnBotProperties properties = properties(false);
        properties.getRag().getDocumentContext().setEnabled(false);
        DocumentContextBuilder builder = new DocumentContextBuilder(properties, null);

        List<Chunk> chunks = builder.buildDocumentContext(document("guide.md", "text/markdown"), List.of(
                chunk(0, "content", Map.of("strategy", "char_window"))
        ));

        assertThat(chunks).isEmpty();
    }

    @Test
    void recursiveWebUsesDeterministicSummaryByDefault() {
        LearnBotProperties properties = properties(true);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        DocumentContextBuilder builder = new DocumentContextBuilder(properties, ollamaClient);

        List<Chunk> chunks = builder.buildDocumentContext(document("guide.html", "text/html"), List.of(
                chunk(0, "Install and operate the service.", Map.of("strategy", "html_blocks", "headingPath", "Install"))
        ), true);

        verify(ollamaClient, never()).chat(any(), any(), any());
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.metadata()).containsEntry("contextType", "document_summary");
            assertThat(chunk.metadata()).containsEntry("generatedBy", "deterministic");
            assertThat(chunk.metadata()).containsEntry("llmAttempted", false);
        });
    }

    private LearnBotProperties properties(boolean llmEnabled) {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getRag().getDocumentContext().setLlmSummaryEnabled(llmEnabled);
        return properties;
    }

    private ExtractedDocument document(String title, String contentType) {
        return new ExtractedDocument(title, "https://example.test/" + title, contentType, "content", Map.of());
    }

    private Chunk chunk(int index, String content, Map<String, Object> metadata) {
        return new Chunk(index, content, metadata);
    }
}
