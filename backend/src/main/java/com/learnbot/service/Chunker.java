package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class Chunker {
    private static final int TARGET_CHARS = 1_400;
    private static final int HARD_MAX_CHARS = 2_400;
    private static final int TABLE_ROWS_PER_CHUNK = 25;
    private static final Pattern PDF_PAGE = Pattern.compile("(?m)^Page\\s+(\\d+):\\s*$");
    private static final Pattern SLIDE = Pattern.compile("(?m)^Slide\\s+(\\d+):\\s*$");
    private static final Pattern SHEET_ROW = Pattern.compile("^Sheet\\s+(.+?)\\s+Row\\s+(\\d+):\\s*(.*)$");
    private static final Pattern CSV_ROW = Pattern.compile("^Row\\s+(\\d+):\\s*(.*)$");
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern CLAUSE_HEADING = Pattern.compile(
            "(?m)^\\s*(제\\s*\\d+\\s*조(?:의\\s*\\d+)?(?:\\s*\\([^\\n)]{1,80}\\))?|제\\s*\\d+\\s*항|부칙|별표\\s*\\d*|\\d+[.)]\\s+[^\\n]{2,80}|[가-힣][.)]\\s+[^\\n]{2,80})(?:\\s+[^\\n]*)?\\s*$"
    );
    private static final Pattern ARTICLE_NUMBER = Pattern.compile("제\\s*\\d+\\s*조(?:의\\s*\\d+)?");
    private static final Pattern PARAGRAPH_NUMBER = Pattern.compile("제\\s*\\d+\\s*항");

    private final LearnBotProperties properties;

    public Chunker(LearnBotProperties properties) {
        this.properties = properties;
    }

    public List<Chunk> split(ExtractedDocument document) {
        if (document == null) {
            return List.of();
        }
        String contentType = lower(document.contentType());
        String sourceUri = lower(document.sourceUri());
        String title = lower(document.title());
        String content = document.content();
        List<Chunk> chunks;
        if (contentType.contains("pdf") || sourceUri.endsWith(".pdf") || title.endsWith(".pdf")) {
            chunks = splitPdf(content);
        } else if (contentType.contains("html") || "text/html".equals(contentType)) {
            chunks = splitHtml(document);
        } else if (contentType.contains("spreadsheet")
                || contentType.contains("excel")
                || contentType.contains("csv")
                || sourceUri.endsWith(".csv")
                || sourceUri.endsWith(".xlsx")
                || sourceUri.endsWith(".xls")) {
            chunks = splitRows(content);
        } else if (contentType.contains("wordprocessingml") || sourceUri.endsWith(".docx")) {
            chunks = splitDocx(content);
        } else if (contentType.contains("presentationml") || sourceUri.endsWith(".pptx")) {
            chunks = splitSlides(content);
        } else if (contentType.contains("markdown") || sourceUri.endsWith(".md") || sourceUri.endsWith(".markdown")) {
            chunks = splitMarkdown(content);
        } else {
            chunks = split(content);
        }
        return enrichDocumentMetadata(document, chunks);
    }

    public List<Chunk> split(String content) {
        String normalized = normalize(content);
        int size = properties.getChunking().getSize();
        int overlap = Math.min(properties.getChunking().getOverlap(), size - 1);
        List<Chunk> chunks = new ArrayList<>();

        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + size, normalized.length());
            if (end < normalized.length()) {
                int boundary = normalized.lastIndexOf('\n', end);
                if (boundary > start + size / 2) {
                    end = boundary;
                }
            }

            String chunk = normalized.substring(start, end).trim();
            if (!chunk.isBlank()) {
                chunks.add(new Chunk(chunks.size(), chunk, Map.of(
                        "strategy", "char_window",
                        "charStart", start,
                        "charEnd", end,
                        "start", start,
                        "end", end
                )));
            }

            if (end == normalized.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }

        return chunks;
    }

    private List<Chunk> splitSlides(String content) {
        List<PageBlock> slides = numberedBlocks(normalizePreservingLines(content), SLIDE);
        if (slides.isEmpty()) {
            return paragraphChunks(content, "slide_fallback", Map.of("blockType", "slide"));
        }
        List<Chunk> chunks = new ArrayList<>();
        for (PageBlock slide : slides) {
            Map<String, Object> metadata = baseMetadata("pptx_slide", "slide");
            metadata.put("slideStart", slide.page());
            metadata.put("slideEnd", slide.page());
            addChunk(chunks, "Slide " + slide.page() + ":\n" + slide.text(), metadata);
        }
        return chunks;
    }

    private List<Chunk> splitPdf(String content) {
        String normalized = normalizePreservingLines(content);
        Matcher matcher = PDF_PAGE.matcher(normalized);
        List<PageBlock> pages = new ArrayList<>();
        int lastPage = -1;
        int lastStart = -1;
        while (matcher.find()) {
            if (lastPage > 0) {
                pages.add(new PageBlock(lastPage, normalized.substring(lastStart, matcher.start()).trim()));
            }
            lastPage = Integer.parseInt(matcher.group(1));
            lastStart = matcher.end();
        }
        if (lastPage > 0) {
            pages.add(new PageBlock(lastPage, normalized.substring(lastStart).trim()));
        }
        if (pages.isEmpty()) {
            return paragraphChunks(normalized, "pdf_fallback", Map.of("blockType", "text"));
        }

        List<Chunk> chunks = new ArrayList<>();
        for (PageBlock page : pages) {
            List<String> parts = splitLongText(page.text(), TARGET_CHARS, HARD_MAX_CHARS);
            for (String part : parts) {
                Map<String, Object> metadata = baseMetadata("pdf_page", "page");
                metadata.put("pageStart", page.page());
                metadata.put("pageEnd", page.page());
                enrichClauseMetadata(metadata, part);
                addChunk(chunks, "Page " + page.page() + ":\n" + part, metadata);
            }
        }
        return chunks;
    }

    private List<Chunk> splitHtml(ExtractedDocument document) {
        Object blocks = document.metadata() == null ? null : document.metadata().get("webPreviewBlocks");
        if (!(blocks instanceof List<?> previewBlocks) || previewBlocks.isEmpty()) {
            return paragraphChunks(document.content(), "html_text", Map.of("blockType", "section"));
        }

        List<Chunk> chunks = new ArrayList<>();
        List<String> headingPath = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Map<String, Object> metadata = baseMetadata("html_blocks", "section");
        for (Object item : previewBlocks) {
            if (!(item instanceof Map<?, ?> block)) {
                continue;
            }
            Object rawType = block.get("type");
            String type = rawType == null ? "paragraph" : String.valueOf(rawType);
            String text = blockText(block);
            if (text.isBlank()) {
                continue;
            }
            if ("heading".equals(type)) {
                if (!current.isEmpty()) {
                    addChunk(chunks, current.toString(), metadata);
                    current.setLength(0);
                }
                int level = number(block.get("level"), 2);
                while (headingPath.size() >= level) {
                    headingPath.remove(headingPath.size() - 1);
                }
                headingPath.add(text);
                metadata = baseMetadata("html_blocks", "section");
                metadata.put("headingPath", String.join(" > ", headingPath));
                appendBlock(current, text);
                continue;
            }
            if (current.length() + text.length() > HARD_MAX_CHARS && !current.isEmpty()) {
                addChunk(chunks, current.toString(), metadata);
                current.setLength(0);
                if (metadata.containsKey("headingPath")) {
                    appendBlock(current, String.valueOf(metadata.get("headingPath")));
                }
            }
            metadata.put("blockType", type);
            appendBlock(current, text);
        }
        if (!current.isEmpty()) {
            addChunk(chunks, current.toString(), metadata);
        }
        return chunks.isEmpty() ? split(document.content()) : chunks;
    }

    private List<Chunk> splitRows(String content) {
        String[] lines = splitLines(content);
        List<Chunk> chunks = new ArrayList<>();
        String currentSheet = null;
        String header = null;
        int firstRow = -1;
        int lastRow = -1;
        int rows = 0;
        StringBuilder builder = new StringBuilder();

        for (String line : lines) {
            Matcher sheetMatcher = SHEET_ROW.matcher(line);
            Matcher csvMatcher = CSV_ROW.matcher(line);
            boolean isSheet = sheetMatcher.find();
            boolean isCsv = !isSheet && csvMatcher.find();
            if (!isSheet && !isCsv) {
                continue;
            }
            String sheet = isSheet ? sheetMatcher.group(1) : null;
            int rowNumber = Integer.parseInt(isSheet ? sheetMatcher.group(2) : csvMatcher.group(1));
            if (header == null || rowNumber == 1 || (sheet != null && !sheet.equals(currentSheet))) {
                header = line;
            }
            if (rows >= TABLE_ROWS_PER_CHUNK || (currentSheet != null && sheet != null && !sheet.equals(currentSheet))) {
                addRowChunk(chunks, builder, currentSheet, firstRow, lastRow, header);
                builder.setLength(0);
                rows = 0;
                firstRow = -1;
            }
            if (builder.isEmpty() && header != null && rowNumber != 1) {
                builder.append(header).append('\n');
            }
            builder.append(line).append('\n');
            currentSheet = sheet == null ? currentSheet : sheet;
            firstRow = firstRow < 0 ? rowNumber : firstRow;
            lastRow = rowNumber;
            rows++;
        }
        addRowChunk(chunks, builder, currentSheet, firstRow, lastRow, header);
        return chunks.isEmpty() ? split(content) : chunks;
    }

    private List<Chunk> splitDocx(String content) {
        String normalized = normalizePreservingLines(content);
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder prose = new StringBuilder();
        StringBuilder table = new StringBuilder();
        int tableIndex = 0;
        int rowStart = -1;
        int rowEnd = -1;
        for (String line : splitLines(normalized)) {
            if (line.matches("^Table\\s+\\d+:\\s*$")) {
                if (!prose.isEmpty()) {
                    chunks.addAll(paragraphChunks(prose.toString(), "docx_paragraph", Map.of("blockType", "paragraph")));
                    prose.setLength(0);
                }
                if (!table.isEmpty()) {
                    addDocxTableChunk(chunks, table, tableIndex, rowStart, rowEnd);
                    table.setLength(0);
                }
                tableIndex++;
                table.append(line).append('\n');
                rowStart = -1;
                rowEnd = -1;
                continue;
            }
            Matcher rowMatcher = CSV_ROW.matcher(line);
            if (tableIndex > 0 && rowMatcher.find()) {
                int row = Integer.parseInt(rowMatcher.group(1));
                if (rowStart < 0) {
                    rowStart = row;
                }
                rowEnd = row;
                if (rowEnd - rowStart + 1 > TABLE_ROWS_PER_CHUNK) {
                    addDocxTableChunk(chunks, table, tableIndex, rowStart, rowEnd - 1);
                    table.setLength(0);
                    rowStart = row;
                }
                table.append(line).append('\n');
            } else if (tableIndex > 0 && !table.isEmpty()) {
                table.append(line).append('\n');
            } else {
                prose.append(line).append('\n');
            }
        }
        if (!table.isEmpty()) {
            addDocxTableChunk(chunks, table, tableIndex, rowStart, rowEnd);
        }
        if (!prose.isEmpty()) {
            chunks.addAll(paragraphChunks(prose.toString(), "docx_paragraph", Map.of("blockType", "paragraph")));
        }
        return chunks.isEmpty() ? split(content) : chunks;
    }

    private List<Chunk> splitMarkdown(String content) {
        String[] lines = splitLines(normalizePreservingLines(content));
        List<Chunk> chunks = new ArrayList<>();
        List<String> headingPath = new ArrayList<>();
        StringBuilder builder = new StringBuilder();
        Map<String, Object> metadata = baseMetadata("markdown_heading", "section");
        for (String line : lines) {
            Matcher headingMatcher = MARKDOWN_HEADING.matcher(line);
            if (headingMatcher.find()) {
                if (!builder.isEmpty()) {
                    addMarkdownChunks(chunks, builder.toString(), metadata);
                    builder.setLength(0);
                }
                int level = headingMatcher.group(1).length();
                while (headingPath.size() >= level) {
                    headingPath.remove(headingPath.size() - 1);
                }
                headingPath.add(headingMatcher.group(2).trim());
                metadata = baseMetadata("markdown_heading", "section");
                metadata.put("headingPath", String.join(" > ", headingPath));
            }
            builder.append(line).append('\n');
        }
        if (!builder.isEmpty()) {
            addMarkdownChunks(chunks, builder.toString(), metadata);
        }
        return chunks.isEmpty() ? split(content) : chunks;
    }

    private List<Chunk> paragraphChunks(String content, String strategy, Map<String, Object> extraMetadata) {
        List<Chunk> chunks = new ArrayList<>();
        Map<String, Object> metadata = new LinkedHashMap<>(extraMetadata);
        metadata.put("strategy", strategy);
        for (String part : splitLongText(normalizePreservingLines(content), TARGET_CHARS, HARD_MAX_CHARS)) {
            addChunk(chunks, part, metadata);
        }
        return chunks;
    }

    private void addMarkdownChunks(List<Chunk> chunks, String text, Map<String, Object> metadata) {
        for (String part : splitLongText(text, TARGET_CHARS, HARD_MAX_CHARS)) {
            addChunk(chunks, part, metadata);
        }
    }

    private void addRowChunk(List<Chunk> chunks, StringBuilder builder, String sheetName, int rowStart, int rowEnd, String header) {
        if (builder.isEmpty()) {
            return;
        }
        Map<String, Object> metadata = baseMetadata("table_rows", "table");
        if (sheetName != null) {
            metadata.put("sheetName", sheetName);
        }
        metadata.put("rowStart", rowStart);
        metadata.put("rowEnd", rowEnd);
        if (header != null) {
            metadata.put("header", header);
        }
        addChunk(chunks, builder.toString(), metadata);
    }

    private void addDocxTableChunk(List<Chunk> chunks, StringBuilder table, int tableIndex, int rowStart, int rowEnd) {
        Map<String, Object> metadata = baseMetadata("docx_table", "table");
        metadata.put("tableIndex", tableIndex);
        metadata.put("rowStart", rowStart);
        metadata.put("rowEnd", rowEnd);
        addChunk(chunks, table.toString(), metadata);
    }

    private void addChunk(List<Chunk> chunks, String content, Map<String, Object> metadata) {
        String clean = normalizeChunk(content);
        if (clean.isBlank()) {
            return;
        }
        chunks.add(new Chunk(chunks.size(), clean, new LinkedHashMap<>(metadata)));
    }

    private List<Chunk> enrichDocumentMetadata(ExtractedDocument document, List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<Chunk> enriched = new ArrayList<>();
        for (Chunk chunk : chunks) {
            Map<String, Object> metadata = new LinkedHashMap<>(chunk.metadata() == null ? Map.of() : chunk.metadata());
            putIfNotBlank(metadata, "sourceUrl", document.sourceUri());
            String headingPath = string(metadata.get("headingPath"));
            if (!headingPath.isBlank()) {
                putIfNotBlank(metadata, "sectionTitle", lastHeading(headingPath));
            }
            Integer page = DocumentPageMetadata.canonicalPageNumber(metadata);
            if (page != null) {
                metadata.putIfAbsent("pageNumber", page);
            }
            String sheetName = string(metadata.get("sheetName"));
            if (!sheetName.isBlank()) {
                putIfNotBlank(metadata, "tableId", "sheet:" + sheetName);
            } else if (metadata.containsKey("tableIndex")) {
                putIfNotBlank(metadata, "tableId", "table:" + metadata.get("tableIndex"));
            } else if ("table".equals(string(metadata.get("blockType")))) {
                putIfNotBlank(metadata, "tableId", "table:rows");
            }
            enrichClauseMetadata(metadata, chunk.content());
            Integer row = sameNumber(metadata.get("rowStart"), metadata.get("rowEnd"));
            if (row != null) {
                metadata.putIfAbsent("rowNumber", row);
            }
            enriched.add(new Chunk(enriched.size(), chunk.content(), metadata));
        }
        return enriched;
    }

    private void putIfNotBlank(Map<String, Object> metadata, String key, Object value) {
        String text = string(value);
        if (!text.isBlank()) {
            metadata.putIfAbsent(key, text);
        }
    }

    private void enrichClauseMetadata(Map<String, Object> metadata, String content) {
        if (metadata == null || "table".equals(string(metadata.get("blockType")))) {
            return;
        }
        ClauseSignal signal = clauseSignal(content);
        if (signal == null) {
            return;
        }
        putIfNotBlank(metadata, "clauseNumber", signal.number());
        putIfNotBlank(metadata, "clauseLevel", signal.level());
        if (string(metadata.get("sectionTitle")).isBlank()) {
            putIfNotBlank(metadata, "sectionTitle", signal.title());
        }
        if (string(metadata.get("headingPath")).isBlank()) {
            putIfNotBlank(metadata, "headingPath", signal.title());
        }
    }

    private ClauseSignal clauseSignal(String content) {
        String text = normalizePreservingLines(content);
        if (text.isBlank()) {
            return null;
        }
        Matcher matcher = CLAUSE_HEADING.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String title = matcher.group(1).replaceAll("\\s+", " ").trim();
        if (title.isBlank()) {
            return null;
        }
        String number = firstMatch(ARTICLE_NUMBER, title);
        String level = "section";
        if (!number.isBlank()) {
            level = "article";
        } else {
            number = firstMatch(PARAGRAPH_NUMBER, title);
            if (!number.isBlank()) {
                level = "paragraph";
            } else if (title.startsWith("부칙")) {
                number = "부칙";
                level = "appendix";
            } else if (title.startsWith("별표")) {
                number = title.split("\\s+", 2)[0];
                level = "appendix";
            }
        }
        String displayTitle = title.replaceFirst("^\\s*" + ARTICLE_NUMBER.pattern() + "\\s*", "").trim();
        displayTitle = displayTitle.replaceFirst("^\\(([^)]{1,80})\\).*$", "$1").trim();
        if (displayTitle.isBlank()) {
            displayTitle = title;
        }
        return new ClauseSignal(number, level, displayTitle);
    }

    private String firstMatch(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group().replaceAll("\\s+", "") : "";
    }

    private String lastHeading(String headingPath) {
        String[] parts = headingPath.split("\\s*>\\s*");
        return parts.length == 0 ? headingPath : parts[parts.length - 1].trim();
    }

    private Integer sameNumber(Object start, Object end) {
        Integer first = integer(start);
        Integer second = integer(end);
        if (first == null && second == null) {
            return null;
        }
        if (first == null) {
            return second;
        }
        if (second == null || first.equals(second)) {
            return first;
        }
        return null;
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            String text = string(value);
            return text.isBlank() ? null : Integer.parseInt(text);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Map<String, Object> baseMetadata(String strategy, String blockType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("strategy", strategy);
        metadata.put("blockType", blockType);
        return metadata;
    }

    private List<String> splitLongText(String content, int targetChars, int hardMaxChars) {
        String clean = normalizePreservingLines(content);
        if (clean.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        StringBuilder builder = new StringBuilder();
        for (String paragraph : clean.split("\\n\\s*\\n")) {
            String part = paragraph.trim();
            if (part.isBlank()) {
                continue;
            }
            if (builder.length() + part.length() + 2 > targetChars && !builder.isEmpty()) {
                parts.add(builder.toString().trim());
                builder.setLength(0);
            }
            if (part.length() > hardMaxChars) {
                if (!builder.isEmpty()) {
                    parts.add(builder.toString().trim());
                    builder.setLength(0);
                }
                parts.addAll(splitHard(part, hardMaxChars));
            } else {
                appendBlock(builder, part);
            }
        }
        if (!builder.isEmpty()) {
            parts.add(builder.toString().trim());
        }
        return parts;
    }

    private List<String> splitHard(String value, int limit) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < value.length()) {
            int end = Math.min(start + limit, value.length());
            int boundary = value.lastIndexOf('\n', end);
            if (boundary <= start + limit / 2) {
                boundary = value.lastIndexOf(". ", end);
            }
            if (boundary > start + limit / 2) {
                end = boundary + 1;
            }
            parts.add(value.substring(start, end).trim());
            start = end;
        }
        return parts;
    }

    private List<PageBlock> numberedBlocks(String normalized, Pattern pattern) {
        Matcher matcher = pattern.matcher(normalized);
        List<PageBlock> blocks = new ArrayList<>();
        int lastNumber = -1;
        int lastStart = -1;
        while (matcher.find()) {
            if (lastNumber > 0) {
                blocks.add(new PageBlock(lastNumber, normalized.substring(lastStart, matcher.start()).trim()));
            }
            lastNumber = Integer.parseInt(matcher.group(1));
            lastStart = matcher.end();
        }
        if (lastNumber > 0) {
            blocks.add(new PageBlock(lastNumber, normalized.substring(lastStart).trim()));
        }
        return blocks;
    }

    private String blockText(Map<?, ?> block) {
        Object rawType = block.get("type");
        String type = rawType == null ? "" : String.valueOf(rawType);
        if ("list".equals(type) && block.get("items") instanceof List<?> items) {
            StringBuilder builder = new StringBuilder();
            for (Object item : items) {
                builder.append("- ").append(item).append('\n');
            }
            return builder.toString().trim();
        }
        if ("table".equals(type) && block.get("rows") instanceof List<?> rows) {
            StringBuilder builder = new StringBuilder();
            for (Object row : rows) {
                if (row instanceof List<?> cells) {
                    builder.append("| ")
                            .append(String.join(" | ", cells.stream().map(String::valueOf).toList()))
                            .append(" |\n");
                }
            }
            return builder.toString().trim();
        }
        Object text = block.get("text");
        return text == null ? "" : String.valueOf(text).trim();
    }

    private void appendBlock(StringBuilder builder, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append(text.trim());
    }

    private int number(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private String[] splitLines(String content) {
        return content == null || content.isBlank() ? new String[]{""} : content.split("\\R", -1);
    }

    private String normalize(String content) {
        return content == null ? "" : content
                .replace('\u0000', ' ')
                .replace("\r\n", "\n")
                .replaceAll("[ \\t]+", " ")
                .trim();
    }

    private String normalizePreservingLines(String content) {
        return content == null ? "" : content
                .replace('\u0000', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t]+", " ")
                .trim();
    }

    private String normalizeChunk(String content) {
        return normalizePreservingLines(content)
                .replaceAll("\\n{4,}", "\n\n\n")
                .trim();
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record PageBlock(int page, String text) {
    }

    private record ClauseSignal(String number, String level, String title) {
    }
}
