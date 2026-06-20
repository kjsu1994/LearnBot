package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Component
public class DocumentContextBuilder {
    private static final int STRUCTURE_VERSION = 1;
    private static final int MAX_CONTEXT_CHARS = 9000;
    private static final int MAX_CONTEXT_CHUNKS_PER_DOCUMENT = 2;

    private final LearnBotProperties properties;
    private final OllamaClient ollamaClient;

    public DocumentContextBuilder(LearnBotProperties properties, OllamaClient ollamaClient) {
        this.properties = properties;
        this.ollamaClient = ollamaClient;
    }

    public boolean enabled() {
        return properties.getRag().getDocumentContext().isEnabled();
    }

    public List<Chunk> buildDocumentContext(ExtractedDocument document, List<Chunk> chunks) {
        if (!enabled() || document == null || chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        DocumentFacts facts = facts(document, chunks);
        List<Chunk> output = new ArrayList<>();
        addChunk(output, "document_structure", "document", structureContent(document, facts), Map.of(
                "summaryLevel", "document",
                "generatedBy", "deterministic",
                "llmAttempted", false,
                "llmSucceeded", false
        ));

        HybridText summary = documentSummary(document, chunks, facts);
        addChunk(output, "document_summary", "document", summary.content(), Map.of(
                "summaryLevel", "document",
                "generatedBy", summary.generatedBy(),
                "llmAttempted", llmEnabled(),
                "llmSucceeded", summary.llmSucceeded()
        ));
        return output.stream().limit(MAX_CONTEXT_CHUNKS_PER_DOCUMENT).toList();
    }

    public List<Chunk> buildSourceContext(List<DocumentContextInput> documents) {
        if (!enabled() || documents == null || documents.size() <= 1) {
            return List.of();
        }
        List<DocumentContextInput> usable = documents.stream()
                .filter(input -> input != null && input.document() != null && input.chunks() != null && !input.chunks().isEmpty())
                .limit(Math.max(1, properties.getRag().getDocumentContext().getMaxSourceDocuments()))
                .toList();
        if (usable.size() <= 1) {
            return List.of();
        }
        SourceFacts facts = sourceFacts(usable);
        List<Chunk> output = new ArrayList<>();
        addChunk(output, "source_structure", "source", sourceStructureContent(facts), Map.of(
                "summaryLevel", "source",
                "generatedBy", "deterministic",
                "llmAttempted", false,
                "llmSucceeded", false,
                "documentCount", usable.size()
        ));
        HybridText summary = sourceSummary(facts);
        addChunk(output, "source_summary", "source", summary.content(), Map.of(
                "summaryLevel", "source",
                "generatedBy", summary.generatedBy(),
                "llmAttempted", llmEnabled(),
                "llmSucceeded", summary.llmSucceeded(),
                "documentCount", usable.size()
        ));
        return output;
    }

    private DocumentFacts facts(ExtractedDocument document, List<Chunk> chunks) {
        Map<String, Integer> strategies = new TreeMap<>();
        Set<String> headings = new LinkedHashSet<>();
        Set<String> pages = new LinkedHashSet<>();
        Set<String> slides = new LinkedHashSet<>();
        Set<String> sheets = new LinkedHashSet<>();
        Set<String> tables = new LinkedHashSet<>();
        List<String> sampleTexts = new ArrayList<>();

        for (Chunk chunk : chunks) {
            Map<String, Object> metadata = chunk.metadata() == null ? Map.of() : chunk.metadata();
            mergeCount(strategies, string(metadata, "strategy"));
            addIfPresent(headings, string(metadata, "headingPath"));
            addRange(pages, metadata, "pageStart", "pageEnd", "pages ");
            addRange(slides, metadata, "slideStart", "slideEnd", "slides ");
            if (!string(metadata, "sheetName").isBlank()) {
                String rowRange = range(metadata, "rowStart", "rowEnd");
                sheets.add(string(metadata, "sheetName") + (rowRange.isBlank() ? "" : " rows " + rowRange));
            }
            if (metadata.containsKey("tableIndex")) {
                String rowRange = range(metadata, "rowStart", "rowEnd");
                tables.add("table " + metadata.get("tableIndex") + (rowRange.isBlank() ? "" : " rows " + rowRange));
            }
            if (sampleTexts.size() < 8) {
                sampleTexts.add(trim(chunk.content(), 500));
            }
        }
        return new DocumentFacts(
                chunks.size(),
                strategies,
                headings.stream().limit(40).toList(),
                pages.stream().limit(80).toList(),
                slides.stream().limit(80).toList(),
                sheets.stream().limit(80).toList(),
                tables.stream().limit(80).toList(),
                keywords(document, chunks),
                sampleTexts
        );
    }

    private SourceFacts sourceFacts(List<DocumentContextInput> documents) {
        List<DocumentSourceLine> lines = documents.stream()
                .map(input -> {
                    DocumentFacts facts = facts(input.document(), input.chunks());
                    return new DocumentSourceLine(
                            input.document().title(),
                            input.document().sourceUri(),
                            input.document().contentType(),
                            facts.chunkCount(),
                            firstNonEmpty(facts.headings(), facts.pages(), facts.sheets(), facts.slides())
                    );
                })
                .sorted(Comparator.comparing(DocumentSourceLine::title, String.CASE_INSENSITIVE_ORDER))
                .toList();
        Map<String, Long> contentTypes = lines.stream()
                .collect(Collectors.groupingBy(line -> clean(line.contentType()), TreeMap::new, Collectors.counting()));
        return new SourceFacts(lines, contentTypes);
    }

    private String structureContent(ExtractedDocument document, DocumentFacts facts) {
        return """
                Document structure context
                Title: %s
                Source URI: %s
                Content type: %s
                Original chunks: %d
                Chunk strategies: %s
                Headings: %s
                Pages: %s
                Slides: %s
                Sheets and row ranges: %s
                Tables: %s
                Search keywords: document structure outline table sheet page slide section heading where located source map
                """.formatted(
                clean(document.title()),
                clean(document.sourceUri()),
                clean(document.contentType()),
                facts.chunkCount(),
                joinMap(facts.strategies()),
                joinOrDash(facts.headings()),
                joinOrDash(facts.pages()),
                joinOrDash(facts.slides()),
                joinOrDash(facts.sheets()),
                joinOrDash(facts.tables())
        ).strip();
    }

    private HybridText documentSummary(ExtractedDocument document, List<Chunk> chunks, DocumentFacts facts) {
        String deterministic = """
                Document summary
                Title: %s
                Source URI: %s
                Content type: %s
                This document has %d indexed content chunks.
                Main structure signals: headings=%s; pages=%s; sheets=%s; tables=%s.
                Retrieval keywords: %s.
                Use this chunk for overview, summary, main topic, and high-level question routing.
                """.formatted(
                clean(document.title()),
                clean(document.sourceUri()),
                clean(document.contentType()),
                facts.chunkCount(),
                joinOrDash(facts.headings().stream().limit(12).toList()),
                joinOrDash(facts.pages().stream().limit(12).toList()),
                joinOrDash(facts.sheets().stream().limit(12).toList()),
                joinOrDash(facts.tables().stream().limit(12).toList()),
                joinOrDash(facts.keywords())
        ).strip();
        if (!llmEnabled()) {
            return new HybridText(deterministic, "deterministic", false);
        }
        if (properties.getRag().getDocumentContext().isMapReduceEnabled()) {
            HybridText mapReduce = mapReduceDocumentSummary(document, chunks, deterministic);
            if (mapReduce.llmSucceeded()) {
                return mapReduce;
            }
        }
        String samples = facts.sampleTexts().stream().collect(Collectors.joining("\n\n---\n\n"));
        return llmSummary(
                "Summarize this document for retrieval. Mention concrete sections, pages, sheets, tables, and topics when present.",
                deterministic + "\n\nRepresentative excerpts:\n" + samples,
                deterministic
        );
    }

    private HybridText mapReduceDocumentSummary(ExtractedDocument document, List<Chunk> chunks, String fallback) {
        if (chunks == null || chunks.isEmpty()) {
            return new HybridText(fallback, "deterministic", false);
        }
        try {
            int windowSize = Math.max(1, properties.getRag().getDocumentContext().getMapWindowChunks());
            int maxWindows = Math.max(1, properties.getRag().getDocumentContext().getMaxMapWindowsPerDocument());
            List<String> mapSummaries = new ArrayList<>();
            for (int start = 0; start < chunks.size() && mapSummaries.size() < maxWindows; start += windowSize) {
                int end = Math.min(chunks.size(), start + windowSize);
                String window = chunks.subList(start, end).stream()
                        .map(chunk -> "Chunk " + chunk.index() + ":\n" + clean(chunk.content()))
                        .collect(Collectors.joining("\n\n---\n\n"));
                HybridText mapped = llmSummary(
                        "Create a retrieval map summary for this chunk window from document " + clean(document.title()) + ".",
                        window,
                        "",
                        maxMapInputChars()
                );
                if (mapped.llmSucceeded() && !mapped.content().isBlank()) {
                    mapSummaries.add(mapped.content());
                }
            }
            if (mapSummaries.isEmpty()) {
                return new HybridText(fallback, "deterministic", false);
            }
            HybridText reduced = llmSummary(
                    "Reduce these chunk-window summaries into one document retrieval summary. Preserve concrete topics, sections, pages, sheets, tables, and limitations.",
                    "Document: " + clean(document.title()) + "\nSource URI: " + clean(document.sourceUri())
                            + "\n\nWindow summaries:\n" + trim(String.join("\n\n---\n\n", mapSummaries), maxReduceInputChars()),
                    fallback,
                    maxReduceInputChars()
            );
            if (reduced.llmSucceeded()) {
                return new HybridText(reduced.content(), "llm_auxiliary_map_reduce", true);
            }
            return new HybridText(fallback, "deterministic", false);
        } catch (RuntimeException ex) {
            return new HybridText(fallback, "deterministic", false);
        }
    }

    private String sourceStructureContent(SourceFacts facts) {
        String documents = facts.documents().stream()
                .limit(120)
                .map(line -> "- " + line.title() + " | " + line.sourceUri()
                        + " | type=" + line.contentType()
                        + " | chunks=" + line.chunkCount()
                        + " | structure=" + line.signals())
                .collect(Collectors.joining("\n"));
        return """
                Source structure context
                Documents in source: %d
                Content types: %s

                Document map:
                %s

                Search keywords: source structure site map crawl recursive pages documents overview routing where located
                """.formatted(facts.documents().size(), joinMap(facts.contentTypes()), documents).strip();
    }

    private HybridText sourceSummary(SourceFacts facts) {
        String deterministic = """
                Source summary
                This source contains %d indexed documents.
                Content types: %s.
                Representative documents: %s.
                Use this chunk for source-wide overview, multi-page summary, and deciding which document may answer a question.
                """.formatted(
                facts.documents().size(),
                joinMap(facts.contentTypes()),
                facts.documents().stream().limit(16).map(DocumentSourceLine::title).collect(Collectors.joining(", "))
        ).strip();
        if (!llmEnabled()) {
            return new HybridText(deterministic, "deterministic", false);
        }
        return llmSummary(
                "Summarize this multi-document source for retrieval. Mention the document map and likely question routing.",
                deterministic + "\n\n" + sourceStructureContent(facts),
                deterministic,
                maxReduceInputChars()
        );
    }

    private HybridText llmSummary(String instruction, String context, String fallback) {
        return llmSummary(instruction, context, fallback, maxSummaryInputChars());
    }

    private HybridText llmSummary(String instruction, String context, String fallback, int maxInputChars) {
        try {
            String response = ollamaClient.chat(
                    """
                            You create compact document retrieval summaries.
                            Use only the provided facts and excerpts. Do not invent facts, counts, pages, tables, or source names.
                            Return plain text with concrete titles, sections, pages, sheets, tables, and topics.
                            """,
                    instruction + "\n\nFacts:\n" + trim(maskSecrets(context), Math.max(1000, maxInputChars)),
                    OllamaClient.ChatRole.AUXILIARY
            );
            String clean = response == null ? "" : response.strip();
            if (clean.length() < 20) {
                return new HybridText(fallback, "deterministic", false);
            }
            return new HybridText(clean, "llm_auxiliary", true);
        } catch (RuntimeException ex) {
            return new HybridText(fallback, "deterministic", false);
        }
    }

    private void addChunk(List<Chunk> chunks, String contextType, String summaryLevel, String content, Map<String, Object> metadata) {
        String clean = trim(maskSecrets(content), MAX_CONTEXT_CHARS);
        if (clean.isBlank()) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>(metadata);
        values.put("kind", "document_context");
        values.put("contextType", contextType);
        values.put("summaryLevel", summaryLevel);
        values.put("strategy", "document_context");
        values.put("blockType", "context");
        values.put("structureVersion", STRUCTURE_VERSION);
        chunks.add(new Chunk(chunks.size(), clean, values));
    }

    private boolean llmEnabled() {
        return properties.getRag().getDocumentContext().isLlmSummaryEnabled();
    }

    private int maxSummaryInputChars() {
        return Math.max(1000, properties.getRag().getDocumentContext().getMaxSummaryInputChars());
    }

    private int maxMapInputChars() {
        return Math.max(1000, properties.getRag().getDocumentContext().getMaxMapInputChars());
    }

    private int maxReduceInputChars() {
        return Math.max(1000, properties.getRag().getDocumentContext().getMaxReduceInputChars());
    }

    private List<String> keywords(ExtractedDocument document, List<Chunk> chunks) {
        Map<String, Integer> counts = new TreeMap<>();
        addKeywords(counts, document.title());
        addKeywords(counts, document.sourceUri());
        for (Chunk chunk : chunks.stream().limit(30).toList()) {
            addKeywords(counts, chunk.content());
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .limit(24)
                .map(Map.Entry::getKey)
                .toList();
    }

    private void addKeywords(Map<String, Integer> counts, String value) {
        for (String token : clean(value).toLowerCase(Locale.ROOT).split("[^\\p{IsHangul}\\p{IsAlphabetic}\\p{IsDigit}]+")) {
            if (token.length() < 3 || token.length() > 40 || STOP_WORDS.contains(token)) {
                continue;
            }
            counts.merge(token, 1, Integer::sum);
        }
    }

    private void addRange(Set<String> output, Map<String, Object> metadata, String startKey, String endKey, String prefix) {
        String range = range(metadata, startKey, endKey);
        if (!range.isBlank()) {
            output.add(prefix + range);
        }
    }

    private String range(Map<String, Object> metadata, String startKey, String endKey) {
        Object start = metadata.get(startKey);
        Object end = metadata.get(endKey);
        if (start == null && end == null) {
            return "";
        }
        if (start == null || end == null || String.valueOf(start).equals(String.valueOf(end))) {
            return String.valueOf(start == null ? end : start);
        }
        return start + "-" + end;
    }

    private String firstNonEmpty(List<String>... values) {
        for (List<String> value : values) {
            if (value != null && !value.isEmpty()) {
                return String.join(", ", value.stream().limit(6).toList());
            }
        }
        return "-";
    }

    private String string(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private void addIfPresent(Set<String> output, String value) {
        if (value != null && !value.isBlank()) {
            output.add(value);
        }
    }

    private void mergeCount(Map<String, Integer> counts, String value) {
        if (value != null && !value.isBlank()) {
            counts.merge(value, 1, Integer::sum);
        }
    }

    private String joinMap(Map<String, ? extends Number> values) {
        if (values == null || values.isEmpty()) {
            return "-";
        }
        return values.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private String joinOrDash(Iterable<String> values) {
        if (values == null) {
            return "-";
        }
        List<String> clean = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                clean.add(value);
            }
        }
        return clean.isEmpty() ? "-" : String.join(", ", clean);
    }

    private String trim(String value, int maxChars) {
        String clean = value == null ? "" : value.strip();
        return clean.length() <= maxChars ? clean : clean.substring(0, maxChars).strip() + "\n...";
    }

    private String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private String maskSecrets(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value
                .replaceAll("(?i)(password\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(secret\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(token\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(api[_-]?key\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(credential\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]");
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "this", "that", "from", "into", "onto", "about",
            "document", "source", "page", "sheet", "table", "row", "chunk"
    );

    public record DocumentContextInput(ExtractedDocument document, List<Chunk> chunks) {
    }

    private record DocumentFacts(
            int chunkCount,
            Map<String, Integer> strategies,
            List<String> headings,
            List<String> pages,
            List<String> slides,
            List<String> sheets,
            List<String> tables,
            List<String> keywords,
            List<String> sampleTexts
    ) {
    }

    private record SourceFacts(List<DocumentSourceLine> documents, Map<String, Long> contentTypes) {
    }

    private record DocumentSourceLine(String title, String sourceUri, String contentType, int chunkCount, String signals) {
    }

    private record HybridText(String content, String generatedBy, boolean llmSucceeded) {
    }
}
