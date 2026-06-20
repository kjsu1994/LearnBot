package com.learnbot.service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextParagraph;
import org.apache.poi.hslf.usermodel.HSLFTextRun;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
public class FileExtractor {
    public ExtractedDocument extract(MultipartFile file) {
        String fileName = file.getOriginalFilename() == null ? "uploaded-file" : file.getOriginalFilename();
        try {
            return extract(fileName, file.getInputStream());
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not extract file: " + ex.getMessage(), ex);
        }
    }

    public ExtractedDocument extract(StoredFile file) {
        try {
            return extract(file.filename(), new ByteArrayInputStream(file.content()));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not extract file: " + ex.getMessage(), ex);
        }
    }

    public ExtractedDocument extract(String fileName, String sourceUri, String contentType, byte[] content, Map<String, Object> metadata) {
        try {
            ExtractedDocument extracted = extract(fileName, new ByteArrayInputStream(content));
            Map<String, Object> merged = new java.util.LinkedHashMap<>();
            if (extracted.metadata() != null) {
                merged.putAll(extracted.metadata());
            }
            if (metadata != null) {
                merged.putAll(metadata);
            }
            return new ExtractedDocument(
                    extracted.title(),
                    sourceUri == null || sourceUri.isBlank() ? extracted.sourceUri() : sourceUri,
                    contentType == null || contentType.isBlank() ? extracted.contentType() : contentType,
                    extracted.content(),
                    merged
            );
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not extract file: " + ex.getMessage(), ex);
        }
    }

    private ExtractedDocument extract(String fileName, InputStream inputStream) throws Exception {
        String lower = fileName.toLowerCase();

        if (lower.endsWith(".csv")) {
            return csv(inputStream, fileName);
        }
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return excel(inputStream, fileName);
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt")) {
            return text(inputStream, fileName, lower.endsWith(".txt") ? "text/plain" : "text/markdown");
        }
        if (lower.endsWith(".pdf")) {
            return pdf(inputStream, fileName);
        }
        if (lower.endsWith(".docx")) {
            return docx(inputStream, fileName);
        }
        if (lower.endsWith(".ppt")) {
            return ppt(inputStream, fileName);
        }
        if (lower.endsWith(".pptx")) {
            return pptx(inputStream, fileName);
        }

        return genericText(inputStream, fileName);
    }

    private ExtractedDocument csv(InputStream inputStream, String fileName) throws Exception {
        StringBuilder content = new StringBuilder();
        try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.parse(reader)) {
            for (CSVRecord record : parser) {
                content.append("Row ").append(record.getRecordNumber()).append(": ");
                for (int i = 0; i < record.size(); i++) {
                    if (i > 0) {
                        content.append(" | ");
                    }
                    content.append("C").append(i + 1).append("=").append(stripBom(record.get(i)));
                }
                content.append('\n');
            }
        }

        return new ExtractedDocument(
                fileName,
                "file://" + fileName,
                "text/csv",
                content.toString(),
                Map.of("fileName", fileName)
        );
    }

    private ExtractedDocument excel(InputStream inputStream, String fileName) throws Exception {
        StringBuilder content = new StringBuilder();
        DataFormatter formatter = new DataFormatter();

        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            for (Sheet sheet : workbook) {
                for (Row row : sheet) {
                    content.append("Sheet ").append(sheet.getSheetName())
                            .append(" Row ").append(row.getRowNum() + 1)
                            .append(": ");
                    boolean first = true;
                    for (Cell cell : row) {
                        if (!first) {
                            content.append(" | ");
                        }
                        first = false;
                        content.append("C").append(cell.getColumnIndex() + 1)
                                .append("=")
                                .append(formatter.formatCellValue(cell));
                    }
                    content.append('\n');
                }
            }
        }

        return new ExtractedDocument(
                fileName,
                "file://" + fileName,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                content.toString(),
                Map.of("fileName", fileName)
        );
    }

    private ExtractedDocument text(InputStream inputStream, String fileName, String contentType) throws Exception {
        String content = decodeUtf8(inputStream.readAllBytes());
        return new ExtractedDocument(
                fileName,
                "file://" + fileName,
                contentType,
                content,
                Map.of("fileName", fileName)
        );
    }

    private ExtractedDocument genericText(InputStream inputStream, String fileName) throws Exception {
        byte[] bytes = inputStream.readAllBytes();
        if (looksBinary(bytes)) {
            throw new IllegalArgumentException("Unsupported binary file. Supported structured files: CSV, XLS, XLSX, PDF, DOCX, PPT, PPTX, Markdown, and TXT.");
        }
        String content = decodeUtf8(bytes);
        if (content.isBlank()) {
            throw new IllegalArgumentException("No extractable text was found in this file.");
        }
        return new ExtractedDocument(
                fileName,
                "file://" + fileName,
                "text/plain",
                content,
                Map.of("fileName", fileName, "fallbackExtractor", "plain-text")
        );
    }

    private ExtractedDocument pdf(InputStream inputStream, String fileName) throws Exception {
        byte[] bytes = inputStream.readAllBytes();
        StringBuilder content = new StringBuilder();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int pageCount = document.getNumberOfPages();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document);
                if (pageText != null && !pageText.isBlank()) {
                    content.append("Page ").append(page).append(":\n")
                            .append(pageText.trim())
                            .append("\n\n");
                }
            }
            if (content.isEmpty()) {
                content.append(new PDFTextStripper().getText(document));
            }
        }
        return new ExtractedDocument(
                fileName,
                "file://" + fileName,
                "application/pdf",
                content.toString(),
                Map.of("fileName", fileName, "pageSource", "pdfbox")
        );
    }

    private ExtractedDocument docx(InputStream inputStream, String fileName) throws Exception {
        StringBuilder content = new StringBuilder();
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText();
                if (text != null && !text.isBlank()) {
                    content.append(text).append('\n');
                }
            }
            int tableIndex = 1;
            for (XWPFTable table : document.getTables()) {
                content.append("Table ").append(tableIndex++).append(":\n");
                int rowIndex = 1;
                for (XWPFTableRow row : table.getRows()) {
                    content.append("Row ").append(rowIndex++).append(": ");
                    boolean first = true;
                    int columnIndex = 1;
                    for (XWPFTableCell cell : row.getTableCells()) {
                        if (!first) {
                            content.append(" | ");
                        }
                        first = false;
                        content.append("C").append(columnIndex++).append("=")
                                .append(cell.getText() == null ? "" : cell.getText().replaceAll("\\s+", " ").trim());
                    }
                    content.append('\n');
                }
            }
        }
        return new ExtractedDocument(
                fileName,
                "file://" + fileName,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                content.toString(),
                Map.of("fileName", fileName)
        );
    }

    private ExtractedDocument pptx(InputStream inputStream, String fileName) throws Exception {
        StringBuilder content = new StringBuilder();
        try (XMLSlideShow slideShow = new XMLSlideShow(inputStream)) {
            int slideIndex = 1;
            for (XSLFSlide slide : slideShow.getSlides()) {
                content.append("Slide ").append(slideIndex++).append(":\n");
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text != null && !text.isBlank()) {
                            content.append(text.replaceAll("\\s+", " ").trim()).append('\n');
                        }
                    }
                }
                content.append('\n');
            }
        }
        return new ExtractedDocument(
                fileName,
                "file://" + fileName,
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                content.toString(),
                Map.of("fileName", fileName, "slideSource", "poi")
        );
    }

    private ExtractedDocument ppt(InputStream inputStream, String fileName) throws Exception {
        StringBuilder content = new StringBuilder();
        try (HSLFSlideShow slideShow = new HSLFSlideShow(inputStream)) {
            int slideIndex = 1;
            for (var slide : slideShow.getSlides()) {
                content.append("Slide ").append(slideIndex++).append(":\n");
                for (List<HSLFTextParagraph> paragraphs : slide.getTextParagraphs()) {
                    for (HSLFTextParagraph paragraph : paragraphs) {
                        for (HSLFTextRun run : paragraph.getTextRuns()) {
                            String text = run.getRawText();
                            if (text != null && !text.isBlank()) {
                                content.append(text.replaceAll("\\s+", " ").trim()).append(' ');
                            }
                        }
                        content.append('\n');
                    }
                }
                content.append('\n');
            }
        }
        return new ExtractedDocument(
                fileName,
                "file://" + fileName,
                "application/vnd.ms-powerpoint",
                content.toString(),
                Map.of("fileName", fileName, "slideSource", "poi-hslf")
        );
    }

    private String stripBom(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.charAt(0) == '\uFEFF' ? value.substring(1) : value;
    }

    private String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    private boolean looksBinary(byte[] bytes) {
        int sample = Math.min(bytes.length, 2048);
        for (int i = 0; i < sample; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }
}
