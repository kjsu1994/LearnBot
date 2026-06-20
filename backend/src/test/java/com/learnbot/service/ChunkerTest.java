package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkerTest {
    @Test
    void splitCreatesOverlappingChunks() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getChunking().setSize(300);
        properties.getChunking().setOverlap(30);
        Chunker chunker = new Chunker(properties);

        String content = "a".repeat(800);
        List<Chunk> chunks = chunker.split(content);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(0).index()).isZero();
    }

    @Test
    void splitRemovesNullCharactersBeforeStorage() {
        LearnBotProperties properties = new LearnBotProperties();
        properties.getChunking().setSize(300);
        properties.getChunking().setOverlap(30);
        Chunker chunker = new Chunker(properties);

        List<Chunk> chunks = chunker.split("before\u0000after");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).doesNotContain("\u0000");
        assertThat(chunks.get(0).content()).isEqualTo("before after");
    }

    @Test
    void splitPdfKeepsPageMetadata() {
        Chunker chunker = new Chunker(new LearnBotProperties());
        ExtractedDocument document = new ExtractedDocument(
                "manual.pdf",
                "file://manual.pdf",
                "application/pdf",
                "Page 1:\nInstall the app.\n\nPage 2:\nRun the app.",
                Map.of("fileName", "manual.pdf")
        );

        List<Chunk> chunks = chunker.split(document);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).contains("Page 1:");
        assertThat(chunks.get(0).metadata()).containsEntry("strategy", "pdf_page");
        assertThat(chunks.get(0).metadata()).containsEntry("pageStart", 1);
    }

    @Test
    void splitSpreadsheetRepeatsHeaderAcrossRowGroups() {
        Chunker chunker = new Chunker(new LearnBotProperties());
        StringBuilder content = new StringBuilder("Row 1: C1=name | C2=role\n");
        for (int i = 2; i <= 31; i++) {
            content.append("Row ").append(i).append(": C1=user").append(i).append(" | C2=admin\n");
        }
        ExtractedDocument document = new ExtractedDocument(
                "users.csv",
                "file://users.csv",
                "text/csv",
                content.toString(),
                Map.of("fileName", "users.csv")
        );

        List<Chunk> chunks = chunker.split(document);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(1).content()).startsWith("Row 1: C1=name");
        assertThat(chunks.get(1).metadata()).containsEntry("strategy", "table_rows");
    }

    @Test
    void splitPptxKeepsSlideMetadata() {
        Chunker chunker = new Chunker(new LearnBotProperties());
        ExtractedDocument document = new ExtractedDocument(
                "deck.pptx",
                "file://deck.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "Slide 1:\nArchitecture overview\n\nSlide 2:\nFallback strategy",
                Map.of("fileName", "deck.pptx")
        );

        List<Chunk> chunks = chunker.split(document);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).metadata()).containsEntry("strategy", "pptx_slide");
        assertThat(chunks.get(1).metadata()).containsEntry("slideStart", 2);
    }

    @Test
    void splitMarkdownKeepsHeadingPathMetadata() {
        Chunker chunker = new Chunker(new LearnBotProperties());
        ExtractedDocument document = new ExtractedDocument(
                "guide.md",
                "file://guide.md",
                "text/markdown",
                "# Install\nRun setup.\n\n## Configure\nSet options.",
                Map.of("fileName", "guide.md")
        );

        List<Chunk> chunks = chunker.split(document);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).metadata()).containsEntry("strategy", "markdown_heading");
        assertThat(chunks.get(1).metadata()).containsEntry("headingPath", "Install > Configure");
    }

    @Test
    void splitHtmlUsesPreviewBlocksWhenAvailable() {
        Chunker chunker = new Chunker(new LearnBotProperties());
        ExtractedDocument document = new ExtractedDocument(
                "page.html",
                "https://example.test/page.html",
                "text/html",
                "fallback content",
                Map.of("webPreviewBlocks", List.of(
                        Map.of("type", "heading", "level", 1, "text", "Intro"),
                        Map.of("type", "paragraph", "text", "Welcome to the guide.")
                ))
        );

        List<Chunk> chunks = chunker.split(document);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).contains("Intro", "Welcome");
        assertThat(chunks.get(0).metadata()).containsEntry("strategy", "html_blocks");
        assertThat(chunks.get(0).metadata()).containsEntry("headingPath", "Intro");
    }

    @Test
    void splitDocxSeparatesTablesFromParagraphs() {
        Chunker chunker = new Chunker(new LearnBotProperties());
        ExtractedDocument document = new ExtractedDocument(
                "guide.docx",
                "file://guide.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "Intro paragraph.\n\nTable 1:\nRow 1: C1=name | C2=role\nRow 2: C1=Kim | C2=admin",
                Map.of("fileName", "guide.docx")
        );

        List<Chunk> chunks = chunker.split(document);

        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.metadata()).containsEntry("strategy", "docx_paragraph"));
        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.metadata()).containsEntry("strategy", "docx_table"));
    }
}
