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
                    "관련 코드 근거가 부족해 안전한 수정 계획을 만들 수 없습니다.",
                    List.of(),
                    List.of("질문 범위를 좁히거나 파일명, 클래스명, 메서드명을 추가해 주세요."),
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
                "검색 근거를 기준으로 수정 후보 파일을 선정했습니다.",
                targets,
                List.of(
                        "선정된 파일의 현재 구현을 확인합니다.",
                        "요청한 동작과 직접 관련된 최소 변경만 제안합니다.",
                        "diff 생성 후 서버 검증을 통과한 unified diff만 반환합니다."
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
        if (loaded.files().isEmpty()) {
            return new CodeAgentPatchResponse(
                    "수정 대상 파일을 안전하게 로드하지 못했습니다.",
                    List.of(),
                    "high",
                    List.copyOf(warnings),
                    List.of(),
                    false
            );
        }
        String diff;
        try {
            diff = cleanDiff(ollamaClient.chatResult(
                    patchSystemPrompt(),
                    patchUserPrompt(safeInstruction, loaded.files()),
                    1800
            ).content());
        } catch (RuntimeException ex) {
            warnings.add("LLM patch generation failed: " + ex.getMessage());
            return new CodeAgentPatchResponse(
                    "패치 생성 모델 호출이 실패했습니다.",
                    List.of(),
                    "high",
                    List.copyOf(warnings),
                    List.of(),
                    false
            );
        }
        PatchValidationResult validation = validationService.validate(diff, loaded.files().stream().map(CodePatchFileLoader.LoadedPatchFile::path).toList());
        warnings.addAll(validation.warnings());
        if (!validation.valid()) {
            return new CodeAgentPatchResponse(
                    "생성된 patch가 서버 검증을 통과하지 못했습니다.",
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
                "서버 검증을 통과한 unified diff를 생성했습니다. 자동 적용은 수행하지 않았습니다.",
                files,
                files.size() > 1 ? "medium" : "low",
                List.copyOf(warnings),
                testSuggestions(loaded.files()),
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
                    firstNonBlank(root.path("summary").asText(), "수정 계획을 생성했습니다."),
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
        return values.isEmpty() ? List.of("선정된 target file을 최소 변경으로 수정합니다.") : List.copyOf(values);
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
        if (lower.contains("fix") || lower.contains("bug") || lower.contains("수정") || lower.contains("버그")) return "bugfix";
        if (lower.contains("docs") || lower.contains("문서")) return "docs";
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
