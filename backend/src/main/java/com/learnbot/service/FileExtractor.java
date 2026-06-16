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
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
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

        throw new IllegalArgumentException("Only CSV, XLS, and XLSX files are supported.");
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

    private String stripBom(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.charAt(0) == '\uFEFF' ? value.substring(1) : value;
    }
}
