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
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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

        throw new IllegalArgumentException("Supported files: CSV, XLS, XLSX, PDF, DOCX, Markdown, and TXT.");
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
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        String content = decoder.decode(ByteBuffer.wrap(inputStream.readAllBytes())).toString();
        return new ExtractedDocument(
                fileName,
                "file://" + fileName,
                contentType,
                content,
                Map.of("fileName", fileName)
        );
    }

    private ExtractedDocument pdf(InputStream inputStream, String fileName) throws Exception {
        byte[] bytes = inputStream.readAllBytes();
        String content;
        try (PDDocument document = Loader.loadPDF(bytes)) {
            content = new PDFTextStripper().getText(document);
        }
        return new ExtractedDocument(
                fileName,
                "file://" + fileName,
                "application/pdf",
                content,
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
        }
        return new ExtractedDocument(
                fileName,
                "file://" + fileName,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                content.toString(),
                Map.of("fileName", fileName)
        );
    }

    private String stripBom(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.charAt(0) == '\uFEFF' ? value.substring(1) : value;
    }
}
