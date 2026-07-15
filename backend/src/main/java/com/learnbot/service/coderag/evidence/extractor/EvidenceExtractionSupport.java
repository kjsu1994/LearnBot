package com.learnbot.service.coderag.evidence.extractor;

import com.learnbot.dto.CodeSearchResult;
import com.learnbot.service.CodeIntelligenceAuthority;
import com.learnbot.service.coderag.model.CodeEvidenceItem;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EvidenceExtractionSupport {
    private static final Pattern RELATION_TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9_]{2,}");

    private EvidenceExtractionSupport() {
    }

    static List<CodeSearchResult> bounded(List<CodeSearchResult> evidence, int limit) {
        if (evidence == null || evidence.isEmpty()) return List.of();
        return evidence.stream().filter(java.util.Objects::nonNull).limit(Math.max(1, limit)).toList();
    }

    static String metadata(CodeSearchResult result, String key) {
        Object value = metadataValue(result, key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    static Object metadataValue(CodeSearchResult result, String key) {
        Map<String, Object> metadata = result == null ? null : result.metadata();
        return metadata == null || key == null ? null : metadata.get(key);
    }

    static boolean metadataBoolean(CodeSearchResult result, String key) {
        String value = metadata(result, key);
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    static List<String> metadataValues(CodeSearchResult result, String key) {
        Object value = metadataValue(result, key);
        if (value == null) return List.of();
        if (value instanceof Iterable<?> iterable) {
            List<String> values = new ArrayList<>();
            for (Object item : iterable) {
                String safe = item == null ? "" : String.valueOf(item).trim();
                if (!safe.isBlank()) values.add(safe);
            }
            return List.copyOf(values);
        }
        String safe = String.valueOf(value).trim();
        return safe.isBlank() ? List.of() : List.of(safe);
    }

    static Set<String> relations(CodeSearchResult result) {
        LinkedHashSet<String> relations = new LinkedHashSet<>();
        addRelations(relations, metadataValues(result, "graphRelation"));
        addRelations(relations, metadataValues(result, "graphEdgeType"));
        addRelations(relations, metadataValues(result, "graphEdgeTypes"));
        return Set.copyOf(relations);
    }

    static boolean hasRelation(CodeSearchResult result, String relation) {
        return relation != null && relations(result).contains(relation.toUpperCase(Locale.ROOT));
    }

    static CodeIntelligenceAuthority authority(CodeSearchResult result) {
        return CodeEvidenceItem.authority(result);
    }

    static CodeIntelligenceAuthority directSyntaxAuthority(CodeSearchResult result) {
        CodeIntelligenceAuthority authority = authority(result);
        return authority.rank() >= CodeIntelligenceAuthority.SYNTAX.rank()
                ? authority : CodeIntelligenceAuthority.SYNTAX;
    }

    static CodeEvidenceItem item(CodeSearchResult result, CodeEvidenceItem.Kind... kinds) {
        return CodeEvidenceItem.from(result, kinds);
    }

    static String subject(CodeSearchResult result) {
        if (result == null) return "unknown-symbol";
        String method = safe(result.methodName());
        String type = firstNonBlank(result.className(), result.controlName(), result.namespaceName());
        if (!type.isBlank() && !method.isBlank()) return type + "." + method;
        String symbol = firstNonBlank(method, result.symbolName(), result.className(), result.controlName(),
                result.eventName(), result.filePath());
        return symbol.isBlank() ? "unknown-symbol" : symbol;
    }

    static String normalizeRoute(String value) {
        String route = safe(value).trim();
        if (route.isBlank()) return "";
        int query = route.indexOf('?');
        if (query >= 0) route = route.substring(0, query);
        int fragment = route.indexOf('#');
        if (fragment >= 0) route = route.substring(0, fragment);
        route = route.replace('\\', '/').replaceAll("/+", "/");
        return route.startsWith("/") ? route : "/" + route;
    }

    static String lastGraphNode(CodeSearchResult result) {
        List<String> nodes = metadataValues(result, "graphPathNodes");
        return nodes.isEmpty() ? "" : nodes.get(nodes.size() - 1);
    }

    static String firstMetadata(CodeSearchResult result, String... keys) {
        if (keys == null) return "";
        for (String key : keys) {
            String value = metadata(result, key);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    static String truncate(String value, int maxChars) {
        String safe = safe(value).trim();
        if (safe.length() <= Math.max(0, maxChars)) return safe;
        return safe.substring(0, Math.max(0, maxChars));
    }

    static int lineAtOffset(CodeSearchResult result, int offset) {
        int line = result == null ? 0 : Math.max(0, result.lineStart());
        String content = result == null ? "" : safe(result.content());
        for (int i = 0; i < Math.min(Math.max(0, offset), content.length()); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static void addRelations(Set<String> output, List<String> values) {
        for (String value : values) {
            Matcher matcher = RELATION_TOKEN.matcher(value);
            while (matcher.find()) {
                String token = matcher.group().toUpperCase(Locale.ROOT);
                if (token.contains("_") || token.equals("CALLS")) output.add(token);
            }
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
