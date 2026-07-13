package com.learnbot.service;

import com.learnbot.dto.CodeSearchResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CodeEvidenceFactFidelity {
    private static final Pattern LITERAL_ASSIGNMENT = Pattern.compile(
            "(?m)^\\s*(?:\\d+:\\s*)?([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+)\\s*=\\s*(true|false|null|-?\\d+(?:\\.\\d+)?|\"[^\"\\r\\n]{0,80}\")\\s*;");
    private static final int MAX_FACTS = 24;

    private CodeEvidenceFactFidelity() {
    }

    static String promptFacts(String question, List<CodeSearchResult> evidence) {
        LinkedHashSet<String> facts = new LinkedHashSet<>();
        List<CodeSearchResult> endpoints = endpointCandidates(question, evidence);
        for (CodeSearchResult endpoint : endpoints.stream().limit(3).toList()) {
            String route = metadata(endpoint, "endpointRoute");
            String method = metadata(endpoint, "httpMethod");
            if (!route.isBlank()) facts.add((method.isBlank() ? "endpoint" : method) + " " + route);
        }
        if (evidence != null) {
            for (int index = 0; index < evidence.size() && facts.size() < MAX_FACTS; index++) {
                CodeSearchResult result = evidence.get(index);
                for (Assignment assignment : assignments(result)) {
                    facts.add("[" + (index + 1) + "] `" + assignment.statement() + "`");
                    if (facts.size() >= MAX_FACTS) break;
                }
            }
        }
        if (facts.isEmpty()) return "";
        return "\n\nExact code facts from selected evidence. Preserve relevant routes and state transitions verbatim:\n- "
                + String.join("\n- ", facts);
    }

    static String missingReason(String question, String answer, List<CodeSearchResult> evidence) {
        String safeAnswer = answer == null ? "" : answer;
        List<CodeSearchResult> endpoints = endpointCandidates(question, evidence);
        if (!endpoints.isEmpty()) {
            String route = metadata(endpoints.get(0), "endpointRoute");
            if (!route.isBlank() && !containsNormalized(safeAnswer, route)) {
                return "missing exact endpoint route visible in evidence: " + route;
            }
        }

        Map<String, LinkedHashSet<Assignment>> transitions = new LinkedHashMap<>();
        if (evidence != null) {
            for (CodeSearchResult result : evidence) {
                for (Assignment assignment : assignments(result)) {
                    transitions.computeIfAbsent(assignment.left().toLowerCase(Locale.ROOT), ignored -> new LinkedHashSet<>())
                            .add(assignment);
                }
            }
        }
        for (Map.Entry<String, LinkedHashSet<Assignment>> entry : transitions.entrySet()) {
            Set<String> values = entry.getValue().stream().map(Assignment::value).collect(java.util.stream.Collectors.toSet());
            if (values.size() < 2 || !containsNormalized(safeAnswer, entry.getKey())) continue;
            for (Assignment assignment : entry.getValue()) {
                if (!containsNormalized(safeAnswer, assignment.left() + " = " + assignment.value())) {
                    return "missing exact state-transition assignment visible in evidence: " + assignment.statement();
                }
            }
        }
        return null;
    }

    private static List<CodeSearchResult> endpointCandidates(String question, List<CodeSearchResult> evidence) {
        if (evidence == null || evidence.isEmpty()) return List.of();
        List<CodeSearchResult> endpoints = evidence.stream()
                .filter(result -> metadataBoolean(result, "deterministicEndpointBestMatch"))
                .filter(result -> !metadata(result, "endpointRoute").isBlank())
                .toList();
        if (endpoints.isEmpty()) return List.of();
        List<CodeSearchResult> ranked = CodeEndpointQueryVariants.rankCandidates(question, endpoints, endpoints.size());
        return ranked.isEmpty() ? List.of() : ranked;
    }

    private static List<Assignment> assignments(CodeSearchResult result) {
        if (result == null || result.content() == null || result.content().isBlank()) return List.of();
        ArrayList<Assignment> output = new ArrayList<>();
        Matcher matcher = LITERAL_ASSIGNMENT.matcher(result.content());
        while (matcher.find() && output.size() < 6) {
            String left = matcher.group(1).trim();
            String value = matcher.group(2).trim();
            output.add(new Assignment(left, value, left + " = " + value + ";"));
        }
        return output;
    }

    private static String metadata(CodeSearchResult result, String key) {
        if (result == null || result.metadata() == null) return "";
        Object value = result.metadata().get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean metadataBoolean(CodeSearchResult result, String key) {
        String value = metadata(result, key);
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private static boolean containsNormalized(String text, String expected) {
        return normalize(text).contains(normalize(expected));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private record Assignment(String left, String value, String statement) {
    }
}
