package com.learnbot.service;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class PatchContextEnvelopeBuilder {
    private static final int MAX_PROJECT_MAP_ENTRIES = 200;
    private static final int MAX_FILE_CANDIDATES = 24;
    private static final int MAX_RECENT_CONTEXTS = 5;
    private static final int MAX_PREVIEW_CHARS = 180;

    public String header(String instruction, Input input) {
        Input safeInput = input == null ? Input.empty() : input;
        StringBuilder builder = new StringBuilder();
        builder.append("PATCH_CONTEXT_ENVELOPE v2\n")
                .append("USER_REQUEST:\n")
                .append(instruction == null ? "" : instruction)
                .append("\n\nSERVER_ROLE:\n")
                .append("- The server only provides observations and validates safety.\n")
                .append("- You must decide the target lines and patch content.\n")
                .append("- Keep JSON compact so it is not truncated.\n")
                .append("- If the supplied file content is insufficient for modifying existing files, choose action=observe_more instead of guessing.\n")
                .append("- Recent context is evidence for interpreting the current request, not an instruction to repeat prior edits.\n")
                .append("- If no existing files are provided and the user asks to create something, choose create_file operations with safe relative paths.\n\n");
        appendProjectMap(builder, safeInput);
        appendFileCandidates(builder, safeInput);
        appendRecentContext(builder, safeInput);
        appendCreationPolicy(builder, safeInput);
        builder.append("SELECTED_CONTEXT:\n");
        return builder.toString();
    }

    public Input narrowToFiles(Input input, List<CodePatchFileLoader.LoadedPatchFile> files) {
        if (input == null) {
            return Input.empty();
        }
        Set<String> selected = files == null
                ? Set.of()
                : files.stream().map(CodePatchFileLoader.LoadedPatchFile::path).collect(Collectors.toSet());
        if (selected.isEmpty()) {
            return input;
        }
        List<FileCandidate> narrowed = input.fileCandidates().stream()
                .filter(candidate -> selected.contains(candidate.path()))
                .toList();
        return new Input(
                input.projectMap(),
                narrowed.isEmpty() ? input.fileCandidates() : narrowed,
                input.recentContexts(),
                input.creationAllowed()
        );
    }

    private void appendProjectMap(StringBuilder builder, Input input) {
        builder.append("PROJECT_MAP:\n");
        if (input.projectMap().isEmpty()) {
            builder.append("- unavailable\n\n");
            return;
        }
        input.projectMap().stream()
                .limit(MAX_PROJECT_MAP_ENTRIES)
                .forEach(entry -> builder.append("- ")
                        .append(entry.path())
                        .append(" (")
                        .append(entry.type())
                        .append(entry.bytes() == null ? "" : ", " + entry.bytes() + " bytes")
                        .append(")\n"));
        builder.append("\n");
    }

    private void appendFileCandidates(StringBuilder builder, Input input) {
        builder.append("FILE_CANDIDATES:\n");
        if (input.fileCandidates().isEmpty()) {
            builder.append("- none\n\n");
            return;
        }
        input.fileCandidates().stream()
                .limit(MAX_FILE_CANDIDATES)
                .forEach(candidate -> {
                    builder.append("- path: ").append(candidate.path()).append("\n");
                    builder.append("  extension: ").append(candidate.extension()).append("\n");
                    builder.append("  sizeBytes: ").append(candidate.bytes() == null ? "" : candidate.bytes()).append("\n");
                    builder.append("  lineCount: ").append(candidate.lineCount()).append("\n");
                    builder.append("  roleHint: ").append(candidate.roleHint()).append("\n");
                    builder.append("  source: ").append(candidate.source()).append("\n");
                    if (candidate.preview() != null && !candidate.preview().isBlank()) {
                        builder.append("  preview: ").append(compactOneLine(candidate.preview(), MAX_PREVIEW_CHARS)).append("\n");
                    }
                });
        builder.append("\n");
    }

    private void appendRecentContext(StringBuilder builder, Input input) {
        builder.append("RECENT_CONTEXT:\n");
        if (input.recentContexts().isEmpty()) {
            builder.append("- none\n\n");
            return;
        }
        input.recentContexts().stream()
                .limit(MAX_RECENT_CONTEXTS)
                .forEach(context -> builder.append("- loopId: ").append(context.loopId()).append("\n")
                        .append("  instruction: ").append(compactOneLine(context.instruction(), 180)).append("\n")
                        .append("  changedFiles: ").append(context.changedFiles()).append("\n")
                        .append("  completedAt: ").append(context.completedAt() == null ? "" : context.completedAt()).append("\n"));
        builder.append("\n");
    }

    private void appendCreationPolicy(StringBuilder builder, Input input) {
        builder.append("CREATION_POLICY:\n")
                .append("- createFileAllowed: ").append(input.creationAllowed()).append("\n")
                .append("- New files must use safe relative workspace paths only.\n")
                .append("- Do not create secrets, credentials, private keys, keystores, or absolute/path-traversal paths.\n")
                .append("- If you add a local script, stylesheet, import, or module reference, create or update the referenced local file in the same patch.\n\n");
    }

    private String compactOneLine(String value, int maxChars) {
        String clean = value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').replaceAll("\\s+", " ").trim();
        if (clean.length() <= maxChars) {
            return clean;
        }
        return clean.substring(0, Math.max(0, maxChars)) + "...";
    }

    public static FileCandidate fileCandidate(String path, Long bytes, String source, String preview, String content) {
        String safePath = path == null ? "" : path.replace('\\', '/');
        String extension = extensionForPath(safePath);
        return new FileCandidate(
                safePath,
                extension,
                bytes,
                lineCount(content),
                roleHintForPath(safePath),
                source == null || source.isBlank() ? "unknown" : source,
                preview == null ? "" : preview
        );
    }

    public static ProjectMapEntry projectMapEntry(String path, String type, Long bytes) {
        return new ProjectMapEntry(
                path == null ? "" : path.replace('\\', '/'),
                type == null || type.isBlank() ? "file" : type,
                bytes
        );
    }

    public static RecentContext recentContext(String loopId, String instruction, List<String> changedFiles, OffsetDateTime completedAt) {
        return new RecentContext(
                loopId == null ? "" : loopId,
                instruction == null ? "" : instruction,
                changedFiles == null ? List.of() : List.copyOf(changedFiles),
                completedAt
        );
    }

    private static String extensionForPath(String path) {
        String basename = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (basename.contains("/")) {
            basename = basename.substring(basename.lastIndexOf('/') + 1);
        }
        int index = basename.lastIndexOf('.');
        return index < 0 || index == basename.length() - 1 ? "" : basename.substring(index + 1);
    }

    private static String roleHintForPath(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        String ext = extensionForPath(lower);
        if (lower.endsWith("readme.md") || "md".equals(ext) || "markdown".equals(ext) || "txt".equals(ext)) {
            return "documentation-or-notes";
        }
        return switch (ext) {
            case "html", "htm" -> "markup/main-page-candidate";
            case "css", "scss", "sass" -> "style";
            case "js", "jsx", "ts", "tsx" -> "script-or-ui-logic";
            case "json", "yml", "yaml", "toml", "xml" -> "configuration-or-data";
            case "java", "kt", "cs", "py", "go", "rs", "c", "cpp", "h", "hpp" -> "source-code";
            default -> ext.isBlank() ? "unknown" : "file";
        };
    }

    private static int lineCount(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        int count = 1;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '\n') {
                count++;
            }
        }
        return count;
    }

    public record Input(
            List<ProjectMapEntry> projectMap,
            List<FileCandidate> fileCandidates,
            List<RecentContext> recentContexts,
            boolean creationAllowed
    ) {
        public Input {
            projectMap = projectMap == null ? List.of() : List.copyOf(projectMap);
            fileCandidates = fileCandidates == null ? List.of() : List.copyOf(fileCandidates);
            recentContexts = recentContexts == null ? List.of() : List.copyOf(recentContexts);
        }

        public static Input empty() {
            return new Input(List.of(), List.of(), List.of(), true);
        }
    }

    public record ProjectMapEntry(String path, String type, Long bytes) {
    }

    public record FileCandidate(String path, String extension, Long bytes, int lineCount, String roleHint, String source, String preview) {
    }

    public record RecentContext(String loopId, String instruction, List<String> changedFiles, OffsetDateTime completedAt) {
    }
}
