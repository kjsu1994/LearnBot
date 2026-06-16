package com.learnbot.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CodeChunkParser {
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("^\\s*namespace\\s+([A-Za-z_][A-Za-z0-9_.]*)");
    private static final Pattern CLASS_PATTERN = Pattern.compile("\\b(?:class|struct|interface|enum)\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "^\\s*(?:\\[[^]]+\\]\\s*)*(?:(?:public|private|protected|internal|static|virtual|override|async|sealed|partial|extern|new|unsafe)\\s+)+[A-Za-z_][A-Za-z0-9_<>,.?\\[\\]\\s]*\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\([^;]*\\)\\s*(?:\\{|=>)?"
    );
    private static final Pattern CONSTRUCTOR_PATTERN = Pattern.compile(
            "^\\s*(?:public|private|protected|internal)\\s+([A-Z][A-Za-z0-9_]*)\\s*\\([^;]*\\)\\s*(?:\\{|=>)?"
    );
    private static final Pattern XAML_CLASS_PATTERN = Pattern.compile("x:Class\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern XAML_NAME_PATTERN = Pattern.compile("(?:x:Name|Name)\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern XAML_EVENT_PATTERN = Pattern.compile("\\b(Click|Loaded|SelectionChanged|TextChanged|Checked|Unchecked|MouseDown|MouseUp|KeyDown|KeyUp|Command)\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern XAML_BINDING_PATTERN = Pattern.compile("\\{Binding\\s+([^,}]+)");

    public List<ParsedCodeChunk> parse(String relativePath, String language, String content) {
        String lowerPath = relativePath.toLowerCase(Locale.ROOT);
        if (lowerPath.endsWith(".cs")) {
            return parseCSharp(relativePath, content);
        }
        if (lowerPath.endsWith(".xaml")) {
            return parseXaml(relativePath, content);
        }
        return fallbackChunks(relativePath, language, content, 90);
    }

    private List<ParsedCodeChunk> parseCSharp(String relativePath, String content) {
        String[] lines = splitLines(content);
        List<ParsedCodeChunk> chunks = new ArrayList<>();
        String namespace = null;
        String currentClass = null;
        int index = 0;

        for (int i = 0; i < lines.length; i++) {
            Matcher namespaceMatcher = NAMESPACE_PATTERN.matcher(lines[i]);
            if (namespaceMatcher.find()) {
                namespace = namespaceMatcher.group(1);
            }

            Matcher classMatcher = CLASS_PATTERN.matcher(lines[i]);
            if (classMatcher.find()) {
                currentClass = classMatcher.group(1);
                int end = Math.min(findBraceBlockEnd(lines, i), i + 80);
                chunks.add(buildChunk(
                        index++,
                        "class",
                        currentClass,
                        currentClass,
                        null,
                        namespace,
                        null,
                        null,
                        i + 1,
                        end + 1,
                        relativePath,
                        slice(lines, i, end),
                        Map.of("language", "csharp", "parser", "class")
                ));
            }

            String methodName = methodName(lines[i]);
            if (methodName != null && !isControlKeyword(methodName)) {
                String className = currentClass == null ? nearestClass(lines, i) : currentClass;
                int end = findMethodEnd(lines, i);
                String eventName = inferEventName(methodName);
                chunks.add(buildChunk(
                        index++,
                        eventName == null ? "method" : "event_handler",
                        methodName,
                        className,
                        methodName,
                        namespace,
                        null,
                        eventName,
                        i + 1,
                        end + 1,
                        relativePath,
                        slice(lines, i, end),
                        metadataForCSharp(relativePath, className, methodName)
                ));
                i = Math.max(i, end);
            }
        }

        if (chunks.isEmpty()) {
            return fallbackChunks(relativePath, "csharp", content, 90);
        }
        return chunks;
    }

    private List<ParsedCodeChunk> parseXaml(String relativePath, String content) {
        String[] lines = splitLines(content);
        List<ParsedCodeChunk> chunks = new ArrayList<>();
        String xamlClass = firstGroup(XAML_CLASS_PATTERN, content);
        int index = 0;

        if (xamlClass != null) {
            chunks.add(buildChunk(
                    index++,
                    "xaml_view",
                    xamlClass,
                    simpleName(xamlClass),
                    null,
                    packageName(xamlClass),
                    null,
                    null,
                    1,
                    Math.min(lines.length, 80),
                    relativePath,
                    slice(lines, 0, Math.min(lines.length - 1, 79)),
                    Map.of(
                            "language", "xaml",
                            "xamlClass", xamlClass,
                            "codeBehind", relativePath + ".cs"
                    )
            ));
        }

        for (int i = 0; i < lines.length; i++) {
            Matcher nameMatcher = XAML_NAME_PATTERN.matcher(lines[i]);
            Matcher eventMatcher = XAML_EVENT_PATTERN.matcher(lines[i]);
            Matcher bindingMatcher = XAML_BINDING_PATTERN.matcher(lines[i]);
            boolean hasName = nameMatcher.find();
            boolean hasEvent = eventMatcher.find();
            boolean hasBinding = bindingMatcher.find();
            if (!hasName && !hasEvent && !hasBinding) {
                continue;
            }

            String controlName = hasName ? nameMatcher.group(1) : null;
            String eventName = hasEvent ? eventMatcher.group(2) : null;
            String bindingPath = hasBinding ? bindingMatcher.group(1).trim() : null;
            int start = Math.max(0, i - 4);
            int end = Math.min(lines.length - 1, i + 8);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("language", "xaml");
            metadata.put("xamlClass", xamlClass);
            metadata.put("codeBehind", relativePath + ".cs");
            if (bindingPath != null) {
                metadata.put("binding", bindingPath);
            }
            chunks.add(buildChunk(
                    index++,
                    hasEvent ? "xaml_event" : hasBinding ? "xaml_binding" : "xaml_control",
                    firstNonBlank(eventName, controlName, bindingPath, xamlClass),
                    simpleName(xamlClass),
                    null,
                    packageName(xamlClass),
                    controlName,
                    eventName,
                    start + 1,
                    end + 1,
                    relativePath,
                    slice(lines, start, end),
                    metadata
            ));
        }

        if (chunks.isEmpty()) {
            return fallbackChunks(relativePath, "xaml", content, 80);
        }
        return chunks;
    }

    private List<ParsedCodeChunk> fallbackChunks(String relativePath, String language, String content, int windowSize) {
        String[] lines = splitLines(content);
        List<ParsedCodeChunk> chunks = new ArrayList<>();
        for (int start = 0; start < lines.length; start += windowSize) {
            int end = Math.min(lines.length - 1, start + windowSize - 1);
            chunks.add(buildChunk(
                    chunks.size(),
                    "file_section",
                    relativePath,
                    null,
                    null,
                    null,
                    null,
                    null,
                    start + 1,
                    end + 1,
                    relativePath,
                    slice(lines, start, end),
                    Map.of("language", language, "parser", "fallback")
            ));
        }
        return chunks;
    }

    private ParsedCodeChunk buildChunk(
            int chunkIndex,
            String chunkType,
            String symbolName,
            String className,
            String methodName,
            String namespaceName,
            String controlName,
            String eventName,
            int lineStart,
            int lineEnd,
            String relativePath,
            String content,
            Map<String, Object> metadata
    ) {
        String enrichedContent = "File: " + relativePath + "\n"
                + "Lines: " + lineStart + "-" + lineEnd + "\n"
                + content.strip();
        return new ParsedCodeChunk(
                chunkIndex,
                chunkType,
                symbolName,
                className,
                methodName,
                namespaceName,
                controlName,
                eventName,
                lineStart,
                lineEnd,
                enrichedContent,
                metadata
        );
    }

    private String[] splitLines(String content) {
        return content == null || content.isBlank() ? new String[]{""} : content.split("\\R", -1);
    }

    private String methodName(String line) {
        Matcher methodMatcher = METHOD_PATTERN.matcher(line);
        if (methodMatcher.find()) {
            return methodMatcher.group(1);
        }
        Matcher constructorMatcher = CONSTRUCTOR_PATTERN.matcher(line);
        if (constructorMatcher.find()) {
            return constructorMatcher.group(1);
        }
        return null;
    }

    private boolean isControlKeyword(String value) {
        return SetHolder.CONTROL_KEYWORDS.contains(value);
    }

    private int findMethodEnd(String[] lines, int start) {
        if (lines[start].contains("=>")) {
            return start;
        }
        return findBraceBlockEnd(lines, start);
    }

    private int findBraceBlockEnd(String[] lines, int start) {
        int balance = 0;
        boolean seenOpen = false;
        for (int i = start; i < lines.length; i++) {
            String line = stripStringLiterals(lines[i]);
            for (int j = 0; j < line.length(); j++) {
                char ch = line.charAt(j);
                if (ch == '{') {
                    balance++;
                    seenOpen = true;
                } else if (ch == '}') {
                    balance--;
                }
            }
            if (seenOpen && balance <= 0) {
                return i;
            }
            if (!seenOpen && i > start + 8) {
                return i;
            }
        }
        return Math.min(lines.length - 1, start + 120);
    }

    private String nearestClass(String[] lines, int index) {
        for (int i = index; i >= 0; i--) {
            Matcher matcher = CLASS_PATTERN.matcher(lines[i]);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private String inferEventName(String methodName) {
        if (methodName == null) {
            return null;
        }
        if (methodName.endsWith("_Click")
                || methodName.endsWith("_Loaded")
                || methodName.endsWith("_SelectionChanged")
                || methodName.endsWith("_TextChanged")
                || methodName.endsWith("_Checked")
                || methodName.endsWith("_Unchecked")
                || methodName.endsWith("_MouseDown")
                || methodName.endsWith("_MouseUp")
                || methodName.endsWith("_KeyDown")
                || methodName.endsWith("_KeyUp")) {
            return methodName;
        }
        return null;
    }

    private Map<String, Object> metadataForCSharp(String relativePath, String className, String methodName) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("language", "csharp");
        metadata.put("parser", "regex");
        if (relativePath.endsWith(".xaml.cs")) {
            metadata.put("xaml", relativePath.substring(0, relativePath.length() - 3));
        }
        if (className != null) {
            metadata.put("class", className);
        }
        if (methodName != null) {
            metadata.put("method", methodName);
        }
        return metadata;
    }

    private String slice(String[] lines, int start, int end) {
        StringBuilder builder = new StringBuilder();
        for (int i = Math.max(0, start); i <= Math.min(lines.length - 1, end); i++) {
            builder.append(i + 1).append(": ").append(lines[i]).append('\n');
        }
        return builder.toString();
    }

    private String stripStringLiterals(String value) {
        return value.replaceAll("\"(?:\\\\.|[^\"])*\"", "\"\"");
    }

    private String firstGroup(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String simpleName(String value) {
        if (value == null) {
            return null;
        }
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(dot + 1) : value;
    }

    private String packageName(String value) {
        if (value == null) {
            return null;
        }
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(0, dot) : null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static final class SetHolder {
        private static final java.util.Set<String> CONTROL_KEYWORDS = java.util.Set.of(
                "if", "for", "foreach", "while", "switch", "catch", "using", "lock"
        );
    }
}
