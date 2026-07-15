package com.learnbot.service;

import com.learnbot.dto.CodeSearchResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EvidenceExcerptSelector {

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}_-]{2,}");
    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']{4,})[\"']");
    private static final int WINDOW_RADIUS = 3;
    private static final int MAX_WINDOWS = 4;

    private EvidenceExcerptSelector() {
    }

    public static Excerpt select(String question, CodeSearchResult result, int maxChars) {
        String content = result == null || result.content() == null ? "" : result.content();
        int budget = Math.max(120, maxChars);
        int sourceStart = result == null ? 0 : Math.max(0, result.lineStart());
        int sourceEnd = result == null ? sourceStart : Math.max(sourceStart, result.lineEnd());
        if (isDirectRead(result)) {
            List<String> sourceLines = sourceLines(content, sourceStart, sourceEnd);
            String directContent = String.join("\n", sourceLines);
            if (directContent.length() <= budget) {
                return new Excerpt(directContent, "FULL_CHUNK", true, false, sourceStart,
                        actualLineEnd(sourceStart, sourceEnd, sourceLines.size()));
            }
            RequestedRange requestedRange = requestedRange(result, sourceStart, sourceEnd, sourceLines.size());
            return requestedRange == null
                    ? boundedDirectRead(sourceLines, budget, sourceStart, sourceEnd)
                    : requestedDirectRead(sourceLines, sourceStart, sourceEnd, requestedRange);
        }

        String compact = content.replaceAll("\\R{3,}", "\n\n").trim();
        if (compact.length() <= budget) {
            return new Excerpt(compact, "FULL_CHUNK", true, false, sourceStart, sourceEnd);
        }

        List<String> lines = compact.lines().map(String::stripTrailing).toList();
        Map<String, Double> terms = weightedTerms(question, result);
        if (terms.isEmpty()) {
            return truncated(compact, budget, sourceStart);
        }

        boolean[] commentLines = commentLines(lines);
        Map<String, Integer> documentFrequency = documentFrequency(lines, terms.keySet());
        double[] lineScores = new double[lines.size()];
        for (int index = 0; index < lines.size(); index++) {
            String normalized = normalize(lines.get(index));
            double score = 0;
            for (Map.Entry<String, Double> entry : terms.entrySet()) {
                if (!normalized.contains(entry.getKey())) {
                    continue;
                }
                int frequency = documentFrequency.getOrDefault(entry.getKey(), 1);
                double rarity = 1.0 + Math.log((lines.size() + 1.0) / (frequency + 1.0));
                score += entry.getValue() * rarity;
            }
            if (commentLines[index]) {
                score *= 0.35;
            }
            lineScores[index] = score;
        }

        List<Window> candidates = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            if (lineScores[index] <= 0) {
                continue;
            }
            int start = Math.max(0, index - WINDOW_RADIUS);
            int end = Math.min(lines.size() - 1, index + WINDOW_RADIUS);
            candidates.add(new Window(start, end,
                    windowScore(lines, start, end, terms, documentFrequency, commentLines)));
        }
        candidates.sort(Comparator.comparingDouble(Window::score).reversed()
                .thenComparingInt(Window::start));

        List<Window> selected = new ArrayList<>();
        int selectedChars = 0;
        for (Window candidate : candidates) {
            if (selected.size() >= MAX_WINDOWS || selected.stream().anyMatch(current -> overlapRatio(current, candidate) >= 0.65)) {
                continue;
            }
            int candidateChars = lines.subList(candidate.start(), candidate.end() + 1).stream()
                    .mapToInt(String::length)
                    .sum() + (candidate.end() - candidate.start());
            if (!selected.isEmpty() && selectedChars + candidateChars > budget) {
                continue;
            }
            selected.add(candidate);
            selectedChars += candidateChars;
        }
        if (selected.isEmpty()) {
            return truncated(compact, budget, sourceStart);
        }

        selected = merge(selected);
        StringBuilder text = new StringBuilder();
        int firstLine = Integer.MAX_VALUE;
        int lastLine = 0;
        int previousEnd = -1;
        for (Window window : selected) {
            String separator = previousEnd >= 0 && window.start() > previousEnd + 1
                    ? "\n... omitted lines " + (sourceStart + previousEnd + 1) + "-" + (sourceStart + window.start() - 1) + " ...\n"
                    : "";
            String block = String.join("\n", lines.subList(window.start(), window.end() + 1));
            if (text.length() + separator.length() + block.length() > budget) {
                int remaining = budget - text.length() - separator.length();
                if (remaining <= 0) {
                    break;
                }
                text.append(separator).append(block, 0, Math.min(remaining, block.length()));
                firstLine = Math.min(firstLine, window.start());
                lastLine = window.end();
                break;
            }
            text.append(separator).append(block);
            firstLine = Math.min(firstLine, window.start());
            lastLine = Math.max(lastLine, window.end());
            previousEnd = window.end();
        }
        if (text.isEmpty()) {
            return truncated(compact, budget, sourceStart);
        }
        return new Excerpt(text.toString().stripTrailing() + "\n...", "SCORED_WINDOWS", false, true,
                sourceStart + firstLine, sourceStart + lastLine);
    }

    private static boolean isDirectRead(CodeSearchResult result) {
        return result != null
                && result.metadata() != null
                && Boolean.parseBoolean(String.valueOf(result.metadata().getOrDefault("llmDirectRead", false)));
    }

    private static Excerpt requestedDirectRead(
            List<String> lines,
            int sourceStart,
            int sourceEnd,
            RequestedRange requestedRange
    ) {
        int firstIndex = requestedRange.lineStart() - sourceStart;
        int lastIndex = requestedRange.lineEnd() - sourceStart;
        StringBuilder text = new StringBuilder();
        if (firstIndex > 0) {
            text.append(requestedRangeOmissionMarker(sourceStart, requestedRange.lineStart() - 1));
        }
        text.append(String.join("\n", lines.subList(firstIndex, lastIndex + 1)));
        if (lastIndex < lines.size() - 1) {
            text.append(requestedRangeOmissionMarker(
                    requestedRange.lineEnd() + 1,
                    actualLineEnd(sourceStart, sourceEnd, lines.size())
            ));
        }
        boolean complete = firstIndex == 0 && lastIndex == lines.size() - 1;
        return new Excerpt(
                text.toString().stripTrailing(),
                "DIRECT_READ_REQUESTED_RANGE",
                complete,
                false,
                requestedRange.lineStart(),
                requestedRange.lineEnd()
        );
    }

    private static Excerpt boundedDirectRead(List<String> lines, int budget, int sourceStart, int sourceEnd) {
        int actualEnd = actualLineEnd(sourceStart, sourceEnd, lines.size());
        if (lines.size() <= 1) {
            return new Excerpt(String.join("\n", lines), "FULL_CHUNK", true, false, sourceStart, actualEnd);
        }
        int markerBudget = omissionMarker(sourceStart, actualEnd).length();
        int available = Math.max(40, budget - markerBudget);
        int headEnd = prefixEnd(lines, available * 2 / 3);
        int tailStart = suffixStart(lines, available - (available * 2 / 3));
        if (headEnd + 1 >= tailStart) {
            return new Excerpt(String.join("\n", lines), "FULL_CHUNK", true, false, sourceStart, actualEnd);
        }
        int omittedStart = sourceStart + headEnd + 1;
        int omittedEnd = sourceStart + tailStart - 1;
        String text = String.join("\n", lines.subList(0, headEnd + 1))
                + omissionMarker(omittedStart, omittedEnd)
                + String.join("\n", lines.subList(tailStart, lines.size()));
        return new Excerpt(text.stripTrailing(), "DIRECT_READ_BOUNDED", false, true, sourceStart, actualEnd);
    }

    private static List<String> sourceLines(String content, int sourceStart, int sourceEnd) {
        String normalized = content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
        if (lines.size() > 1 && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        int expectedLines = Math.max(1, sourceEnd - sourceStart + 1);
        if (lines.size() > expectedLines) {
            return List.copyOf(lines.subList(0, expectedLines));
        }
        return lines.isEmpty() ? List.of("") : List.copyOf(lines);
    }

    private static RequestedRange requestedRange(
            CodeSearchResult result,
            int sourceStart,
            int sourceEnd,
            int availableLines
    ) {
        Integer requestedStart = metadataInteger(result, "llmRequestedLineStart");
        Integer requestedEnd = metadataInteger(result, "llmRequestedLineEnd");
        if (requestedStart == null || requestedEnd == null || requestedEnd < requestedStart) {
            return null;
        }
        int actualEnd = actualLineEnd(sourceStart, sourceEnd, availableLines);
        int lineStart = Math.max(sourceStart, requestedStart);
        int lineEnd = Math.min(actualEnd, requestedEnd);
        return lineEnd < lineStart ? null : new RequestedRange(lineStart, lineEnd);
    }

    private static Integer metadataInteger(CodeSearchResult result, String key) {
        if (result == null || result.metadata() == null) {
            return null;
        }
        Object value = result.metadata().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int prefixEnd(List<String> lines, int budget) {
        int used = 0;
        int end = 0;
        for (int index = 0; index < lines.size(); index++) {
            int addition = lines.get(index).length() + (index == 0 ? 0 : 1);
            if (index > 0 && used + addition > Math.max(1, budget)) {
                break;
            }
            used += addition;
            end = index;
        }
        return end;
    }

    private static int suffixStart(List<String> lines, int budget) {
        int used = 0;
        int start = lines.size() - 1;
        for (int index = lines.size() - 1; index >= 0; index--) {
            int addition = lines.get(index).length() + (index == lines.size() - 1 ? 0 : 1);
            if (index < lines.size() - 1 && used + addition > Math.max(1, budget)) {
                break;
            }
            used += addition;
            start = index;
        }
        return start;
    }

    private static int actualLineEnd(int sourceStart, int sourceEnd, int lineCount) {
        return Math.min(sourceEnd, sourceStart + Math.max(0, lineCount - 1));
    }

    private static String omissionMarker(int lineStart, int lineEnd) {
        return "\n... direct-read content omitted by prompt budget: lines "
                + lineStart + "-" + lineEnd + " ...\n";
    }

    private static String requestedRangeOmissionMarker(int lineStart, int lineEnd) {
        return "\n... direct-read content outside requested range omitted: lines "
                + lineStart + "-" + lineEnd + " ...\n";
    }

    private static Map<String, Double> weightedTerms(String question, CodeSearchResult result) {
        Map<String, Double> terms = new LinkedHashMap<>();
        addTerms(terms, question, 6.0);
        addPhraseTerms(terms, question, 10.0);
        Matcher quoted = QUOTED.matcher(question == null ? "" : question);
        while (quoted.find()) {
            addTerm(terms, normalize(quoted.group(1)), 12.0);
        }
        if (result != null) {
            addTerms(terms, result.symbolName(), 8.0);
            addTerms(terms, result.methodName(), 8.0);
            addTerms(terms, result.className(), 6.0);
            addMetadataTerms(terms, result.metadata() == null ? null : result.metadata().get("llmSupportedClaims"), 7.0);
            addMetadataTerms(terms, result.metadata() == null ? null : result.metadata().get("llmNotSupportedClaims"), 4.0);
            addMetadataTerms(terms, result.metadata() == null ? null : result.metadata().get("llmFollowUpQuery"), 5.0);
            addTerms(terms, result.filePath(), 0.5);
        }
        return terms;
    }

    private static void addMetadataTerms(Map<String, Double> terms, Object value, double weight) {
        if (value instanceof Collection<?> values) {
            values.forEach(item -> addTerms(terms, String.valueOf(item), weight));
        } else if (value != null) {
            addTerms(terms, String.valueOf(value), weight);
        }
    }
    private static void addPhraseTerms(Map<String, Double> terms, String value, double weight) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return;
        }
        String[] tokens = normalized.split("\\s+");
        for (int size = 3; size <= Math.min(6, tokens.length); size++) {
            for (int start = 0; start + size <= tokens.length; start++) {
                String phrase = String.join(" ", java.util.Arrays.copyOfRange(tokens, start, start + size));
                addTerm(terms, phrase, weight + size);
            }
        }
    }


    private static void addTerms(Map<String, Double> terms, String value, double weight) {
        if (value == null || value.isBlank()) {
            return;
        }
        Matcher matcher = TOKEN.matcher(splitIdentifiers(value).toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            addTerm(terms, matcher.group(), weight);
        }
    }

    private static void addTerm(Map<String, Double> terms, String term, double weight) {
        if (term != null && term.length() >= 2) {
            terms.merge(term, weight, Math::max);
        }
    }

    private static String splitIdentifiers(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("[^\\p{L}\\p{N}_-]+", " ");
    }

    private static String normalize(String value) {
        return splitIdentifiers(value == null ? "" : value).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static Map<String, Integer> documentFrequency(List<String> lines, Set<String> terms) {
        Map<String, Integer> frequency = new HashMap<>();
        for (String line : lines) {
            String normalized = normalize(line);
            for (String term : terms) {
                if (normalized.contains(term)) {
                    frequency.merge(term, 1, Integer::sum);
                }
            }
        }
        return frequency;
    }

    private static double windowScore(
            List<String> lines,
            int start,
            int end,
            Map<String, Double> terms,
            Map<String, Integer> documentFrequency,
            boolean[] commentLines
    ) {
        String normalized = normalize(String.join(" ", lines.subList(start, end + 1)));
        double score = 0;
        int matchedTerms = 0;
        for (Map.Entry<String, Double> entry : terms.entrySet()) {
            if (!normalized.contains(entry.getKey())) {
                continue;
            }
            int frequency = documentFrequency.getOrDefault(entry.getKey(), 1);
            double rarity = 1.0 + Math.log((lines.size() + 1.0) / (frequency + 1.0));
            score += entry.getValue() * rarity;
            matchedTerms++;
        }
        int commentCount = 0;
        for (int index = start; index <= end; index++) {
            if (commentLines[index]) {
                commentCount++;
            }
        }
        double commentRatio = (double) commentCount / (end - start + 1);
        return (score + (matchedTerms * 2.0)) * (1.0 - (commentRatio * 0.7));
    }

    private static boolean[] commentLines(List<String> lines) {
        boolean[] comments = new boolean[lines.size()];
        boolean block = false;
        for (int index = 0; index < lines.size(); index++) {
            String value = lines.get(index) == null ? "" : lines.get(index).stripLeading();
            boolean startsBlock = value.contains("/*") || value.contains("<!--");
            boolean endsBlock = value.contains("*/") || value.contains("-->");
            comments[index] = block || startsBlock || isCommentOnly(value);
            if (startsBlock && !endsBlock) {
                block = true;
            }
            if (endsBlock) {
                block = false;
            }
        }
        return comments;
    }

    private static boolean isCommentOnly(String line) {
        String value = line == null ? "" : line.stripLeading();
        return value.startsWith("//") || value.startsWith("/*") || value.startsWith("*")
                || value.startsWith("#") || value.startsWith("<!--");
    }

    private static double overlapRatio(Window left, Window right) {
        int overlap = Math.max(0, Math.min(left.end(), right.end()) - Math.max(left.start(), right.start()) + 1);
        int smaller = Math.min(left.end() - left.start() + 1, right.end() - right.start() + 1);
        return smaller == 0 ? 0 : (double) overlap / smaller;
    }

    private static List<Window> merge(List<Window> windows) {
        List<Window> ordered = windows.stream().sorted(Comparator.comparingInt(Window::start)).toList();
        List<Window> merged = new ArrayList<>();
        for (Window window : ordered) {
            if (merged.isEmpty() || window.start() > merged.get(merged.size() - 1).end() + 1) {
                merged.add(window);
            } else {
                Window previous = merged.remove(merged.size() - 1);
                merged.add(new Window(previous.start(), Math.max(previous.end(), window.end()), Math.max(previous.score(), window.score())));
            }
        }
        return merged;
    }

    private static Excerpt truncated(String content, int budget, int sourceStart) {
        String text = content.substring(0, Math.min(content.length(), budget)).stripTrailing() + "\n...";
        return new Excerpt(text, "TRUNCATED_CHUNK", false, true, sourceStart,
                sourceStart + Math.max(0, (int) text.lines().count() - 1));
    }

    public record Excerpt(String text, String kind, boolean contentComplete, boolean omittedByBudget,
                          int lineStart, int lineEnd) {
    }

    private record Window(int start, int end, double score) {
    }

    private record RequestedRange(int lineStart, int lineEnd) {
    }
}
