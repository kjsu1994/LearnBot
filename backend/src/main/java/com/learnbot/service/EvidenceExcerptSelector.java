package com.learnbot.service;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.coderag.evidence.CodeLexicalCalls;
import com.learnbot.service.coderag.model.CodeEvidenceOperationProvenance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EvidenceExcerptSelector {

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}_-]{2,}");
    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']{4,})[\"']");
    private static final Pattern SOURCE_RANGE_HEADER = Pattern.compile(
            "^\\s*Lines:\\s*(\\d+)\\s*-\\s*(\\d+)\\s*$");
    private static final Pattern NUMBERED_SOURCE_LINE = Pattern.compile("^\\s*(\\d+):(.*)$");
    private static final Pattern INVOCATION_PREFIX = Pattern.compile(
            "(?i)(?:^|.*[^\\p{L}\\p{N}_])(?:if|while|for|return|throw|await|yield|unless|case)\\s*$");
    private static final int WINDOW_RADIUS = 3;
    private static final int MAX_WINDOWS = 4;
    private static final int MAX_DIRECT_WINDOWS = 6;
    private static final int REQUESTED_RANGE_PADDING_LINES = 2;

    private EvidenceExcerptSelector() {
    }

    public static Excerpt select(String question, CodeSearchResult result, int maxChars) {
        String content = result == null || result.content() == null ? "" : result.content();
        int budget = Math.max(1, maxChars);
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
                    ? boundedDirectRead(question, result, sourceLines, budget, sourceStart, sourceEnd)
                    : requestedDirectRead(
                            question,
                            result,
                            sourceLines,
                            Math.max(1, maxChars),
                            sourceStart,
                            sourceEnd,
                            requestedRange
                    );
        }

        List<String> lines = contentLines(content).stream()
                .map(String::stripTrailing)
                .toList();
        String normalizedContent = String.join("\n", lines);
        if (normalizedContent.length() <= budget) {
            return new Excerpt(normalizedContent, "FULL_CHUNK", true, false, sourceStart, sourceEnd);
        }

        Map<String, Double> terms = weightedTerms(question, result);
        if (terms.isEmpty()) {
            return truncated(normalizedContent, budget, sourceStart);
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
        Window structuralAnchor = structuralAnchorWindow(
                question, result, lines, 0, lines.size() - 1, budget);
        if (structuralAnchor != null) {
            int structuralChars = windowChars(lines, structuralAnchor);
            if (structuralChars <= budget) {
                selected.add(structuralAnchor);
                selectedChars = structuralChars;
            }
        }
        for (Window candidate : candidates) {
            if (selected.size() >= MAX_WINDOWS || coveredBySelected(selected, candidate)) {
                continue;
            }
            int candidateChars = windowChars(lines, candidate);
            if (!selected.isEmpty() && selectedChars + candidateChars > budget) {
                continue;
            }
            selected.add(candidate);
            coalesceWindows(selected);
            selectedChars += candidateChars;
        }
        if (selected.isEmpty()) {
            return truncated(normalizedContent, budget, sourceStart);
        }

        selected = merge(selected);
        StringBuilder text = new StringBuilder();
        String omissionSuffix = budget >= 4 ? "\n..." : ".".repeat(budget);
        int contentBudget = Math.max(0, budget - omissionSuffix.length());
        int firstLine = Integer.MAX_VALUE;
        int lastLine = 0;
        int previousEnd = -1;
        for (Window window : selected) {
            String separator = previousEnd >= 0 && window.start() > previousEnd + 1
                    ? "\n... omitted lines " + (sourceStart + previousEnd + 1) + "-" + (sourceStart + window.start() - 1) + " ...\n"
                    : "";
            int remaining = contentBudget - text.length() - separator.length();
            if (remaining <= 0) {
                break;
            }

            StringBuilder block = new StringBuilder();
            int renderedEnd = -1;
            for (int index = window.start(); index <= window.end(); index++) {
                String linePrefix = block.isEmpty() ? "" : "\n";
                String line = lines.get(index);
                if (block.length() + linePrefix.length() + line.length() > remaining) {
                    break;
                }
                block.append(linePrefix).append(line);
                renderedEnd = index;
            }
            if (renderedEnd < window.start()) {
                break;
            }
            text.append(separator).append(block);
            firstLine = Math.min(firstLine, window.start());
            lastLine = Math.max(lastLine, renderedEnd);
            previousEnd = renderedEnd;
            if (renderedEnd < window.end()) {
                break;
            }
        }
        if (text.isEmpty()) {
            return truncated(normalizedContent, budget, sourceStart);
        }
        return new Excerpt(text.toString().stripTrailing() + omissionSuffix, "SCORED_WINDOWS", false, true,
                sourceStart + firstLine, sourceStart + lastLine);
    }

    private static boolean isDirectRead(CodeSearchResult result) {
        return result != null
                && result.metadata() != null
                && Boolean.parseBoolean(String.valueOf(result.metadata().getOrDefault("llmDirectRead", false)));
    }

    private static Excerpt requestedDirectRead(
            String question,
            CodeSearchResult result,
            List<String> lines,
            int budget,
            int sourceStart,
            int sourceEnd,
            RequestedRange requestedRange
    ) {
        int firstIndex = requestedRange.lineStart() - sourceStart;
        int lastIndex = requestedRange.lineEnd() - sourceStart;
        StringBuilder requestedText = new StringBuilder();
        if (firstIndex > 0) {
            requestedText.append(requestedRangeOmissionMarker(sourceStart, requestedRange.lineStart() - 1));
        }
        requestedText.append(String.join("\n", lines.subList(firstIndex, lastIndex + 1)));
        if (lastIndex < lines.size() - 1) {
            requestedText.append(requestedRangeOmissionMarker(
                    requestedRange.lineEnd() + 1,
                    actualLineEnd(sourceStart, sourceEnd, lines.size())
            ));
        }
        String renderedRequestedText = requestedText.toString().stripTrailing();
        if (renderedRequestedText.length() > budget) {
            return boundedRequestedDirectRead(
                    question,
                    result,
                    lines,
                    budget,
                    sourceStart,
                    firstIndex,
                    lastIndex
            );
        }
        boolean complete = firstIndex == 0 && lastIndex == lines.size() - 1;
        return new Excerpt(
                renderedRequestedText,
                "DIRECT_READ_REQUESTED_RANGE",
                complete,
                false,
                requestedRange.lineStart(),
                requestedRange.lineEnd()
        );
    }

    private static Excerpt boundedRequestedDirectRead(
            String question,
            CodeSearchResult result,
            List<String> lines,
            int budget,
            int sourceStart,
            int requestedFirstIndex,
            int requestedLastIndex
    ) {
        int scopeStart = Math.max(0, requestedFirstIndex - REQUESTED_RANGE_PADDING_LINES);
        int scopeEnd = Math.min(lines.size() - 1, requestedLastIndex + REQUESTED_RANGE_PADDING_LINES);
        Map<String, Double> terms = weightedTerms(question, result);
        boolean[] comments = commentLines(lines);
        Map<String, Integer> frequency = documentFrequency(lines, terms.keySet());
        List<Window> selected = new ArrayList<>();
        List<RequestedWindow> behavioralCandidates = behavioralCallWindows(
                result, lines, 0, lines.size() - 1, terms, frequency, comments);
        int behavioralScopeStart = behavioralCandidates.stream()
                .mapToInt(RequestedWindow::anchor)
                .min()
                .orElse(scopeStart);
        int behavioralScopeEnd = behavioralCandidates.stream()
                .mapToInt(RequestedWindow::anchor)
                .max()
                .orElse(scopeEnd);
        int renderScopeStart = Math.min(scopeStart, behavioralScopeStart);
        int renderScopeEnd = Math.max(scopeEnd, behavioralScopeEnd);
        List<Window> structuralFallback = List.of();
        List<Window> excludedSameNameAnchors = List.of();
        List<Window> structuralAnchors = structuralAnchorWindows(
                result, lines, scopeStart, scopeEnd, budget);
        if (!structuralAnchors.isEmpty()) {
            List<Window> boundaryAnchors = preferredStructuralAnchors(
                    structuralAnchors, lines, terms, frequency, comments);
            structuralFallback = boundaryAnchors;
            if (boundaryAnchors.size() == 1 && structuralAnchors.size() > 1) {
                Window preferred = boundaryAnchors.get(0);
                excludedSameNameAnchors = structuralAnchors.stream()
                        .filter(anchor -> anchor.start() != preferred.start() || anchor.end() != preferred.end())
                        .toList();
            }
            // A seven-line evidence window often needs a few hundred characters. Reserving only a
            // token-sized tail lets a long declaration consume the whole budget and silently drops
            // the call or state transition that made the chunk relevant.
            int executionReserve = Math.min(620, Math.max(48, budget / 2));
            executionReserve = Math.min(Math.max(1, budget - 1), executionReserve);
            int structuralBudget = Math.max(1, budget - executionReserve);
            String renderedAnchors = renderRequestedWindows(
                    lines, merge(boundaryAnchors), sourceStart, scopeStart, scopeEnd);
            if (renderedAnchors.length() <= structuralBudget) {
                selected.addAll(boundaryAnchors);
            } else {
                if (boundaryAnchors.size() > 1) {
                    Map<String, Double> intentTerms = structuralIntentTerms(question, result);
                    boolean relevantOutsideAnchors = hasRelevantLineOutsideAnchors(
                            lines,
                            scopeStart,
                            scopeEnd,
                            structuralAnchors,
                            intentTerms,
                            documentFrequency(lines, intentTerms.keySet()),
                            comments);
                    if (!relevantOutsideAnchors && behavioralCandidates.isEmpty()) {
                        return boundedStructuralAnchors(lines, boundaryAnchors, budget, sourceStart);
                    }
                    for (Window anchor : boundaryAnchors) {
                        Window fitted = fitRequestedWindow(
                                lines,
                                selected,
                                new RequestedWindow(anchor, anchor.start()),
                                structuralBudget,
                                sourceStart,
                                renderScopeStart,
                                renderScopeEnd);
                        if (fitted != null) selected.add(fitted);
                    }
                } else {
                    for (Window anchor : boundaryAnchors) {
                        Window fitted = fitRequestedWindow(
                                lines,
                                selected,
                                new RequestedWindow(anchor, anchor.start()),
                                structuralBudget,
                                sourceStart,
                                renderScopeStart,
                                renderScopeEnd);
                        if (fitted != null) selected.add(fitted);
                    }
                }
                if (selected.isEmpty()
                        && boundaryAnchors.size() == 1
                        && boundaryAnchors.get(0).end() - boundaryAnchors.get(0).start() <= 1) {
                    return boundedStructuralAnchors(lines, boundaryAnchors, budget, sourceStart);
                }
            }
        }
        List<RequestedWindow> candidates = new ArrayList<>();
        for (int index = scopeStart; index <= scopeEnd; index++) {
            int candidateIndex = index;
            if (excludedSameNameAnchors.stream()
                    .anyMatch(anchor -> candidateIndex >= anchor.start() && candidateIndex <= anchor.end())) {
                continue;
            }
            if (windowScore(lines, index, index, terms, frequency, comments) <= 0) continue;
            int start = Math.max(scopeStart, index - WINDOW_RADIUS);
            int end = Math.min(scopeEnd, index + WINDOW_RADIUS);
            candidates.add(new RequestedWindow(
                    new Window(start, end, windowScore(lines, start, end, terms, frequency, comments)),
                    index));
        }
        candidates.sort(Comparator.comparingDouble((RequestedWindow candidate) -> candidate.window().score())
                .reversed()
                .thenComparingInt(RequestedWindow::anchor));
        // Preserve the observable execution skeleton before lexical windows consume the remaining
        // budget. Calls inside rejected same-name declarations stay excluded, so a relevant overload
        // cannot be displaced by an unrelated implementation that happens to share its name.
        for (RequestedWindow candidate : behavioralCandidates) {
            if (excludedSameNameAnchors.stream().anyMatch(anchor ->
                    candidate.anchor() >= anchor.start() && candidate.anchor() <= anchor.end())) {
                continue;
            }
            if (selected.size() >= MAX_DIRECT_WINDOWS || coveredBySelected(selected, candidate.window())) {
                continue;
            }
            Window fitted = fitRequestedWindow(
                    lines, selected, candidate, budget, sourceStart,
                    renderScopeStart, renderScopeEnd);
            if (fitted != null) {
                selected.add(fitted);
                coalesceWindows(selected);
            }
        }
        for (RequestedWindow candidate : candidates) {
            if (structuralAnchors.size() > 1
                    && structuralAnchors.stream().noneMatch(anchor ->
                    candidate.anchor() >= anchor.start() && candidate.anchor() <= anchor.end())) {
                Map<String, Double> intentTerms = structuralIntentTerms(question, result);
                Map<String, Integer> intentFrequency = documentFrequency(lines, intentTerms.keySet());
                if (windowScore(
                        lines, candidate.anchor(), candidate.anchor(),
                        intentTerms, intentFrequency, comments) > 0) {
                    Window owner = owningStructuralAnchor(lines, structuralAnchors, candidate.anchor());
                    if (owner != null) {
                        return ownedRelevantExcerpt(
                                lines, owner, candidate.anchor(), budget,
                                sourceStart, scopeStart, scopeEnd);
                    }
                }
            }
            if (selected.size() >= MAX_DIRECT_WINDOWS || coveredBySelected(selected, candidate.window())) {
                continue;
            }
            Window fitted = fitRequestedWindow(
                    lines, selected, candidate, budget, sourceStart,
                    renderScopeStart, renderScopeEnd);
            if (fitted != null) {
                selected.add(fitted);
                coalesceWindows(selected);
            }
        }

        if (selected.isEmpty()) {
            int fallbackLine = candidates.isEmpty()
                    ? requestedFirstIndex
                    : candidates.get(0).anchor();
            if (candidates.isEmpty() && !structuralFallback.isEmpty()) {
                return boundedStructuralAnchors(lines, structuralFallback, budget, sourceStart);
            }
            return new Excerpt(
                    boundedLine(lines.get(fallbackLine), budget),
                    "DIRECT_READ_REQUESTED_RANGE_BOUNDED",
                    false,
                    true,
                    sourceStart + fallbackLine,
                    sourceStart + fallbackLine
            );
        }

        selected = merge(selected);
        String text = renderRequestedWindows(
                lines, selected, sourceStart, renderScopeStart, renderScopeEnd);
        return new Excerpt(
                text,
                "DIRECT_READ_REQUESTED_RANGE_BOUNDED",
                false,
                true,
                sourceStart + selected.get(0).start(),
                sourceStart + selected.get(selected.size() - 1).end()
        );
    }

    /**
     * Samples actual call-site lines across the whole direct-read body (head, tail, midpoint, then
     * finer midpoints). This preserves an execution skeleton when question vocabulary does not occur
     * in source identifiers, while the shared lexical scanner excludes comments, literals, control
     * keywords, and declarations.
     */
    private static List<RequestedWindow> behavioralCallWindows(
            CodeSearchResult result,
            List<String> lines,
            int scopeStart,
            int scopeEnd,
            Map<String, Double> terms,
            Map<String, Integer> frequency,
            boolean[] comments
    ) {
        if (lines == null || lines.isEmpty() || scopeEnd < scopeStart) return List.of();
        String content = String.join("\n", lines);
        String callable = result == null ? "" : result.methodName();
        List<CodeLexicalCalls.CallSite> calls = CodeLexicalCalls.scan(content, callable).stream()
                .filter(call -> call.lineOffset() >= scopeStart && call.lineOffset() <= scopeEnd)
                .toList();
        if (calls.isEmpty()) return List.of();
        LinkedHashMap<String, CodeLexicalCalls.CallSite> bySymbol = new LinkedHashMap<>();
        for (CodeLexicalCalls.CallSite call : calls) {
            bySymbol.putIfAbsent(call.symbol().toLowerCase(Locale.ROOT), call);
        }
        List<CodeLexicalCalls.CallSite> distinctCalls = List.copyOf(bySymbol.values());
        List<CodeLexicalCalls.CallSite> relevantCalls = distinctCalls.stream()
                .filter(call -> windowScore(
                        lines, call.lineOffset(), call.lineOffset(), terms, frequency, comments) > 0)
                .sorted(Comparator.comparingInt(CodeLexicalCalls.CallSite::lineOffset))
                .toList();
        List<RequestedWindow> windows = new ArrayList<>();
        if (!relevantCalls.isEmpty()) {
            LinkedHashMap<CodeLexicalCalls.CallSite, Boolean> prioritized = new LinkedHashMap<>();
            List<CodeLexicalCalls.CallSite> callableFamily = distinctCalls.stream()
                    .filter(call -> sharesCallableToken(call, callable))
                    .toList();
            CodeLexicalCalls.coverageOrder(callableFamily.size()).stream()
                    .limit(3)
                    .map(callableFamily::get)
                    .forEach(call -> prioritized.put(call, true));
            for (int index : CodeLexicalCalls.coverageOrder(relevantCalls.size())) {
                prioritized.put(relevantCalls.get(index), true);
            }
            relevantCalls.forEach(call -> prioritized.put(call, true));
            for (CodeLexicalCalls.CallSite call : prioritized.keySet()) {
                int line = call.lineOffset();
                windows.add(new RequestedWindow(
                        new Window(line, line, Double.MAX_VALUE), line));
            }
        } else {
            for (int index : CodeLexicalCalls.coverageOrder(distinctCalls.size())) {
                int line = distinctCalls.get(index).lineOffset();
                windows.add(new RequestedWindow(
                        new Window(line, line, Double.MAX_VALUE), line));
            }
        }
        return List.copyOf(windows);
    }

    private static boolean sharesCallableToken(CodeLexicalCalls.CallSite call, String callable) {
        if (call == null || callable == null || callable.isBlank()) return false;
        Set<String> callableTokens = identifierTokens(callable);
        Set<String> calleeTokens = identifierTokens(call.symbol());
        return !callableTokens.isEmpty() && callableTokens.stream().anyMatch(calleeTokens::contains);
    }

    private static Set<String> identifierTokens(String value) {
        if (value == null || value.isBlank()) return Set.of();
        String split = value.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .toLowerCase(Locale.ROOT);
        return java.util.Arrays.stream(split.split("\\s+"))
                .filter(token -> token.length() >= 3)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static Excerpt boundedStructuralAnchors(
            List<String> lines,
            List<Window> anchors,
            int budget,
            int sourceStart
    ) {
        int safeBudget = Math.max(1, budget);
        List<Window> selected = anchors == null || anchors.isEmpty()
                ? List.of(new Window(0, 0, Double.MAX_VALUE))
                : merge(anchors);
        Window first = selected.get(0);
        Window last = selected.get(selected.size() - 1);
        String firstText = String.join("\n", lines.subList(first.start(), first.end() + 1));
        String rendered;
        if (first.equals(last)) {
            rendered = boundedHeadTail(firstText, safeBudget);
        } else {
            String separator = "\n...\n";
            int available = Math.max(0, safeBudget - separator.length());
            int firstBudget = Math.max(1, available * 45 / 100);
            int lastBudget = Math.max(1, available - firstBudget);
            String lastText = String.join("\n", lines.subList(last.start(), last.end() + 1));
            rendered = boundedHeadTail(firstText, firstBudget)
                    + separator
                    + boundedHeadTail(lastText, lastBudget);
            if (rendered.length() > safeBudget) rendered = boundedHeadTail(rendered, safeBudget);
        }
        return new Excerpt(
                rendered,
                "DIRECT_READ_REQUESTED_RANGE_BOUNDED",
                false,
                true,
                sourceStart + first.start(),
                sourceStart + last.end());
    }

    private static String boundedHeadTail(String value, int budget) {
        String safe = value == null ? "" : value;
        int safeBudget = Math.max(1, budget);
        if (safe.length() <= safeBudget) return safe;
        String marker = " ... ";
        if (safeBudget <= marker.length() + 2) return boundedLine(safe, safeBudget);
        int available = safeBudget - marker.length();
        int head = (available + 1) / 2;
        int tail = available - head;
        return safe.substring(0, head).stripTrailing()
                + marker
                + safe.substring(safe.length() - tail).stripLeading();
    }

    private static Window fitRequestedWindow(
            List<String> lines,
            List<Window> selected,
            RequestedWindow candidate,
            int budget,
            int sourceStart,
            int scopeStart,
            int scopeEnd
    ) {
        Window full = candidate.window();
        if (fitsRequestedWindows(lines, selected, full, budget, sourceStart, scopeStart, scopeEnd)) return full;

        int anchor = candidate.anchor();
        Window fitted = new Window(anchor, anchor, full.score());
        if (!fitsRequestedWindows(lines, selected, fitted, budget, sourceStart, scopeStart, scopeEnd)) return null;

        boolean expanded;
        do {
            expanded = false;
            if (fitted.start() > full.start()) {
                Window left = new Window(fitted.start() - 1, fitted.end(), fitted.score());
                if (fitsRequestedWindows(lines, selected, left, budget, sourceStart, scopeStart, scopeEnd)) {
                    fitted = left;
                    expanded = true;
                }
            }
            if (fitted.end() < full.end()) {
                Window right = new Window(fitted.start(), fitted.end() + 1, fitted.score());
                if (fitsRequestedWindows(lines, selected, right, budget, sourceStart, scopeStart, scopeEnd)) {
                    fitted = right;
                    expanded = true;
                }
            }
        } while (expanded && (fitted.start() > full.start() || fitted.end() < full.end()));
        return fitted;
    }

    private static boolean fitsRequestedWindows(
            List<String> lines,
            List<Window> selected,
            Window candidate,
            int budget,
            int sourceStart,
            int scopeStart,
            int scopeEnd
    ) {
        List<Window> combined = new ArrayList<>(selected);
        combined.add(candidate);
        return renderRequestedWindows(
                lines, merge(combined), sourceStart, scopeStart, scopeEnd).length() <= budget;
    }

    private static String renderRequestedWindows(
            List<String> lines,
            List<Window> selected,
            int sourceStart,
            int scopeStart,
            int scopeEnd
    ) {
        StringBuilder text = new StringBuilder();
        int previousEnd = -1;
        for (Window window : selected) {
            int omittedStart = previousEnd < 0 ? scopeStart : previousEnd + 1;
            if (window.start() > omittedStart) {
                if (!text.isEmpty()) text.append('\n');
                text.append("... lines ")
                        .append(sourceStart + omittedStart)
                        .append('-')
                        .append(sourceStart + window.start() - 1)
                        .append(" omitted by prompt budget ...\n");
            }
            if (!text.isEmpty() && previousEnd >= 0 && window.start() == previousEnd + 1) {
                text.append('\n');
            }
            text.append(String.join("\n", lines.subList(window.start(), window.end() + 1)));
            previousEnd = window.end();
        }
        if (previousEnd >= 0 && previousEnd < scopeEnd) {
            text.append("\n... lines ")
                    .append(sourceStart + previousEnd + 1)
                    .append('-')
                    .append(sourceStart + scopeEnd)
                    .append(" omitted by prompt budget ...");
        }
        return text.toString().stripTrailing();
    }

    private static String boundedLine(String value, int budget) {
        String safe = value == null ? "" : value;
        if (safe.length() <= budget) {
            return safe;
        }
        if (budget <= 3) {
            return ".".repeat(budget);
        }
        return safe.substring(0, budget - 3).stripTrailing() + "...";
    }

    private static Excerpt boundedDirectRead(
            String question,
            CodeSearchResult result,
            List<String> lines,
            int budget,
            int sourceStart,
            int sourceEnd
    ) {
        int actualEnd = actualLineEnd(sourceStart, sourceEnd, lines.size());
        if (lines.size() <= 1) {
            return new Excerpt(
                    boundedLine(String.join("\n", lines), budget),
                    "DIRECT_READ_ANCHORED",
                    false,
                    true,
                    sourceStart,
                    actualEnd);
        }
        Excerpt anchored = boundedRequestedDirectRead(
                question, result, lines, budget, sourceStart, 0, lines.size() - 1);
        return new Excerpt(
                anchored.text(),
                "DIRECT_READ_ANCHORED",
                false,
                true,
                anchored.lineStart(),
                anchored.lineEnd());
    }

    private static int windowChars(List<String> lines, Window window) {
        return lines.subList(window.start(), window.end() + 1).stream()
                .mapToInt(String::length)
                .sum() + Math.max(0, window.end() - window.start());
    }

    private static boolean coveredBySelected(List<Window> selected, Window candidate) {
        return selected != null && candidate != null && selected.stream().anyMatch(current ->
                candidate.start() >= current.start() && candidate.end() <= current.end());
    }

    private static void coalesceWindows(List<Window> selected) {
        if (selected == null || selected.size() < 2) return;
        List<Window> merged = merge(selected);
        selected.clear();
        selected.addAll(merged);
    }

    private static Map<String, Double> structuralIntentTerms(String question, CodeSearchResult result) {
        Map<String, Double> terms = new LinkedHashMap<>();
        addTerms(terms, question, 6.0);
        if (result != null && result.metadata() != null) {
            if (!isDirectRead(result)) {
                addMetadataTerms(terms, result.metadata().get("llmChecklistGoal"), 10.0);
                addMetadataTerms(terms, result.metadata().get("llmSearchPlanQuery"), 9.0);
                addMetadataTerms(terms, result.metadata().get("llmFollowUpQuery"), 5.0);
                addMetadataTerms(terms, result.metadata().get("llmReadArea"), 6.0);
            }
            addMetadataTerms(terms, result.metadata().get("llmRequestedSymbol"), 12.0);
            addMetadataTerms(terms, result.metadata().get("llmRequestedPath"), 4.0);
        }
        return terms;
    }

    private static boolean hasRelevantLineOutsideAnchors(
            List<String> lines,
            int scopeStart,
            int scopeEnd,
            List<Window> anchors,
            Map<String, Double> terms,
            Map<String, Integer> frequency,
            boolean[] comments
    ) {
        if (terms == null || terms.isEmpty()) return false;
        for (int index = Math.max(0, scopeStart); index <= Math.min(scopeEnd, lines.size() - 1); index++) {
            int candidate = index;
            boolean insideAnchor = anchors.stream()
                    .anyMatch(anchor -> candidate >= anchor.start() && candidate <= anchor.end());
            if (!insideAnchor && windowScore(lines, index, index, terms, frequency, comments) > 0) return true;
        }
        return false;
    }

    private static Window owningStructuralAnchor(
            List<String> lines,
            List<Window> anchors,
            int relevantLine
    ) {
        Window owner = null;
        for (Window anchor : anchors == null ? List.<Window>of() : anchors) {
            if (anchor.start() > relevantLine) break;
            owner = anchor;
        }
        if (owner != null && lexicallyOwns(lines, owner, relevantLine)) return owner;

        // The relevant line may belong to a differently named sibling or nested declaration. Pair it
        // with the nearest enclosing lexical header only when that scope can be proven from source.
        for (int index = relevantLine - 1; index >= 0; index--) {
            Window candidate = lexicalHeaderWindow(lines, index);
            if (lexicallyOwns(lines, candidate, relevantLine)) return candidate;
        }
        return null;
    }

    private static Window lexicalHeaderWindow(List<String> lines, int openerLine) {
        if (lines == null || openerLine < 0 || openerLine >= lines.size()) {
            return new Window(Math.max(0, openerLine), Math.max(0, openerLine), Double.MAX_VALUE);
        }
        int parenthesisDepth = 0;
        boolean sawClosingParenthesis = false;
        int minimumLine = Math.max(0, openerLine - 32);
        for (int lineIndex = openerLine; lineIndex >= minimumLine; lineIndex--) {
            String line = lines.get(lineIndex) == null ? "" : lines.get(lineIndex);
            for (int column = line.length() - 1; column >= 0; column--) {
                char current = line.charAt(column);
                if (current == ')') {
                    parenthesisDepth++;
                    sawClosingParenthesis = true;
                } else if (current == '(' && parenthesisDepth > 0) {
                    parenthesisDepth--;
                    if (parenthesisDepth == 0 && sawClosingParenthesis) {
                        return new Window(lineIndex, openerLine, Double.MAX_VALUE);
                    }
                }
            }
        }
        return new Window(openerLine, openerLine, Double.MAX_VALUE);
    }

    /**
     * Proves ownership from lexical scope instead of assuming that the nearest same-name declaration
     * owns every following line. Unknown or nested scope shapes deliberately fail closed.
     */
    private static boolean lexicallyOwns(List<String> lines, Window owner, int relevantLine) {
        if (lines == null || owner == null || owner.start() < 0
                || owner.start() >= lines.size() || relevantLine <= owner.start()
                || relevantLine >= lines.size()) {
            return false;
        }
        if (braceScopeOwns(lines, owner.start(), relevantLine)) {
            return true;
        }
        return indentationScopeOwns(lines, owner, relevantLine);
    }

    private static boolean braceScopeOwns(List<String> lines, int declarationLine, int relevantLine) {
        LexicalBraceState state = new LexicalBraceState();
        for (int index = declarationLine; index < relevantLine; index++) {
            state.scan(lines.get(index));
            if (state.closedOwner()) return false;
        }
        if (!state.openedOwner() || state.depth() != 1) return false;

        // A relevant line that opens/closes another block is ambiguous without a parser. Fail closed.
        LexicalBraceState relevantState = state.copy();
        relevantState.scan(lines.get(relevantLine));
        return !relevantState.sawBrace();
    }

    private static boolean indentationScopeOwns(
            List<String> lines,
            Window owner,
            int relevantLine
    ) {
        int declarationIndent = indentation(lines.get(owner.start()));
        boolean colonBody = false;
        for (int index = owner.start(); index <= Math.min(owner.end(), lines.size() - 1); index++) {
            String stripped = lines.get(index) == null ? "" : lines.get(index).stripTrailing();
            if (stripped.endsWith(":")) {
                colonBody = true;
                break;
            }
        }
        if (!colonBody) return false;

        Integer bodyIndent = null;
        for (int index = owner.start() + 1; index <= relevantLine; index++) {
            String line = lines.get(index) == null ? "" : lines.get(index);
            if (line.isBlank()) continue;
            int currentIndent = indentation(line);
            if (currentIndent <= declarationIndent) return false;
            if (bodyIndent == null) bodyIndent = currentIndent;
            if (index == relevantLine) {
                // Deeper indentation may be a nested declaration/control scope; do not guess ownership.
                return currentIndent == bodyIndent;
            }
        }
        return false;
    }

    private static int indentation(String line) {
        int width = 0;
        String value = line == null ? "" : line;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == ' ') {
                width++;
            } else if (current == '\t') {
                width += 4 - (width % 4);
            } else {
                break;
            }
        }
        return width;
    }

    private static final class LexicalBraceState {
        private int depth;
        private boolean openedOwner;
        private boolean closedOwner;
        private boolean blockComment;
        private boolean sawBrace;

        private LexicalBraceState() {
        }

        private LexicalBraceState(LexicalBraceState source) {
            this.depth = source.depth;
            this.openedOwner = source.openedOwner;
            this.closedOwner = source.closedOwner;
            this.blockComment = source.blockComment;
        }

        private void scan(String line) {
            sawBrace = false;
            String value = line == null ? "" : line;
            boolean singleQuote = false;
            boolean doubleQuote = false;
            boolean escaped = false;
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                char next = index + 1 < value.length() ? value.charAt(index + 1) : '\0';
                if (blockComment) {
                    if (current == '*' && next == '/') {
                        blockComment = false;
                        index++;
                    }
                    continue;
                }
                if (!singleQuote && !doubleQuote && current == '/' && next == '/') break;
                if (!singleQuote && !doubleQuote && current == '/' && next == '*') {
                    blockComment = true;
                    index++;
                    continue;
                }
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if ((singleQuote || doubleQuote) && current == '\\') {
                    escaped = true;
                    continue;
                }
                if (!doubleQuote && current == '\'') {
                    singleQuote = !singleQuote;
                    continue;
                }
                if (!singleQuote && current == '"') {
                    doubleQuote = !doubleQuote;
                    continue;
                }
                if (singleQuote || doubleQuote) continue;
                if (current == '{') {
                    sawBrace = true;
                    openedOwner = true;
                    depth++;
                } else if (current == '}') {
                    sawBrace = true;
                    if (openedOwner && depth > 0 && --depth == 0) closedOwner = true;
                }
            }
        }

        private LexicalBraceState copy() {
            return new LexicalBraceState(this);
        }

        private int depth() {
            return depth;
        }

        private boolean openedOwner() {
            return openedOwner;
        }

        private boolean closedOwner() {
            return closedOwner;
        }

        private boolean sawBrace() {
            return sawBrace;
        }
    }

    private static Excerpt ownedRelevantExcerpt(
            List<String> lines,
            Window owner,
            int relevantLine,
            int budget,
            int sourceStart,
            int scopeStart,
            int scopeEnd
    ) {
        Window relevant = new Window(relevantLine, relevantLine, Double.MAX_VALUE);
        List<Window> pair = merge(List.of(owner, relevant));
        String rendered = renderRequestedWindows(lines, pair, sourceStart, scopeStart, scopeEnd);
        if (rendered.length() > budget) {
            String separator = "\n...\n";
            int available = Math.max(0, budget - separator.length());
            int ownerBudget = Math.max(1, available * 55 / 100);
            int relevantBudget = Math.max(1, available - ownerBudget);
            String ownerText = String.join("\n", lines.subList(owner.start(), owner.end() + 1));
            rendered = boundedHeadTail(ownerText, ownerBudget)
                    + separator
                    + boundedHeadTail(lines.get(relevantLine), relevantBudget);
            if (rendered.length() > budget) rendered = boundedHeadTail(rendered, budget);
        }
        return new Excerpt(
                rendered,
                "DIRECT_READ_REQUESTED_RANGE_BOUNDED",
                false,
                true,
                sourceStart + owner.start(),
                sourceStart + relevantLine);
    }

    /**
     * Keeps the declaration and the beginning of its implementation visible before relevance windows
     * consume the remaining prompt budget. This is language-neutral for brace/arrow based languages and
     * still preserves a declaration line for other parsers.
     */
    private static Window structuralAnchorWindow(
            String question,
            CodeSearchResult result,
            List<String> lines,
            int scopeStart,
            int scopeEnd,
            int budget
    ) {
        List<Window> anchors = structuralAnchorWindows(result, lines, scopeStart, scopeEnd, budget);
        if (anchors.isEmpty()) return null;
        Map<String, Double> terms = weightedTerms(question, result);
        anchors = preferredStructuralAnchors(
                anchors,
                lines,
                terms,
                documentFrequency(lines, terms.keySet()),
                commentLines(lines));
        return new Window(
                anchors.get(0).start(),
                anchors.get(anchors.size() - 1).end(),
                Double.MAX_VALUE);
    }

    /**
     * Prefer the declaration whose signature/body is most relevant to the requested identity and question.
     * When candidates are genuinely indistinguishable, retain both source boundaries so a later bounded
     * rendering cannot silently assume that the first same-name occurrence is the definition.
     */
    private static List<Window> preferredStructuralAnchors(
            List<Window> anchors,
            List<String> lines,
            Map<String, Double> terms,
            Map<String, Integer> frequency,
            boolean[] comments
    ) {
        if (anchors == null || anchors.size() <= 1) {
            return anchors == null ? List.of() : List.copyOf(anchors);
        }
        List<Window> ranked = anchors.stream()
                .map(anchor -> new Window(
                        anchor.start(),
                        anchor.end(),
                        windowScore(lines, anchor.start(), anchor.end(), terms, frequency, comments)))
                .sorted(Comparator.comparingDouble(Window::score).reversed()
                        .thenComparingInt(Window::start))
                .toList();
        if (ranked.get(0).score() > ranked.get(1).score() + 0.000_001d) {
            return List.of(ranked.get(0));
        }
        Window first = anchors.get(0);
        Window last = anchors.get(anchors.size() - 1);
        return first.equals(last) ? List.of(first) : List.of(first, last);
    }

    private static List<Window> structuralAnchorWindows(
            CodeSearchResult result,
            List<String> lines,
            int scopeStart,
            int scopeEnd,
            int budget
    ) {
        if (result == null || lines == null || lines.isEmpty() || scopeEnd < scopeStart) {
            return List.of();
        }
        List<String> identifiers = java.util.stream.Stream.of(
                        result.methodName(), result.symbolName(), result.className())
                .filter(value -> value != null && !value.isBlank())
                .map(EvidenceExcerptSelector::canonicalIdentifier)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        for (String identifier : identifiers) {
            Window fallback = null;
            List<Window> anchors = new ArrayList<>();
            Pattern declaration = Pattern.compile(
                    "(^|[^\\p{L}\\p{N}_])" + Pattern.quote(identifier)
                            + (identifier.equals(canonicalIdentifier(result.methodName()))
                            ? "\\s*\\(" : "([^\\p{L}\\p{N}_]|$)"));
            for (int index = Math.max(0, scopeStart); index <= Math.min(scopeEnd, lines.size() - 1); index++) {
                Matcher matcher = declaration.matcher(lines.get(index));
                if (!matcher.find()) {
                    continue;
                }
                int identifierStart = lines.get(index).indexOf(identifier, matcher.start());
                if (looksLikeInvocation(lines.get(index), identifierStart)) continue;
                int scanEnd = Math.min(scopeEnd, lines.size() - 1);
                int bodyEnd = declarationBodyEnd(
                        lines,
                        index,
                        identifierStart + identifier.length(),
                        scanEnd,
                        budget,
                        identifier.equals(canonicalIdentifier(result.methodName())));
                if (bodyEnd == DECLARATION_TERMINATED) continue;
                if (fallback == null) fallback = new Window(index, index, Double.MAX_VALUE);
                if (bodyEnd >= index) anchors.add(new Window(index, bodyEnd, Double.MAX_VALUE));
            }
            if (!anchors.isEmpty()) return List.copyOf(anchors);
            if (fallback != null) return List.of(fallback);
        }
        return List.of();
    }

    private static final int DECLARATION_TERMINATED = -2;

    private static int declarationBodyEnd(
            List<String> lines,
            int startLine,
            int startColumn,
            int scanEnd,
            int budget,
            boolean requiresParameterList
    ) {
        int parenthesisDepth = 0;
        boolean sawParameterList = false;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;
        int scannedChars = 0;
        for (int cursor = startLine; cursor <= scanEnd; cursor++) {
            String line = lines.get(cursor);
            int from = cursor == startLine ? Math.min(startColumn, line.length()) : 0;
            scannedChars += Math.max(0, line.length() - from) + 1;
            if (scannedChars > Math.max(1, budget)) break;
            for (int index = from; index < line.length(); index++) {
                char current = line.charAt(index);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if ((inSingleQuote || inDoubleQuote) && current == '\\') {
                    escaped = true;
                    continue;
                }
                if (!inDoubleQuote && current == '\'') {
                    inSingleQuote = !inSingleQuote;
                    continue;
                }
                if (!inSingleQuote && current == '"') {
                    inDoubleQuote = !inDoubleQuote;
                    continue;
                }
                if (inSingleQuote || inDoubleQuote) continue;
                if (current == '(') {
                    parenthesisDepth++;
                    sawParameterList = true;
                    continue;
                }
                if (current == ')') {
                    if (parenthesisDepth > 0) parenthesisDepth--;
                    continue;
                }
                if (parenthesisDepth > 0) continue;
                boolean eligible = !requiresParameterList || sawParameterList;
                if (eligible && current == '{') {
                    return Math.min(scanEnd, cursor + 1);
                }
                if (eligible && current == '=' && index + 1 < line.length() && line.charAt(index + 1) == '>') {
                    return Math.min(scanEnd, cursor + 1);
                }
                if (eligible && current == ':') {
                    return line.substring(index + 1).isBlank()
                            ? Math.min(scanEnd, cursor + 1)
                            : DECLARATION_TERMINATED;
                }
                if (current == ';') {
                    return DECLARATION_TERMINATED;
                }
            }
        }
        return -1;
    }

    private static boolean looksLikeInvocation(String line, int identifierStart) {
        if (line == null || identifierStart <= 0) return false;
        int cursor = identifierStart - 1;
        while (cursor >= 0 && Character.isWhitespace(line.charAt(cursor))) cursor--;
        if (cursor < 0) return false;
        char preceding = line.charAt(cursor);
        if (".:(=,!&|?(".indexOf(preceding) >= 0) return true;
        String prefix = line.substring(0, identifierStart).stripTrailing();
        return prefix.endsWith("new") || INVOCATION_PREFIX.matcher(prefix).matches();
    }

    private static String canonicalIdentifier(String value) {
        String identifier = value == null ? "" : value.trim();
        int parameters = identifier.indexOf('(');
        if (parameters >= 0) identifier = identifier.substring(0, parameters);
        identifier = identifier.replace("::", ".").replace('#', '.');
        int separator = identifier.lastIndexOf('.');
        if (separator >= 0 && separator + 1 < identifier.length()) {
            identifier = identifier.substring(separator + 1);
        }
        int generic = identifier.indexOf('<');
        return (generic > 0 ? identifier.substring(0, generic) : identifier).trim();
    }

    private static List<String> sourceLines(String content, int sourceStart, int sourceEnd) {
        List<String> lines = new ArrayList<>(contentLines(content));
        List<String> envelopedLines = canonicalEnvelopeSourceLines(lines, sourceStart, sourceEnd);
        if (!envelopedLines.isEmpty()) {
            return envelopedLines;
        }
        int expectedLines = Math.max(1, sourceEnd - sourceStart + 1);
        if (lines.size() > expectedLines) {
            return List.copyOf(lines.subList(0, expectedLines));
        }
        return lines.isEmpty() ? List.of("") : List.copyOf(lines);
    }

    private static List<String> canonicalEnvelopeSourceLines(
            List<String> contentLines,
            int sourceStart,
            int sourceEnd
    ) {
        boolean matchingRangeHeader = false;
        for (String line : contentLines) {
            Matcher header = SOURCE_RANGE_HEADER.matcher(line);
            if (!header.matches()) {
                continue;
            }
            try {
                matchingRangeHeader = Integer.parseInt(header.group(1)) == sourceStart
                        && Integer.parseInt(header.group(2)) == sourceEnd;
            } catch (NumberFormatException ignored) {
                return List.of();
            }
            break;
        }
        if (!matchingRangeHeader) {
            return List.of();
        }

        Map<Integer, String> numberedLines = new LinkedHashMap<>();
        for (String line : contentLines) {
            Matcher numbered = NUMBERED_SOURCE_LINE.matcher(line);
            if (!numbered.matches()) {
                continue;
            }
            try {
                int lineNumber = Integer.parseInt(numbered.group(1));
                if (lineNumber >= sourceStart && lineNumber <= sourceEnd) {
                    numberedLines.putIfAbsent(lineNumber, line.stripTrailing());
                }
            } catch (NumberFormatException ignored) {
                return List.of();
            }
        }

        List<String> sourceLines = new ArrayList<>();
        for (int lineNumber = sourceStart; lineNumber <= sourceEnd; lineNumber++) {
            String line = numberedLines.get(lineNumber);
            if (line == null) {
                return List.of();
            }
            sourceLines.add(line);
        }
        return List.copyOf(sourceLines);
    }

    private static List<String> contentLines(String content) {
        String normalized = content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
        if (lines.size() > 1 && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
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
            if (!isDirectRead(result)) {
                addMetadataTerms(terms, result.metadata() == null ? null : result.metadata().get("llmSupportedClaims"), 7.0);
                addMetadataTerms(terms, result.metadata() == null ? null : result.metadata().get("llmNotSupportedClaims"), 4.0);
                addMetadataTerms(terms, result.metadata() == null ? null : result.metadata().get("llmFollowUpQuery"), 5.0);
                addMetadataTerms(terms, result.metadata() == null ? null : result.metadata().get("llmChecklistGoal"), 10.0);
                addMetadataTerms(terms, result.metadata() == null ? null : result.metadata().get("llmSearchPlanQuery"), 9.0);
                addMetadataTerms(terms, result.metadata() == null ? null : result.metadata().get("llmReadArea"), 6.0);
            }
            addMetadataTerms(terms, result.metadata() == null ? null : result.metadata().get("llmRequestedSymbol"), 12.0);
            addMetadataTerms(terms, result.metadata() == null ? null : result.metadata().get("llmRequestedPath"), 4.0);
            addProvenanceTerms(terms, result, 10.0);
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
        int safeBudget = Math.max(1, budget);
        String omissionSuffix = safeBudget >= 4 ? "\n..." : ".".repeat(safeBudget);
        int contentBudget = Math.max(0, safeBudget - omissionSuffix.length());
        List<String> lines = content == null ? List.of() : content.lines().toList();
        StringBuilder rendered = new StringBuilder();
        int renderedEnd = -1;
        for (int index = 0; index < lines.size(); index++) {
            String prefix = rendered.isEmpty() ? "" : "\n";
            String line = lines.get(index);
            if (rendered.length() + prefix.length() + line.length() > contentBudget) break;
            rendered.append(prefix).append(line);
            renderedEnd = index;
        }
        if (renderedEnd < 0 && !lines.isEmpty() && contentBudget > 0) {
            rendered.append(boundedLine(lines.get(0), contentBudget));
            renderedEnd = 0;
        }
        String text = rendered.toString().stripTrailing() + omissionSuffix;
        return new Excerpt(text, "TRUNCATED_CHUNK", false, true, sourceStart,
                sourceStart + Math.max(0, renderedEnd));
    }

    public record Excerpt(String text, String kind, boolean contentComplete, boolean omittedByBudget,
                          int lineStart, int lineEnd) {
    }

    private record Window(int start, int end, double score) {
    }

    private static void addProvenanceTerms(
            Map<String, Double> terms,
            CodeSearchResult result,
            double weight
    ) {
        for (CodeEvidenceOperationProvenance provenance : CodeEvidenceOperationProvenance.from(result)) {
            if (isSearchProvenance(provenance)) {
                addTerms(terms, provenance.query(), weight);
                addTerms(terms, provenance.evidenceGroup(), weight);
                provenance.claimIds().forEach(value -> addTerms(terms, value, weight * 0.6));
            }
            addTerms(terms, provenance.path(), weight * 0.4);
            addTerms(terms, provenance.symbol(), weight + 2.0);
        }
    }

    private static boolean isSearchProvenance(CodeEvidenceOperationProvenance provenance) {
        if (provenance == null) return false;
        return switch (provenance.operationType()) {
            case "keyword_search", "hybrid_search", "reference_search", "find_endpoint" -> true;
            default -> false;
        };
    }

    private record RequestedWindow(Window window, int anchor) {
    }

    private record RequestedRange(int lineStart, int lineEnd) {
    }

}
