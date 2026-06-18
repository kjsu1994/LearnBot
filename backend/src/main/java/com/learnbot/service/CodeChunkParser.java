package com.learnbot.service;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
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
    private static final Pattern JS_SYMBOL_PATTERN = Pattern.compile(
            "^\\s*(?:export\\s+default\\s+|export\\s+)?(?:async\\s+)?(?:function\\s+([A-Za-z_$][A-Za-z0-9_$]*)|(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|[A-Za-z_$][A-Za-z0-9_$]*)\\s*=>|class\\s+([A-Za-z_$][A-Za-z0-9_$]*))"
    );
    private static final Pattern PYTHON_SYMBOL_PATTERN = Pattern.compile("^\\s*(?:async\\s+)?(?:def|class)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*[(:]");
    private static final Pattern DART_SYMBOL_PATTERN = Pattern.compile("^\\s*(?:class\\s+([A-Za-z_][A-Za-z0-9_]*)|(?:Future<[^>]+>|[A-Za-z_][A-Za-z0-9_<>,?]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\([^;]*\\)\\s*(?:async\\s*)?\\{?)");
    private static final Pattern GENERIC_SYMBOL_PATTERN = Pattern.compile("^\\s*(?:func|fn|function|def|class|struct|enum|interface)\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern SQL_STATEMENT_PATTERN = Pattern.compile("^\\s*(CREATE|ALTER|DROP|INSERT|UPDATE|DELETE|SELECT)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern YAML_TOP_LEVEL_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]+\\s*:");
    private static final Pattern CSS_SELECTOR_PATTERN = Pattern.compile("^\\s*([^@{}][^{};]{1,160})\\s*\\{\\s*$");
    private static final Pattern XML_TAG_PATTERN = Pattern.compile("^\\s*<([A-Za-z_][A-Za-z0-9_.:-]*)(\\s|>|/>)");

    public List<ParsedCodeChunk> parse(String relativePath, String language, String content) {
        String lowerPath = relativePath.toLowerCase(Locale.ROOT);
        try {
            if (lowerPath.endsWith(".java")) {
                return parseJava(relativePath, content);
            }
            if (lowerPath.endsWith(".cs")) {
                return parseCSharp(relativePath, content);
            }
            if (lowerPath.endsWith(".xaml")) {
                return parseXaml(relativePath, content);
            }
            if (lowerPath.endsWith(".js") || lowerPath.endsWith(".jsx") || lowerPath.endsWith(".ts") || lowerPath.endsWith(".tsx")) {
                return parsePatternSymbols(relativePath, language, content, JS_SYMBOL_PATTERN, "function", 120);
            }
            if (lowerPath.endsWith(".dart")) {
                return parsePatternSymbols(relativePath, language, content, DART_SYMBOL_PATTERN, "method", 120);
            }
            if (lowerPath.endsWith(".py")) {
                return parsePatternSymbols(relativePath, language, content, PYTHON_SYMBOL_PATTERN, "symbol", 120);
            }
            if (lowerPath.endsWith(".go") || lowerPath.endsWith(".rs") || lowerPath.endsWith(".kt")
                    || lowerPath.endsWith(".kts") || lowerPath.endsWith(".php") || lowerPath.endsWith(".rb")
                    || lowerPath.endsWith(".swift") || lowerPath.endsWith(".c") || lowerPath.endsWith(".cc")
                    || lowerPath.endsWith(".cpp") || lowerPath.endsWith(".h") || lowerPath.endsWith(".hpp")) {
                return parsePatternSymbols(relativePath, language, content, GENERIC_SYMBOL_PATTERN, "symbol", 120);
            }
            if (lowerPath.endsWith(".sql")) {
                return parseDelimited(relativePath, language, content, SQL_STATEMENT_PATTERN, "sql_statement", ";", 120);
            }
            if (lowerPath.endsWith(".yml") || lowerPath.endsWith(".yaml")) {
                return parseIndentedBlocks(relativePath, language, content, YAML_TOP_LEVEL_PATTERN, "yaml_block", 100);
            }
            if (lowerPath.endsWith(".css") || lowerPath.endsWith(".scss")) {
                return parsePatternSymbols(relativePath, language, content, CSS_SELECTOR_PATTERN, "css_rule", 80);
            }
            if (lowerPath.endsWith(".xml") || lowerPath.endsWith(".config") || lowerPath.endsWith(".csproj")
                    || lowerPath.endsWith(".html")) {
                return parsePatternSymbols(relativePath, language, content, XML_TAG_PATTERN, "xml_element", 100);
            }
            if (lowerPath.endsWith(".md")) {
                return parseMarkdown(relativePath, language, content);
            }
        } catch (RuntimeException ignored) {
            return fallbackChunks(relativePath, language, content, 90);
        }
        return fallbackChunks(relativePath, language, content, 90);
    }

    private List<ParsedCodeChunk> parseJava(String relativePath, String content) {
        CompilationUnit unit = StaticJavaParser.parse(content == null ? "" : content);
        String[] lines = splitLines(content);
        String packageName = unit.getPackageDeclaration().map(declaration -> declaration.getNameAsString()).orElse(null);
        List<ParsedCodeChunk> chunks = new ArrayList<>();

        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            if (type instanceof ClassOrInterfaceDeclaration || type instanceof EnumDeclaration) {
                Range range = range(type, lines.length);
                String className = type.getNameAsString();
                chunks.add(buildChunk(
                        chunks.size(),
                        type instanceof EnumDeclaration ? "enum" : "class",
                        className,
                        className,
                        null,
                        packageName,
                        null,
                        null,
                        range.start(),
                        range.end(),
                        relativePath,
                        slice(lines, range.start() - 1, range.end() - 1),
                        metadata("java", "javaparser", Map.of("symbol", className))
                ));
            }
        }

        for (ConstructorDeclaration constructor : unit.findAll(ConstructorDeclaration.class)) {
            addJavaCallable(chunks, relativePath, lines, packageName, constructor, constructor.getNameAsString(), "constructor");
        }
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            addJavaCallable(chunks, relativePath, lines, packageName, method, method.getNameAsString(), "method");
        }

        return chunks.isEmpty() ? fallbackChunks(relativePath, "java", content, 90) : chunks;
    }

    private void addJavaCallable(
            List<ParsedCodeChunk> chunks,
            String relativePath,
            String[] lines,
            String packageName,
            Node node,
            String methodName,
            String chunkType
    ) {
        Range range = range(node, lines.length);
        String className = node.findAncestor(TypeDeclaration.class)
                .map(type -> ((TypeDeclaration<?>) type).getNameAsString())
                .orElse(null);
        chunks.add(buildChunk(
                chunks.size(),
                chunkType,
                methodName,
                className,
                methodName,
                packageName,
                null,
                null,
                range.start(),
                range.end(),
                relativePath,
                slice(lines, range.start() - 1, range.end() - 1),
                metadata("java", "javaparser", metadataValues("class", className, "method", methodName))
        ));
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
                        metadata("csharp", "regex", Map.of("symbol", currentClass))
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
                    metadata("xaml", "xml_regex", metadataValues(
                            "xamlClass", xamlClass,
                            "codeBehind", relativePath + ".cs"
                    ))
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
            Map<String, Object> metadata = metadata("xaml", "xml_regex", metadataValues(
                    "xamlClass", xamlClass,
                    "codeBehind", relativePath + ".cs"
            ));
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
                    metadata(language, "line_window", Map.of("windowSize", windowSize))
            ));
        }
        return chunks;
    }

    private List<ParsedCodeChunk> parsePatternSymbols(
            String relativePath,
            String language,
            String content,
            Pattern pattern,
            String defaultType,
            int maxLines
    ) {
        String[] lines = splitLines(content);
        List<Integer> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (isStatementLine(lines[i])) {
                continue;
            }
            Matcher matcher = pattern.matcher(lines[i]);
            if (matcher.find()) {
                starts.add(i);
                names.add(firstMatchedGroup(matcher, lines[i].trim()));
            }
        }
        if (starts.isEmpty()) {
            return fallbackChunks(relativePath, language, content, 90);
        }
        List<ParsedCodeChunk> chunks = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1) - 1 : Math.min(lines.length - 1, start + maxLines - 1);
            end = Math.min(end, start + maxLines - 1);
            String symbol = names.get(i);
            String chunkType = inferChunkType(defaultType, symbol, lines[start]);
            chunks.add(buildChunk(
                    chunks.size(),
                    chunkType,
                    symbol,
                    "class".equals(chunkType) ? symbol : null,
                    "method".equals(chunkType) || "function".equals(chunkType) ? symbol : null,
                    null,
                    null,
                    null,
                    start + 1,
                    end + 1,
                    relativePath,
                    slice(lines, start, end),
                    metadata(language, "regex_symbol", Map.of("symbol", symbol))
            ));
        }
        return chunks;
    }

    private List<ParsedCodeChunk> parseDelimited(
            String relativePath,
            String language,
            String content,
            Pattern pattern,
            String chunkType,
            String delimiter,
            int maxLines
    ) {
        String[] lines = splitLines(content);
        List<ParsedCodeChunk> chunks = new ArrayList<>();
        int start = -1;
        String symbol = null;
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = pattern.matcher(lines[i]);
            if (matcher.find() && start < 0) {
                start = i;
                symbol = matcher.group(1).toUpperCase(Locale.ROOT);
            }
            if (start >= 0 && (lines[i].trim().endsWith(delimiter) || i - start >= maxLines - 1)) {
                chunks.add(buildChunk(chunks.size(), chunkType, symbol, null, null, null, null, null,
                        start + 1, i + 1, relativePath, slice(lines, start, i),
                        metadata(language, "statement", Map.of("statement", symbol))));
                start = -1;
                symbol = null;
            }
        }
        if (start >= 0) {
            chunks.add(buildChunk(chunks.size(), chunkType, symbol, null, null, null, null, null,
                    start + 1, lines.length, relativePath, slice(lines, start, lines.length - 1),
                    metadata(language, "statement", Map.of("statement", symbol))));
        }
        return chunks.isEmpty() ? fallbackChunks(relativePath, language, content, 90) : chunks;
    }

    private List<ParsedCodeChunk> parseIndentedBlocks(String relativePath, String language, String content, Pattern pattern, String chunkType, int maxLines) {
        String[] lines = splitLines(content);
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank() && pattern.matcher(lines[i]).find() && !Character.isWhitespace(lines[i].charAt(0))) {
                starts.add(i);
            }
        }
        if (starts.isEmpty()) {
            return fallbackChunks(relativePath, language, content, 90);
        }
        List<ParsedCodeChunk> chunks = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1) - 1 : lines.length - 1;
            end = Math.min(end, start + maxLines - 1);
            String key = lines[start].split(":", 2)[0].trim();
            chunks.add(buildChunk(chunks.size(), chunkType, key, null, null, null, null, null,
                    start + 1, end + 1, relativePath, slice(lines, start, end),
                    metadata(language, "top_level_block", Map.of("key", key))));
        }
        return chunks;
    }

    private List<ParsedCodeChunk> parseMarkdown(String relativePath, String language, String content) {
        String[] lines = splitLines(content);
        Pattern heading = Pattern.compile("^(#{1,6})\\s+(.+)$");
        List<Integer> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = heading.matcher(lines[i]);
            if (matcher.find()) {
                starts.add(i);
                names.add(matcher.group(2).trim());
            }
        }
        if (starts.isEmpty()) {
            return fallbackChunks(relativePath, language, content, 90);
        }
        List<ParsedCodeChunk> chunks = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1) - 1 : lines.length - 1;
            chunks.add(buildChunk(chunks.size(), "markdown_section", names.get(i), null, null, null, null, null,
                    start + 1, end + 1, relativePath, slice(lines, start, end),
                    metadata(language, "markdown_heading", Map.of("heading", names.get(i)))));
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

    private boolean isStatementLine(String line) {
        String trimmed = line == null ? "" : line.stripLeading().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("return ")
                || trimmed.startsWith("if ")
                || trimmed.startsWith("for ")
                || trimmed.startsWith("while ")
                || trimmed.startsWith("switch ")
                || trimmed.startsWith("catch ");
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
        Map<String, Object> metadata = metadata("csharp", "regex", Map.of());
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

    private Map<String, Object> metadata(String language, String parser, Map<String, Object> values) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("language", language);
        metadata.put("parser", parser);
        metadata.put("strategy", parser);
        if (values != null) {
            values.forEach((key, value) -> {
                if (value != null) {
                    metadata.put(key, value);
                }
            });
        }
        return metadata;
    }

    private Map<String, Object> metadataValues(Object... keyValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value != null) {
                values.put(String.valueOf(keyValues[i]), value);
            }
        }
        return values;
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

    private Range range(Node node, int lineCount) {
        int start = node.getRange().map(value -> value.begin.line).orElse(1);
        int end = node.getRange().map(value -> value.end.line).orElse(Math.min(lineCount, start + 80));
        return new Range(Math.max(1, start), Math.max(start, end));
    }

    private String firstMatchedGroup(Matcher matcher, String fallback) {
        for (int i = 1; i <= matcher.groupCount(); i++) {
            String value = matcher.group(i);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return fallback.length() <= 80 ? fallback : fallback.substring(0, 80);
    }

    private String inferChunkType(String defaultType, String symbol, String line) {
        String lower = line == null ? "" : line.toLowerCase(Locale.ROOT);
        if (lower.contains("class ")) {
            return "class";
        }
        if (lower.contains("component") || (symbol != null && !symbol.isBlank() && Character.isUpperCase(symbol.charAt(0)))) {
            return "component";
        }
        if (lower.contains("function") || lower.contains("=>") || lower.contains("def ") || lower.contains("func ") || lower.contains("fn ")) {
            return "method";
        }
        return defaultType;
    }

    private static final class SetHolder {
        private static final java.util.Set<String> CONTROL_KEYWORDS = java.util.Set.of(
                "if", "for", "foreach", "while", "switch", "catch", "using", "lock"
        );
    }

    private record Range(int start, int end) {
    }
}
