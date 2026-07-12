package com.learnbot.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class CodeLanguageCatalog {
    private static final Map<String, String> EXTENSIONS;

    static {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        register(values, "java", ".java");
        register(values, "csharp", ".cs", ".csx");
        register(values, "kotlin", ".kt", ".kts");
        register(values, "scala", ".scala", ".sc");
        register(values, "javascript", ".js", ".jsx", ".mjs", ".cjs", ".vue", ".svelte", ".astro");
        register(values, "typescript", ".ts", ".tsx", ".mts", ".cts");
        register(values, "python", ".py", ".pyi");
        register(values, "go", ".go");
        register(values, "rust", ".rs");
        register(values, "cpp", ".c", ".cc", ".cpp", ".cxx", ".h", ".hh", ".hpp", ".hxx");
        register(values, "ruby", ".rb");
        register(values, "php", ".php");
        register(values, "swift", ".swift");
        register(values, "dart", ".dart");
        register(values, "visual_basic", ".vb");
        register(values, "fsharp", ".fs", ".fsi", ".fsx");
        register(values, "sql", ".sql");
        register(values, "xaml", ".xaml");
        register(values, "razor", ".razor", ".cshtml");
        register(values, "qml", ".qml");
        register(values, "shell", ".sh", ".bash", ".zsh");
        register(values, "powershell", ".ps1", ".psm1");
        EXTENSIONS = Map.copyOf(values);
    }

    private CodeLanguageCatalog() {
    }

    public static String languageForPath(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return EXTENSIONS.entrySet().stream()
                .filter(entry -> lower.endsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("other");
    }

    private static void register(Map<String, String> values, String language, String... extensions) {
        for (String extension : extensions) {
            values.put(extension, language);
        }
    }
}
