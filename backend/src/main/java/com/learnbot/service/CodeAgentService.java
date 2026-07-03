package com.learnbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.dto.CodeAgentPatchResponse;
import com.learnbot.dto.CodeAgentPlanResponse;
import com.learnbot.dto.CodeEvidence;
import com.learnbot.dto.CodeSearchResult;
import com.learnbot.dto.PatchFileDiff;
import com.learnbot.dto.PatchTargetFile;
import com.learnbot.dto.PatchValidationResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CodeAgentService {
    private static final int PLAN_SEARCH_LIMIT = 12;
    private static final int MAX_PLAN_TARGETS = 3;
    private static final String DEFAULT_KOREAN_POEM_APPEND = """
            \uC791\uC740 \uBE5B\uC774 \uBA38\uBB38 \uC790\uB9AC
            \uD55C \uC904\uC758 \uBC14\uB78C\uC774 \uC26C\uC5B4 \uAC00\uACE0
            \uC624\uB298\uC758 \uB9C8\uC74C\uC774 \uC870\uC6A9\uD788 \uBE5B\uB09C\uB2E4
            """;

    private final CodeSearchService searchService;
    private final CodePatchFileLoader fileLoader;
    private final PatchValidationService validationService;
    private final OllamaClient ollamaClient;
    private final ObjectMapper objectMapper;

    public CodeAgentService(
            CodeSearchService searchService,
            CodePatchFileLoader fileLoader,
            PatchValidationService validationService,
            OllamaClient ollamaClient,
            ObjectMapper objectMapper
    ) {
        this.searchService = searchService;
        this.fileLoader = fileLoader;
        this.validationService = validationService;
        this.ollamaClient = ollamaClient;
        this.objectMapper = objectMapper;
    }

    public CodeAgentPlanResponse plan(UUID repositoryId, UUID selectedSpaceId, List<UUID> spaceIds, String instruction, Integer limit) {
        String safeInstruction = safe(instruction);
        int searchLimit = Math.max(4, Math.min(limit == null ? PLAN_SEARCH_LIMIT : limit, 20));
        List<String> warnings = new ArrayList<>();
        List<CodeSearchResult> evidence = searchService.search(repositoryId, safeInstruction, searchLimit, spaceIds, selectedSpaceId);
        List<String> candidatePaths = candidatePaths(evidence, warnings);
        if (candidatePaths.isEmpty()) {
            return new CodeAgentPlanResponse(
                    intent(safeInstruction),
                    "愿??肄붾뱶 洹쇨굅媛 遺議깊빐 ?덉쟾???섏젙 怨꾪쉷??留뚮뱾 ???놁뒿?덈떎.",
                    List.of(),
                    List.of("吏덈Ц 踰붿쐞瑜?醫곹엳嫄곕굹 ?뚯씪紐? ?대옒?ㅻ챸, 硫붿꽌?쒕챸??異붽???二쇱꽭??"),
                    "high",
                    true,
                    List.copyOf(warnings),
                    evidence(evidence)
            );
        }
        CodeAgentPlanResponse llmPlan = tryLlmPlan(safeInstruction, candidatePaths, evidence, warnings);
        if (llmPlan != null) {
            return llmPlan;
        }
        List<PatchTargetFile> targets = candidatePaths.stream()
                .limit(MAX_PLAN_TARGETS)
                .map(path -> new PatchTargetFile(path, "Code RAG search ranked this file as a likely patch target."))
                .toList();
        warnings.add("LLM plan JSON parsing failed or was unavailable; deterministic target selection was used.");
        return new CodeAgentPlanResponse(
                intent(safeInstruction),
                "寃??洹쇨굅瑜?湲곗??쇰줈 ?섏젙 ?꾨낫 ?뚯씪???좎젙?덉뒿?덈떎.",
                targets,
                List.of(
                        "?좎젙???뚯씪???꾩옱 援ы쁽???뺤씤?⑸땲??",
                        "?붿껌???숈옉怨?吏곸젒 愿?⑤맂 理쒖냼 蹂寃쎈쭔 ?쒖븞?⑸땲??",
                        "diff ?앹꽦 ???쒕쾭 寃利앹쓣 ?듦낵??unified diff留?諛섑솚?⑸땲??"
                ),
                "medium",
                false,
                List.copyOf(warnings),
                evidence(evidence)
        );
    }

    public CodeAgentPatchResponse patch(UUID repositoryId, UUID selectedSpaceId, List<UUID> spaceIds, String instruction, List<String> requestedTargetFiles) {
        String safeInstruction = safe(instruction);
        List<String> warnings = new ArrayList<>();
        List<String> targetFiles = requestedTargetFiles == null || requestedTargetFiles.isEmpty()
                ? plan(repositoryId, selectedSpaceId, spaceIds, safeInstruction, MAX_PLAN_TARGETS).targetFiles().stream()
                .map(PatchTargetFile::path)
                .toList()
                : requestedTargetFiles;
        CodePatchFileLoader.LoadResult loaded = fileLoader.load(repositoryId, targetFiles);
        warnings.addAll(loaded.warnings());
        return patchLoadedFiles(safeInstruction, loaded.files(), warnings);
    }

    public CodeAgentPatchResponse patchFromLoadedFiles(String instruction, List<CodePatchFileLoader.LoadedPatchFile> loadedFiles) {
        List<String> warnings = new ArrayList<>();
        warnings.add("Patch input came from completed Local Agent file.read observations.");
        return patchLoadedFiles(safe(instruction), loadedFiles, warnings);
    }

    private CodeAgentPatchResponse patchLoadedFiles(
            String safeInstruction,
            List<CodePatchFileLoader.LoadedPatchFile> loadedFiles,
            List<String> warnings
    ) {
        List<CodePatchFileLoader.LoadedPatchFile> filesToPatch = loadedFiles == null ? List.of() : loadedFiles;
        if (filesToPatch.isEmpty()) {
            return new CodeAgentPatchResponse(
                    "No safe target files were available for patch generation.",
                    List.of(),
                    "high",
                    List.copyOf(warnings),
                    List.of(),
                    false
            );
        }
        CodeAgentPatchResponse deterministicAppend = deterministicAppendPatch(safeInstruction, filesToPatch, warnings);
        if (deterministicAppend != null) {
            return deterministicAppend;
        }
        String diff;
        try {
            diff = cleanDiff(ollamaClient.chatResult(
                    patchSystemPrompt(),
                    patchUserPrompt(safeInstruction, filesToPatch),
                    1800
            ).content());
        } catch (RuntimeException ex) {
            warnings.add("LLM patch generation failed: " + ex.getMessage());
            CodeAgentPatchResponse fallback = deterministicAppendPatch(safeInstruction, filesToPatch, warnings);
            if (fallback != null) {
                return fallback;
            }
            return new CodeAgentPatchResponse(
                    "Patch generation model call failed.",
                    List.of(),
                    "high",
                    List.copyOf(warnings),
                    List.of(),
                    false
            );
        }
        PatchValidationResult validation = validationService.validate(diff, filesToPatch.stream().map(CodePatchFileLoader.LoadedPatchFile::path).toList());
        warnings.addAll(validation.warnings());
        if (!validation.valid()) {
            CodeAgentPatchResponse fallback = deterministicAppendPatch(safeInstruction, filesToPatch, warnings);
            if (fallback != null) {
                return fallback;
            }
            return new CodeAgentPatchResponse(
                    "Generated patch did not pass server validation.",
                    List.of(),
                    "high",
                    List.copyOf(warnings),
                    List.of(),
                    false
            );
        }
        List<PatchFileDiff> files = changedPaths(diff).stream()
                .map(path -> new PatchFileDiff(path, diff))
                .toList();
        return new CodeAgentPatchResponse(
                "Generated a server-validated unified diff. It has not been applied.",
                files,
                files.size() > 1 ? "medium" : "low",
                List.copyOf(warnings),
                testSuggestions(filesToPatch),
                true
        );
    }
    private CodeAgentPlanResponse tryLlmPlan(String instruction, List<String> candidatePaths, List<CodeSearchResult> evidence, List<String> warnings) {
        try {
            String response = ollamaClient.chatResult(planSystemPrompt(), planUserPrompt(instruction, candidatePaths, evidence), 700).content();
            JsonNode root = objectMapper.readTree(cleanJson(response));
            List<PatchTargetFile> targets = new ArrayList<>();
            Set<String> candidates = new LinkedHashSet<>(candidatePaths);
            for (JsonNode node : root.path("targetFiles")) {
                String path = safe(node.path("path").asText());
                if (candidates.contains(path) && !fileLoader.isSensitiveOrUnsafe(path)) {
                    targets.add(new PatchTargetFile(path, firstNonBlank(node.path("reason").asText(), "Selected by patch plan.")));
                }
                if (targets.size() >= MAX_PLAN_TARGETS) {
                    break;
                }
            }
            if (targets.isEmpty()) {
                return null;
            }
            return new CodeAgentPlanResponse(
                    firstNonBlank(root.path("intent").asText(), intent(instruction)),
                    firstNonBlank(root.path("summary").asText(), "?섏젙 怨꾪쉷???앹꽦?덉뒿?덈떎."),
                    List.copyOf(targets),
                    textArray(root.path("changePlan")),
                    risk(root.path("riskLevel").asText()),
                    root.path("needsMoreContext").asBoolean(false),
                    List.copyOf(warnings),
                    evidence(evidence)
            );
        } catch (Exception ex) {
            return null;
        }
    }

    private CodeAgentPatchResponse deterministicAppendPatch(
            String instruction,
            List<CodePatchFileLoader.LoadedPatchFile> files,
            List<String> warnings
    ) {
        if (!looksLikeAppendToEndRequest(instruction) || files == null || files.size() != 1) {
            return null;
        }
        CodePatchFileLoader.LoadedPatchFile file = files.get(0);
        String diff = appendDiff(file.path(), file.content(), appendTextForInstruction(instruction));
        PatchValidationResult validation = validationService.validate(diff, List.of(file.path()));
        warnings.add("Deterministic append fallback was used after model patch generation was unavailable or invalid.");
        warnings.addAll(validation.warnings());
        if (!validation.valid()) {
            return null;
        }
        return new CodeAgentPatchResponse(
                "Generated a server-validated append patch from the local file.read observation.",
                List.of(new PatchFileDiff(file.path(), diff)),
                "low",
                List.copyOf(warnings),
                testSuggestions(files),
                true
        );
    }

    private boolean looksLikeAppendToEndRequest(String instruction) {
        String lower = safe(instruction).toLowerCase(Locale.ROOT);
        boolean append = lower.contains("append")
                || lower.contains("add")
                || lower.contains("\uCD94\uAC00")
                || lower.contains("\uB05D")
                || lower.contains("\uB9C8\uC9C0\uB9C9");
        boolean end = lower.contains("end")
                || lower.contains("bottom")
                || lower.contains("\uB05D")
                || lower.contains("\uB9C8\uC9C0\uB9C9");
        return append && end;
    }

    private String appendTextForInstruction(String instruction) {
        String lower = safe(instruction).toLowerCase(Locale.ROOT);
        if (lower.contains("poem") || lower.contains("\uC2DC")) {
            return DEFAULT_KOREAN_POEM_APPEND.stripTrailing() + "\n";
        }
        return "Added by LearnBot.\n";
    }

    private String appendDiff(String path, String currentContent, String appendedText) {
        String cleanPath = safe(path).replace('\\', '/');
        String normalized = safe(currentContent).replace("\r\n", "\n").replace('\r', '\n');
        List<String> appendedLines = safe(appendedText).replace("\r\n", "\n").replace('\r', '\n').lines().toList();
        StringBuilder builder = new StringBuilder();
        builder.append("--- a/").append(cleanPath).append("\n");
        builder.append("+++ b/").append(cleanPath).append("\n");
        if (normalized.isEmpty()) {
            builder.append("@@ -0,0 +1,").append(appendedLines.size()).append(" @@\n");
        } else {
            String withoutFinalNewline = normalized.endsWith("\n")
                    ? normalized.substring(0, normalized.length() - 1)
                    : normalized;
            List<String> existingLines = withoutFinalNewline.lines().toList();
            String contextLine = existingLines.isEmpty() ? "" : existingLines.get(existingLines.size() - 1);
            int lineNumber = Math.max(existingLines.size(), 1);
            builder.append("@@ -").append(lineNumber).append(",1 +").append(lineNumber).append(",")
                    .append(appendedLines.size() + 1).append(" @@\n");
            builder.append(" ").append(contextLine).append("\n");
        }
        for (String line : appendedLines) {
            builder.append("+").append(line).append("\n");
        }
        return builder.toString().trim();
    }

    private List<String> candidatePaths(List<CodeSearchResult> evidence, List<String> warnings) {
        Map<String, String> paths = new LinkedHashMap<>();
        for (CodeSearchResult result : evidence == null ? List.<CodeSearchResult>of() : evidence) {
            String path = safe(result.filePath());
            if (path.isBlank()) {
                continue;
            }
            String rejection = fileLoader.rejectionReason(path);
            if (rejection != null) {
                warnings.add(rejection + ": " + path);
                continue;
            }
            paths.putIfAbsent(path, path);
            if (paths.size() >= 8) {
                break;
            }
        }
        return List.copyOf(paths.values());
    }

    private List<CodeEvidence> evidence(List<CodeSearchResult> results) {
        List<CodeEvidence> evidence = new ArrayList<>();
        int index = 1;
        for (CodeSearchResult result : results == null ? List.<CodeSearchResult>of() : results) {
            evidence.add(new CodeEvidence(
                    index++,
                    result.chunkId(),
                    result.repositoryId(),
                    result.fileId(),
                    result.repositoryName(),
                    result.filePath(),
                    result.chunkType(),
                    result.symbolName(),
                    result.className(),
                    result.methodName(),
                    result.controlName(),
                    result.eventName(),
                    result.lineStart(),
                    result.lineEnd(),
                    preview(result.content()),
                    result.score(),
                    result.metadata() == null ? Map.of() : result.metadata()
            ));
            if (evidence.size() >= PLAN_SEARCH_LIMIT) {
                break;
            }
        }
        return List.copyOf(evidence);
    }

    private String planSystemPrompt() {
        return """
                You create safe code patch plans for LearnBot.
                Return JSON only.
                Select targetFiles only from the provided candidate file list.
                Select at most 3 target files.
                Do not write code or diff in the plan.
                If uncertain, set needsMoreContext=true.
                """;
    }

    private String planUserPrompt(String instruction, List<String> candidatePaths, List<CodeSearchResult> evidence) {
        StringBuilder builder = new StringBuilder();
        builder.append("Instruction:\n").append(instruction).append("\n\nCandidate files:\n");
        candidatePaths.forEach(path -> builder.append("- ").append(path).append("\n"));
        builder.append("\nEvidence:\n");
        int index = 1;
        for (CodeSearchResult result : evidence) {
            builder.append("[").append(index++).append("] ")
                    .append(result.filePath()).append(":")
                    .append(result.lineStart()).append("-").append(result.lineEnd()).append("\n")
                    .append(preview(result.content())).append("\n");
            if (index > 8) {
                break;
            }
        }
        builder.append("""

                JSON shape:
                {"intent":"bugfix|feature|refactor|test|docs|unknown","summary":"...","targetFiles":[{"path":"...","reason":"..."}],"changePlan":["..."],"riskLevel":"low|medium|high","needsMoreContext":false}
                """);
        return builder.toString();
    }

    private String patchSystemPrompt() {
        return """
                You generate safe unified diffs for LearnBot Patch Agent v1.
                Output unified diff only.
                Do not use markdown fences.
                Modify only the provided target files.
                Do not create, delete, rename, or chmod files.
                Preserve the existing style.
                If a safe patch cannot be produced, output:
                NO_PATCH
                reason: ...
                """;
    }

    private String patchUserPrompt(String instruction, List<CodePatchFileLoader.LoadedPatchFile> files) {
        StringBuilder builder = new StringBuilder();
        builder.append("Instruction:\n").append(instruction).append("\n\nTarget files:\n");
        for (CodePatchFileLoader.LoadedPatchFile file : files) {
            builder.append("FILE: ").append(file.path()).append("\n")
                    .append("LANGUAGE: ").append(file.language()).append("\n")
                    .append("CONTENT:\n")
                    .append(file.content()).append("\n\n");
        }
        return builder.toString();
    }

    private String cleanJson(String value) {
        String clean = safe(value).trim();
        if (clean.startsWith("```")) {
            clean = clean.replaceFirst("^```[A-Za-z]*\\s*", "");
            clean = clean.replaceFirst("\\s*```$", "");
        }
        int start = clean.indexOf('{');
        int end = clean.lastIndexOf('}');
        return start >= 0 && end > start ? clean.substring(start, end + 1) : clean;
    }

    private String cleanDiff(String value) {
        String clean = safe(value).replace("\r\n", "\n").trim();
        if (clean.startsWith("```")) {
            clean = clean.replaceFirst("^```(?:diff|patch)?\\s*", "");
            clean = clean.replaceFirst("\\s*```$", "");
        }
        return clean.trim();
    }

    private List<String> changedPaths(String diff) {
        Set<String> paths = new LinkedHashSet<>();
        safe(diff).lines()
                .filter(line -> line.startsWith("+++ b/"))
                .map(line -> line.substring("+++ b/".length()).trim().split("\\s+", 2)[0])
                .forEach(paths::add);
        return List.copyOf(paths);
    }

    private List<String> textArray(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String value = item.asText();
                if (value != null && !value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values.isEmpty() ? List.of("?좎젙??target file??理쒖냼 蹂寃쎌쑝濡??섏젙?⑸땲??") : List.copyOf(values);
    }

    private List<String> testSuggestions(List<CodePatchFileLoader.LoadedPatchFile> files) {
        boolean frontend = files.stream().anyMatch(file -> file.path().startsWith("frontend/") || file.path().endsWith(".jsx") || file.path().endsWith(".tsx"));
        boolean backend = files.stream().anyMatch(file -> file.path().startsWith("backend/") || file.path().endsWith(".java"));
        List<String> suggestions = new ArrayList<>();
        if (backend) {
            suggestions.add("mvn test");
        }
        if (frontend) {
            suggestions.add("npm run build");
        }
        return suggestions.isEmpty() ? List.of("Run the closest project test for the changed file.") : List.copyOf(suggestions);
    }

    private String intent(String instruction) {
        String lower = safe(instruction).toLowerCase(Locale.ROOT);
        if (lower.contains("test")) return "test";
        if (lower.contains("refactor")) return "refactor";
        if (lower.contains("fix") || lower.contains("bug") || lower.contains("?섏젙") || lower.contains("踰꾧렇")) return "bugfix";
        if (lower.contains("docs") || lower.contains("臾몄꽌")) return "docs";
        return "feature";
    }

    private String risk(String value) {
        String lower = safe(value).toLowerCase(Locale.ROOT);
        return lower.equals("low") || lower.equals("medium") || lower.equals("high") ? lower : "medium";
    }

    private String preview(String value) {
        String clean = safe(value).replaceAll("\\s+", " ").trim();
        return clean.length() <= 360 ? clean : clean.substring(0, 360) + "...";
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
