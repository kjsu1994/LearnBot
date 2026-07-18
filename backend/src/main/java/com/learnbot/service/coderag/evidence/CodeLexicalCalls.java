package com.learnbot.service.coderag.evidence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A bounded, offset-preserving lexical view of call sites shared by IR extraction and excerpt
 * selection. It deliberately recognizes only language-neutral identifier/call syntax and carries
 * no framework, repository, or question vocabulary.
 */
public final class CodeLexicalCalls {
    private static final Pattern QUALIFIED_CALL = Pattern.compile(
            "\\b([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\.\\s*([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(");
    private static final Pattern UNQUALIFIED_CALL = Pattern.compile(
            "(?<![A-Za-z0-9_$.])([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(");
    private static final Set<String> NON_CALL_KEYWORDS = Set.of(
            "if", "else", "for", "foreach", "while", "do", "switch", "case", "catch", "finally",
            "return", "throw", "throws", "new", "typeof", "sizeof", "nameof", "checked", "unchecked",
            "using", "lock", "synchronized", "assert", "when", "with", "super", "this", "base",
            "class", "interface", "enum", "record", "struct", "def", "func", "function", "sub",
            "constructor", "delegate");

    private CodeLexicalCalls() {
    }

    public static List<CallSite> scan(String source, String declaredCallable) {
        String code = mask(source);
        int bodyStart = callableBodyStart(code, declaredCallable);
        List<CallSpan> qualifiedSpans = new ArrayList<>();
        List<CallSite> calls = new ArrayList<>();

        Matcher qualified = QUALIFIED_CALL.matcher(code);
        while (qualified.find()) {
            if (qualified.start() < bodyStart) continue;
            qualifiedSpans.add(new CallSpan(qualified.start(), qualified.end()));
            calls.add(new CallSite(
                    qualified.start(), lineAt(code, qualified.start()),
                    qualified.group(1) + "." + qualified.group(2), true));
        }

        Matcher local = UNQUALIFIED_CALL.matcher(code);
        while (local.find()) {
            int offset = local.start(1);
            String symbol = local.group(1);
            if (!isInvocation(code, offset, symbol, bodyStart)) continue;
            if (qualifiedSpans.stream().anyMatch(span -> span.contains(offset))) continue;
            calls.add(new CallSite(offset, lineAt(code, offset), symbol, false));
        }

        calls.sort(Comparator.comparingInt(CallSite::offset)
                .thenComparing(Comparator.comparing(CallSite::qualified).reversed())
                .thenComparing(CallSite::symbol));
        LinkedHashMap<String, CallSite> unique = new LinkedHashMap<>();
        for (CallSite call : calls) {
            unique.putIfAbsent(call.offset() + "|" + call.symbol(), call);
        }
        return List.copyOf(unique.values());
    }

    /** Returns head, tail, midpoint, then successively finer midpoint indexes. */
    public static List<Integer> coverageOrder(int size) {
        if (size <= 0) return List.of();
        LinkedHashMap<Integer, Boolean> order = new LinkedHashMap<>();
        order.put(0, true);
        if (size > 1) order.put(size - 1, true);
        List<int[]> intervals = new ArrayList<>();
        if (size > 2) intervals.add(new int[]{1, size - 2});
        for (int cursor = 0; cursor < intervals.size(); cursor++) {
            int[] interval = intervals.get(cursor);
            if (interval[0] > interval[1]) continue;
            int midpoint = interval[0] + (interval[1] - interval[0]) / 2;
            order.put(midpoint, true);
            if (interval[0] <= midpoint - 1) intervals.add(new int[]{interval[0], midpoint - 1});
            if (midpoint + 1 <= interval[1]) intervals.add(new int[]{midpoint + 1, interval[1]});
        }
        return List.copyOf(order.keySet());
    }

    /** Replaces comments and string/character literals with spaces while retaining offsets/newlines. */
    public static String mask(String source) {
        String value = source == null ? "" : source;
        StringBuilder code = new StringBuilder(value.length());
        ScanState state = ScanState.CODE;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            char next = index + 1 < value.length() ? value.charAt(index + 1) : '\0';
            switch (state) {
                case CODE -> {
                    if (current == '/' && next == '/') {
                        code.append("  ");
                        index++;
                        state = ScanState.LINE_COMMENT;
                    } else if (current == '/' && next == '*') {
                        code.append("  ");
                        index++;
                        state = ScanState.BLOCK_COMMENT;
                    } else if (current == '\'') {
                        code.append(' ');
                        state = ScanState.SINGLE_QUOTE;
                    } else if (current == '"') {
                        code.append(' ');
                        state = ScanState.DOUBLE_QUOTE;
                    } else {
                        code.append(current);
                    }
                }
                case LINE_COMMENT -> {
                    code.append(current == '\n' ? '\n' : ' ');
                    if (current == '\n') state = ScanState.CODE;
                }
                case BLOCK_COMMENT -> {
                    if (current == '*' && next == '/') {
                        code.append("  ");
                        index++;
                        state = ScanState.CODE;
                    } else {
                        code.append(current == '\n' ? '\n' : ' ');
                    }
                }
                case SINGLE_QUOTE, DOUBLE_QUOTE -> {
                    char delimiter = state == ScanState.SINGLE_QUOTE ? '\'' : '"';
                    if (current == '\\' && next != '\0') {
                        code.append("  ");
                        index++;
                    } else {
                        code.append(current == '\n' ? '\n' : ' ');
                        if (current == delimiter) state = ScanState.CODE;
                    }
                }
            }
        }
        return code.toString();
    }

    private static int callableBodyStart(String code, String declaredCallable) {
        if (declaredCallable == null || declaredCallable.isBlank()) return 0;
        String callable = declaredCallable.trim();
        int declaration = code.indexOf(callable);
        int scanStart = Math.max(0, declaration + callable.length());
        int brace = code.indexOf('{', scanStart);
        int expression = code.indexOf("=>", scanStart);
        if (brace < 0) return Math.max(0, expression + (expression < 0 ? 0 : 2));
        if (expression < 0) return brace + 1;
        return Math.min(brace + 1, expression + 2);
    }

    private static boolean isInvocation(String code, int symbolOffset, String symbol, int bodyStart) {
        if (symbol == null || NON_CALL_KEYWORDS.contains(symbol.toLowerCase(Locale.ROOT))) return false;
        if (symbolOffset < Math.max(0, bodyStart)) return false;
        if (looksLikeDeclaration(code, symbolOffset, symbol.length())) return false;
        int previous = symbolOffset - 1;
        while (previous >= 0 && Character.isWhitespace(code.charAt(previous))) previous--;
        int wordEnd = previous + 1;
        while (previous >= 0 && (Character.isLetterOrDigit(code.charAt(previous))
                || code.charAt(previous) == '_' || code.charAt(previous) == '$')) previous--;
        String precedingWord = code.substring(previous + 1, wordEnd).toLowerCase(Locale.ROOT);
        return !Set.of("new", "def", "func", "function", "sub").contains(precedingWord);
    }

    private static boolean looksLikeDeclaration(String code, int symbolOffset, int symbolLength) {
        int open = code.indexOf('(', Math.max(0, symbolOffset + symbolLength));
        if (open < 0) return false;
        int depth = 0;
        int close = -1;
        for (int index = open; index < code.length(); index++) {
            char current = code.charAt(index);
            if (current == '(') depth++;
            if (current == ')' && --depth == 0) {
                close = index;
                break;
            }
        }
        if (close < 0) return false;
        int cursor = close + 1;
        while (cursor < code.length() && Character.isWhitespace(code.charAt(cursor))) cursor++;
        if (cursor < code.length() && code.charAt(cursor) == '{') return true;
        if (cursor + 1 < code.length() && code.startsWith("=>", cursor)) return true;
        int boundary = Math.min(code.length(), cursor + 160);
        int brace = code.indexOf('{', cursor);
        int semicolon = code.indexOf(';', cursor);
        return brace >= 0 && brace < boundary && (semicolon < 0 || brace < semicolon)
                && code.substring(cursor, brace).matches("(?s)^(?:throws\\s+|where\\s+|[A-Za-z0-9_$<>,.?&:]+\\s*)+");
    }

    private static int lineAt(String value, int offset) {
        int line = 0;
        for (int index = 0; index < Math.min(Math.max(0, offset), value.length()); index++) {
            if (value.charAt(index) == '\n') line++;
        }
        return line;
    }

    public record CallSite(int offset, int lineOffset, String symbol, boolean qualified) {
        public CallSite {
            offset = Math.max(0, offset);
            lineOffset = Math.max(0, lineOffset);
            symbol = symbol == null ? "" : symbol.trim();
        }
    }

    private record CallSpan(int start, int end) {
        private boolean contains(int offset) {
            return offset >= start && offset < end;
        }
    }

    private enum ScanState {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        SINGLE_QUOTE,
        DOUBLE_QUOTE
    }
}
