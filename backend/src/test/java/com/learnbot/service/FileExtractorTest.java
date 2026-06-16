package com.learnbot.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileExtractorTest {
    private final FileExtractor extractor = new FileExtractor();

    @Test
    void extractsMarkdownContent() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "wiki.md",
                "text/markdown",
                "# Wiki\n\nRAG source evidence".getBytes(StandardCharsets.UTF_8)
        );

        ExtractedDocument document = extractor.extract(file);

        assertThat(document.contentType()).isEqualTo("text/markdown");
        assertThat(document.content()).contains("RAG source evidence");
    }

    @Test
    void extractsDocxParagraphs() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "policy.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docxBytes("Document RAG policy")
        );

        ExtractedDocument document = extractor.extract(file);

        assertThat(document.contentType()).isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(document.content()).contains("Document RAG policy");
    }

    @Test
    void extractsPdfText() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "manual.pdf",
                "application/pdf",
                pdfBytes("PDF RAG evidence")
        );

        ExtractedDocument document = extractor.extract(file);

        assertThat(document.contentType()).isEqualTo("application/pdf");
        assertThat(document.content()).contains("PDF RAG evidence");
    }

    @Test
    void rejectsMalformedUtf8Text() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "broken.txt",
                "text/plain",
                new byte[]{(byte) 0xC3, 0x28}
        );

        assertThatThrownBy(() -> extractor.extract(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Could not extract file");
    }

    private byte[] docxBytes(String text) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(text);
            document.write(output);
            return output.toByteArray();
        }
    }

    private byte[] pdfBytes(String text) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
