package com.learnbot.service;

import com.learnbot.dto.DocumentChunkDetail;
import com.learnbot.dto.DocumentPreviewBlock;
import com.learnbot.dto.DocumentPreviewResponse;
import com.learnbot.dto.DocumentPreviewSheet;
import com.learnbot.dto.DocumentPreviewTable;
import com.learnbot.dto.DocumentSummary;
import com.learnbot.repository.DocumentRepository;
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
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentPreviewService {
    private static final int MAX_TEXT_CHARS = 200_000;
    private static final int MAX_TABLE_ROWS = 500;
    private static final int MAX_COLUMNS = 80;

    private final DocumentRepository repository;
    private final ObjectStorageService objectStorageService;
    private final AuthService authService;

    public DocumentPreviewService(
            DocumentRepository repository,
            ObjectStorageService objectStorageService,
            AuthService authService
    ) {
        this.repository = repository;
        this.objectStorageService = objectStorageService;
        this.authService = authService;
    }

    public DocumentPreviewResponse preview(AppUser user, UUID documentId) {
        DocumentSummary summary = findDocument(user, documentId);
        if ("WEB".equalsIgnoreCase(summary.sourceType())) {
            return web(summary);
        }

        StoredObject object = repository.findSourceObject(summary.sourceId()).orElse(null);
        if (object == null) {
            return fromChunks(summary, previewType(summary.title(), summary.contentType()), false);
        }

        StoredFile file = objectStorageService.load(object);
        String type = previewType(file.filename(), summary.contentType());
        return switch (type) {
            case "pdf" -> base(summary, type, file.filename(), object.sizeBytes(), true, false, null, List.of(), List.of(), List.of(), List.of());
            case "docx" -> docx(summary, file, object);
            case "excel" -> excel(summary, file, object);
            case "csv" -> csv(summary, file, object);
            case "markdown", "text" -> text(summary, file, object, type);
            case "pptx" -> fromChunks(summary, type, true);
            default -> fromChunks(summary, "text", true);
        };
    }

    public StoredFile original(AppUser user, UUID documentId) {
        DocumentSummary summary = findDocument(user, documentId);
        StoredObject object = repository.findSourceObject(summary.sourceId())
                .orElseThrow(() -> new IllegalArgumentException("Original file is not available for this document."));
        return objectStorageService.load(object);
    }

    private DocumentSummary findDocument(AppUser user, UUID documentId) {
        return repository.findDocument(documentId, authService.accessibleSpaceIds(user))
                .orElseThrow(() -> new IllegalArgumentException("Document was not found."));
    }

    private DocumentPreviewResponse text(DocumentSummary summary, StoredFile file, StoredObject object, String previewType) {
        String raw = new String(file.content(), StandardCharsets.UTF_8);
        LimitedText limited = limitText(raw);
        return base(summary, previewType, file.filename(), object.sizeBytes(), true, limited.truncated(),
                limited.text(), List.of(), List.of(), List.of(), List.of());
    }

    private DocumentPreviewResponse csv(DocumentSummary summary, StoredFile file, StoredObject object) {
        List<List<String>> rows = new ArrayList<>();
        boolean truncated = false;
        try (InputStreamReader reader = new InputStreamReader(new ByteArrayInputStream(file.content()), StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.parse(reader)) {
            for (CSVRecord record : parser) {
                if (rows.size() >= MAX_TABLE_ROWS) {
                    truncated = true;
                    break;
                }
                rows.add(csvRow(record));
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not preview CSV file: " + ex.getMessage(), ex);
        }
        List<DocumentPreviewTable> tables = List.of(new DocumentPreviewTable("CSV", rows));
        return base(summary, "csv", file.filename(), object.sizeBytes(), true, truncated, null, List.of(), tables, List.of(), List.of());
    }

    private List<String> csvRow(CSVRecord record) {
        List<String> row = new ArrayList<>();
        int columns = Math.min(record.size(), MAX_COLUMNS);
        for (int i = 0; i < columns; i++) {
            row.add(stripBom(record.get(i)));
        }
        return row;
    }

    private DocumentPreviewResponse excel(DocumentSummary summary, StoredFile file, StoredObject object) {
        List<DocumentPreviewSheet> sheets = new ArrayList<>();
        boolean truncated = false;
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(file.content()))) {
            for (Sheet sheet : workbook) {
                List<List<String>> rows = new ArrayList<>();
                for (Row row : sheet) {
                    if (rows.size() >= MAX_TABLE_ROWS) {
                        truncated = true;
                        break;
                    }
                    rows.add(excelRow(row, formatter));
                }
                sheets.add(new DocumentPreviewSheet(sheet.getSheetName(), rows));
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not preview Excel file: " + ex.getMessage(), ex);
        }
        return base(summary, "excel", file.filename(), object.sizeBytes(), true, truncated, null, List.of(), List.of(), sheets, List.of());
    }

    private List<String> excelRow(Row row, DataFormatter formatter) {
        List<String> values = new ArrayList<>();
        short lastCellNumber = row.getLastCellNum();
        if (lastCellNumber < 0) {
            return values;
        }
        int columns = Math.min(lastCellNumber, MAX_COLUMNS);
        for (int index = 0; index < columns; index++) {
            Cell cell = row.getCell(index);
            values.add(cell == null ? "" : formatter.formatCellValue(cell));
        }
        return values;
    }

    private DocumentPreviewResponse docx(DocumentSummary summary, StoredFile file, StoredObject object) {
        List<String> paragraphs = new ArrayList<>();
        List<DocumentPreviewTable> tables = new ArrayList<>();
        boolean truncated = false;
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(file.content()))) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText();
                if (text != null && !text.isBlank()) {
                    paragraphs.add(text.trim());
                }
            }

            int tableIndex = 1;
            for (XWPFTable table : document.getTables()) {
                List<List<String>> rows = new ArrayList<>();
                for (XWPFTableRow row : table.getRows()) {
                    if (rows.size() >= MAX_TABLE_ROWS) {
                        truncated = true;
                        break;
                    }
                    rows.add(docxRow(row));
                }
                tables.add(new DocumentPreviewTable("Table " + tableIndex++, rows));
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not preview DOCX file: " + ex.getMessage(), ex);
        }
        return base(summary, "docx", file.filename(), object.sizeBytes(), true, truncated, null, paragraphs, tables, List.of(), List.of());
    }

    private List<String> docxRow(XWPFTableRow row) {
        List<String> values = new ArrayList<>();
        int columnIndex = 0;
        for (XWPFTableCell cell : row.getTableCells()) {
            if (columnIndex++ >= MAX_COLUMNS) {
                break;
            }
            values.add(cell.getText() == null ? "" : cell.getText().replaceAll("\\s+", " ").trim());
        }
        return values;
    }

    private DocumentPreviewResponse web(DocumentSummary summary) {
        List<DocumentPreviewBlock> blocks = webPreviewBlocks(repository.documentMetadata(summary.id()));
        if (!blocks.isEmpty()) {
            LimitedText limited = limitText(webText(blocks));
            return base(summary, "web", null, null, false, limited.truncated(),
                    limited.text(), List.of(), List.of(), List.of(), blocks);
        }
        return fromChunks(summary, "web", false);
    }

    private DocumentPreviewResponse fromChunks(DocumentSummary summary, String previewType, boolean originalAvailable) {
        StringBuilder text = new StringBuilder();
        for (DocumentChunkDetail chunk : repository.listDocumentChunks(summary.id())) {
            if (isDocumentContext(chunk.metadata())) {
                continue;
            }
            if (text.length() > 0) {
                text.append("\n\n");
            }
            text.append(chunk.content());
            if (text.length() > MAX_TEXT_CHARS) {
                break;
            }
        }
        LimitedText limited = limitText(text.toString());
        return base(summary, previewType, null, null, originalAvailable, limited.truncated(),
                limited.text(), List.of(), List.of(), List.of(), List.of());
    }

    private boolean isDocumentContext(Map<String, Object> metadata) {
        Object value = metadata == null ? null : metadata.get("kind");
        return "document_context".equals(value == null ? "" : String.valueOf(value));
    }

    private DocumentPreviewResponse base(
            DocumentSummary summary,
            String previewType,
            String filename,
            Long sizeBytes,
            boolean originalAvailable,
            boolean truncated,
            String text,
            List<String> paragraphs,
            List<DocumentPreviewTable> tables,
            List<DocumentPreviewSheet> sheets,
            List<DocumentPreviewBlock> blocks
    ) {
        return new DocumentPreviewResponse(
                summary.id(),
                summary.title(),
                summary.sourceUri(),
                summary.sourceType(),
                summary.contentType(),
                previewType,
                filename,
                sizeBytes,
                originalAvailable,
                truncated,
                text,
                paragraphs,
                tables,
                sheets,
                blocks
        );
    }

    private List<DocumentPreviewBlock> webPreviewBlocks(Map<String, Object> metadata) {
        Object raw = metadata == null ? null : metadata.get("webPreviewBlocks");
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .map(this::webPreviewBlock)
                .filter(block -> block != null && block.type() != null && !block.type().isBlank())
                .toList();
    }

    private DocumentPreviewBlock webPreviewBlock(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        String type = stringValue(map.get("type"));
        String text = stringValue(map.get("text"));
        String href = stringValue(map.get("href"));
        Integer level = integerValue(map.get("level"));
        List<String> items = stringList(map.get("items"));
        List<List<String>> rows = rows(map.get("rows"));
        if (type.isBlank()) {
            return null;
        }
        return new DocumentPreviewBlock(type, level, text, items, rows, href);
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .map(this::stringValue)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private List<List<String>> rows(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(List.class::isInstance)
                .map(List.class::cast)
                .map(this::stringList)
                .filter(row -> !row.isEmpty())
                .toList();
    }

    private String webText(List<DocumentPreviewBlock> blocks) {
        StringBuilder builder = new StringBuilder();
        for (DocumentPreviewBlock block : blocks) {
            switch (block.type()) {
                case "list" -> {
                    for (String item : block.items()) {
                        appendWebText(builder, "- " + item);
                    }
                }
                case "table" -> {
                    for (List<String> row : block.rows()) {
                        appendWebText(builder, String.join(" | ", row));
                    }
                }
                default -> appendWebText(builder, block.text());
            }
        }
        return builder.toString();
    }

    private void appendWebText(StringBuilder builder, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append(text.trim());
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String previewType(String filename, String contentType) {
        String lowerName = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String lowerType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".pdf") || lowerType.contains("pdf")) {
            return "pdf";
        }
        if (lowerName.endsWith(".docx") || lowerType.contains("wordprocessingml")) {
            return "docx";
        }
        if (lowerName.endsWith(".pptx") || lowerType.contains("presentationml")) {
            return "pptx";
        }
        if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls") || lowerType.contains("spreadsheetml") || lowerType.contains("excel")) {
            return "excel";
        }
        if (lowerName.endsWith(".csv") || lowerType.contains("csv")) {
            return "csv";
        }
        if (lowerName.endsWith(".md") || lowerName.endsWith(".markdown") || lowerType.contains("markdown")) {
            return "markdown";
        }
        return "text";
    }

    private LimitedText limitText(String value) {
        if (value == null || value.length() <= MAX_TEXT_CHARS) {
            return new LimitedText(value == null ? "" : value, false);
        }
        return new LimitedText(value.substring(0, MAX_TEXT_CHARS), true);
    }

    private String stripBom(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.charAt(0) == '\uFEFF' ? value.substring(1) : value;
    }

    private record LimitedText(String text, boolean truncated) {
    }
}
