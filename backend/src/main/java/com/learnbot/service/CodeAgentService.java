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

import java.nio.charset.StandardCharsets;
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
    private static final int LLM_DIAGNOSTIC_PREVIEW_CHARS = 2000;
    private static final int PREVIOUS_PATCH_OUTPUT_PREVIEW_CHARS = 1200;
    private static final int VALIDATION_WARNING_PREVIEW_CHARS = 600;
    private static final int PATCH_OUTPUT_TOKENS = 4096;
    private static final int PATCH_REPAIR_ATTEMPTS = 3;
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
                    "관련 코드 근거를 찾지 못했습니다. 저장소 인덱싱 상태와 질문 범위를 확인하세요.",
                    List.of(),
                    List.of("검색어를 더 구체화하거나 관련 저장소를 다시 인덱싱한 뒤 수정 후보를 다시 생성하세요."),
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
        warnings.add("LLM plan JSON parsing failed or was unavailable; server-authored target selection fallback is disabled.");
        return new CodeAgentPlanResponse(
                intent(safeInstruction),
                "모델 계획 응답을 해석하지 못했습니다. 안전을 위해 서버가 임의로 수정 대상 파일을 선택하지 않습니다.",
                List.of(),
                List.of(
                        "후보 파일 목록을 만들 수 없어 적용 전 diff 초안 생성을 중단했습니다.",
                        "질문을 더 구체화하거나 관련 파일명을 포함해 다시 요청하세요.",
                        "diff 초안은 모델 계획이 정상 파싱된 뒤에만 생성됩니다."
                ),
                "medium",
                true,
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

    public CodeAgentPatchResponse patchFromLoadedFilesInBatches(String instruction, List<CodePatchFileLoader.LoadedPatchFile> loadedFiles) {
        String safeInstruction = safe(instruction);
        List<CodePatchFileLoader.LoadedPatchFile> filesToPatch = loadedFiles == null ? List.of() : loadedFiles;
        if (filesToPatch.size() <= 1) {
            return null;
        }
        List<String> warnings = new ArrayList<>();
        warnings.add("LLM patch batch orchestration attempted; server-authored patch content remains disabled.");
        List<PatchBatchPlanItem> plan = oneFilePatchBatches(tryLlmPatchBatchPlan(safeInstruction, filesToPatch, warnings), warnings);
        if (plan.isEmpty()) {
            warnings.add("LLM batch plan was unavailable; using file-boundary batches only to reduce output size. The LLM still authors every patch body.");
            plan = filesToPatch.stream()
                    .map(file -> new PatchBatchPlanItem("batch-" + (filesToPatch.indexOf(file) + 1), List.of(file.path()), "Update this file for the user request.", "file-boundary fallback"))
                    .toList();
        }
        List<PatchBatchResult> batchResults = new ArrayList<>();
        List<String> combinedWarnings = new ArrayList<>(warnings);
        for (int index = 0; index < plan.size(); index++) {
            PatchBatchPlanItem item = plan.get(index);
            List<CodePatchFileLoader.LoadedPatchFile> batchFiles = batchFiles(filesToPatch, item.targetFiles());
            if (batchFiles.isEmpty()) {
                combinedWarnings.add("Patch batch " + item.id() + " skipped because it did not reference loaded files: " + item.targetFiles());
                continue;
            }
            String batchInstruction = patchBatchInstruction(safeInstruction, item, index + 1, plan.size());
            CodeAgentPatchResponse response = patchLoadedFiles(batchInstruction, batchFiles, new ArrayList<>());
            batchResults.add(new PatchBatchResult(item.id(), item.targetFiles(), response));
            combinedWarnings.add("Patch batch " + item.id() + " targeted " + item.targetFiles() + " and valid=" + (response != null && response.valid()) + ".");
            if (response != null && response.warnings() != null) {
                response.warnings().stream()
                        .map(warning -> "batch " + item.id() + ": " + warning)
                        .forEach(combinedWarnings::add);
            }
            if (response == null || !response.valid() || response.files() == null || response.files().isEmpty()) {
                return new CodeAgentPatchResponse(
                        "Patch batch " + item.id() + " did not produce a valid unified diff.",
                        List.of(),
                        "high",
                        List.copyOf(combinedWarnings),
                        List.of(),
                        false
                );
            }
        }
        List<PatchFileDiff> combinedFiles = combineBatchPatchFiles(batchResults);
        if (combinedFiles.isEmpty()) {
            return new CodeAgentPatchResponse(
                    "Patch batch orchestration did not produce any usable diff.",
                    List.of(),
                    "high",
                    List.copyOf(combinedWarnings),
                    List.of(),
                    false
            );
        }
        combinedWarnings.add("Patch batches were composed into one approval proposal; the user still approves the grouped patch once.");
        return new CodeAgentPatchResponse(
                "Generated a grouped patch proposal from " + batchResults.size() + " validated LLM-authored patch batch(es). It has not been applied.",
                combinedFiles,
                combinedFiles.size() > 1 ? "medium" : "low",
                List.copyOf(combinedWarnings),
                testSuggestions(filesToPatch, combinedFiles.stream().map(PatchFileDiff::path).toList()),
                true
        );
    }

    public CodeAgentPatchResponse repairPatchFromLoadedFiles(
            String instruction,
            List<CodePatchFileLoader.LoadedPatchFile> loadedFiles,
            String previousPatchOutput,
            List<String> validationWarnings
    ) {
        List<String> warnings = new ArrayList<>();
        warnings.add("LLM patch integrity repair requested after approval preflight rejected the previous patch.");
        CodeAgentPatchResponse repaired = tryRepairLlmPatch(
                safe(instruction),
                loadedFiles == null ? List.of() : loadedFiles,
                previousPatchOutput,
                validationWarnings == null ? List.of() : validationWarnings,
                warnings
        );
        if (repaired != null) {
            return repaired;
        }
        return new CodeAgentPatchResponse(
                "Patch integrity repair did not produce a valid unified diff.",
                List.of(),
                "high",
                List.copyOf(warnings),
                List.of(),
                false
        );
    }

    private CodeAgentPatchResponse patchLoadedFiles(
            String safeInstruction,
            List<CodePatchFileLoader.LoadedPatchFile> loadedFiles,
            List<String> warnings
    ) {
        List<CodePatchFileLoader.LoadedPatchFile> filesToPatch = loadedFiles == null ? List.of() : loadedFiles;
        if (filesToPatch.isEmpty()) {
            warnings.add("No existing files were selected; LLM patch generation may create new safe workspace files if the user request requires it.");
        }
        OllamaClient.ChatResult modelResult;
        String modelOutput;
        String diff;
        try {
            warnings.add("LLM patch generation attempted; server-authored patch content is disabled.");
            modelResult = patchChatResult(
                    patchSystemPrompt(),
                    patchUserPrompt(safeInstruction, filesToPatch),
                    warnings,
                    "initial"
            );
            modelOutput = modelResult.content();
            diff = materializePatchFromModelOutput(modelOutput, filesToPatch, warnings, "initial");
            diff = normalizePatchDiffHeaders(diff, filesToPatch, warnings, "initial");
            diff = normalizePatchDiffExistingLineWhitespace(diff, filesToPatch, warnings, "initial");
            diff = normalizePatchDiffAbsentContextLinesAfterAdditions(diff, filesToPatch, warnings, "initial");
            if (diff.isBlank() || diff.startsWith("NO_PATCH")) {
                warnings.add("LLM patch generation returned no patch.");
                if (modelResult.stoppedByLength()) {
                    warnings.add("LLM patch initial stopped by length; repair will use compact context and a bounded previous-output preview.");
                }
                addLlmPatchOutputDiagnostics(warnings, "initial", modelResult, diff);
                List<String> noPatchRepairWarnings = new ArrayList<>();
                noPatchRepairWarnings.add("Initial model output returned no patch. The provided file contents are the actual current workspace state; if the file appears incomplete or truncated, treat that as the bug and produce a minimal unified diff when it satisfies the user request.");
                noPatchRepairWarnings.addAll(materializationFailureWarnings(warnings));
                CodeAgentPatchResponse repaired = tryRepairLlmPatch(
                        safeInstruction,
                        filesToPatch,
                        boundedPreviousPatchOutput(modelOutput),
                        noPatchRepairWarnings,
                        warnings
                );
                if (repaired != null) {
                    return repaired;
                }
                CodeAgentPatchResponse fallback = deterministicSafeFallbackPatch(safeInstruction, filesToPatch, warnings);
                if (fallback != null) {
                    return fallback;
                }
                return new CodeAgentPatchResponse(
                        "Patch generation model returned no patch.",
                        List.of(),
                        "high",
                        List.copyOf(warnings),
                        List.of(),
                        false
                );
            }
        } catch (RuntimeException ex) {
            warnings.add("LLM patch generation failed: " + ex.getMessage());
            CodeAgentPatchResponse fallback = deterministicSafeFallbackPatch(safeInstruction, filesToPatch, warnings);
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
        if (looksLikeUnifiedDiffEnvelope(diff) && !hasPatchMutationLines(diff)) {
            warnings.add("LLM patch generation produced a diff with no added or removed lines; LLM repair will be attempted before approval.");
            addLlmPatchOutputDiagnostics(warnings, "initial", modelResult, diff);
            CodeAgentPatchResponse repaired = tryRepairLlmPatch(
                    safeInstruction,
                    filesToPatch,
                    diff,
                    List.of("Patch contains no added or removed file-content lines. Produce a real minimal unified diff against the exact current file contents."),
                    warnings
            );
            if (repaired != null) {
                return repaired;
            }
            return new CodeAgentPatchResponse(
                    "Generated patch contained no file changes.",
                    List.of(),
                    "high",
                    List.copyOf(warnings),
                    List.of(),
                    false
            );
        }
        if (!looksLikeFormattingOnlyRequest(safeInstruction) && isWhitespaceOnlyPatch(diff)) {
            warnings.add("LLM patch generation produced only whitespace changes for a non-formatting request; LLM repair will be attempted before approval.");
            addLlmPatchOutputDiagnostics(warnings, "initial", modelResult, diff);
            CodeAgentPatchResponse repaired = tryRepairLlmPatch(
                    safeInstruction,
                    filesToPatch,
                    diff,
                    List.of("Patch only changes whitespace, but the instruction asks for behavioral/content repair. Produce a meaningful minimal unified diff against the exact current file contents."),
                    warnings
            );
            if (repaired != null) {
                return repaired;
            }
            return new CodeAgentPatchResponse(
                    "Generated patch only changed whitespace for a non-formatting request.",
                    List.of(),
                    "high",
                    List.copyOf(warnings),
                    List.of(),
                    false
            );
        }
        List<String> validationTargets = validationTargetsForPatch(diff, filesToPatch);
        PatchValidationResult validation = validationService.validate(diff, validationTargets, safeInstruction);
        warnings.addAll(validation.warnings());
        if (!validation.valid()) {
            warnings.add("LLM patch generation produced an invalid diff; LLM repair will be attempted before any deterministic fallback.");
            addLlmPatchOutputDiagnostics(warnings, "initial", modelResult, diff);
            CodeAgentPatchResponse repaired = tryRepairLlmPatch(safeInstruction, filesToPatch, boundedPreviousPatchOutput(modelOutput), validation.warnings(), warnings);
            if (repaired != null) {
                return repaired;
            }
            CodeAgentPatchResponse fallback = modelFullFileReplacementPatch(modelOutput, filesToPatch, warnings, "initial");
            if (fallback != null) {
                return fallback;
            }
            fallback = deterministicSafeFallbackPatch(safeInstruction, filesToPatch, warnings);
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
        PatchContextValidationResult contextValidation = validatePatchContext(diff, filesToPatch);
        warnings.addAll(contextValidation.warnings());
        if (!contextValidation.valid()) {
            warnings.add("LLM patch generation produced a diff whose hunk context did not match current file contents; LLM repair will be attempted before approval.");
            addLlmPatchOutputDiagnostics(warnings, "initial", modelResult, diff);
            CodeAgentPatchResponse repaired = tryRepairLlmPatch(safeInstruction, filesToPatch, diff, contextValidation.warnings(), warnings);
            if (repaired != null) {
                return repaired;
            }
            return new CodeAgentPatchResponse(
                    "Generated patch did not match current file contents.",
                    List.of(),
                    "high",
                    List.copyOf(warnings),
                    List.of(),
                    false
            );
        }
        PatchContextValidationResult semanticValidation = validatePatchResultSemantics(diff, filesToPatch);
        warnings.addAll(semanticValidation.warnings());
        if (!semanticValidation.valid()) {
            warnings.add("LLM patch generation produced a structurally invalid result after simulated apply; LLM repair will be attempted before approval.");
            addLlmPatchOutputDiagnostics(warnings, "initial", modelResult, diff);
            CodeAgentPatchResponse repaired = tryRepairLlmPatch(safeInstruction, filesToPatch, diff, semanticValidation.warnings(), warnings);
            if (repaired != null) {
                return repaired;
            }
            return new CodeAgentPatchResponse(
                    "Generated patch produced a structurally invalid result.",
                    List.of(),
                    "high",
                    List.copyOf(warnings),
                    List.of(),
                    false
            );
        }
        String validatedDiff = diff;
        List<PatchFileDiff> files = changedPaths(validatedDiff).stream()
                .map(path -> new PatchFileDiff(path, validatedDiff))
                .toList();
        return new CodeAgentPatchResponse(
                "Generated a server-validated unified diff. It has not been applied.",
                files,
                files.size() > 1 ? "medium" : "low",
                List.copyOf(warnings),
                testSuggestions(filesToPatch, changedPaths(validatedDiff)),
                true
        );
    }

    private CodeAgentPatchResponse modelFullFileReplacementPatch(
            String modelOutput,
            List<CodePatchFileLoader.LoadedPatchFile> files,
            List<String> warnings,
            String phase
    ) {
        if (files == null || files.size() != 1) {
            return null;
        }
        CodePatchFileLoader.LoadedPatchFile file = files.get(0);
        if (looksLikeStructuredJsonPatchEnvelope(modelOutput)) {
            warnings.add("LLM patch " + phase + " full-file fallback rejected JSON-like patch envelope output.");
            return null;
        }
        String replacement = cleanFullFileModelOutput(modelOutput);
        if (!looksLikeSafeFullFileReplacement(file, replacement)) {
            return null;
        }
        if (safe(file.content()).replace("\r\n", "\n").replace('\r', '\n').trim()
                .equals(replacement.replace("\r\n", "\n").replace('\r', '\n').trim())) {
            return null;
        }
        String diff = fullFileReplacementDiff(file.path(), file.content(), replacement);
        PatchValidationResult validation = validationService.validate(diff, List.of(file.path()));
        warnings.add("Model returned full-file content instead of a diff; converted it to a validated unified diff.");
        warnings.addAll(validation.warnings());
        if (!validation.valid()) {
            return null;
        }
        PatchContextValidationResult contextValidation = validatePatchContext(diff, files);
        warnings.addAll(contextValidation.warnings());
        if (!contextValidation.valid()) {
            return null;
        }
        PatchContextValidationResult semanticValidation = validatePatchResultSemantics(diff, files);
        warnings.addAll(semanticValidation.warnings());
        if (!semanticValidation.valid()) {
            warnings.add("Model full-file fallback produced a structurally invalid result after simulated apply.");
            return null;
        }
        return new CodeAgentPatchResponse(
                "Converted model full-file output into a server-validated unified diff.",
                List.of(new PatchFileDiff(file.path(), diff)),
                "medium",
                List.copyOf(warnings),
                testSuggestions(files),
                true
        );
    }

    private List<PatchBatchPlanItem> oneFilePatchBatches(List<PatchBatchPlanItem> plan, List<String> warnings) {
        if (plan == null || plan.isEmpty()) {
            return List.of();
        }
        List<PatchBatchPlanItem> normalized = new ArrayList<>();
        boolean split = false;
        for (PatchBatchPlanItem item : plan) {
            List<String> targetFiles = item.targetFiles() == null ? List.of() : item.targetFiles().stream()
                    .filter(path -> path != null && !path.isBlank())
                    .distinct()
                    .toList();
            if (targetFiles.size() <= 1) {
                normalized.add(item);
                continue;
            }
            split = true;
            for (String path : targetFiles) {
                normalized.add(new PatchBatchPlanItem(
                        item.id() + "-" + (normalized.size() + 1),
                        List.of(path),
                        item.goal(),
                        item.rationale()
                ));
            }
        }
        if (split) {
            warnings.add("LLM batch plan contained multi-file batches; server split them into one-file patch-generation batches to avoid oversized JSON output. The LLM still authors each batch patch body.");
        }
        return List.copyOf(normalized);
    }

    private List<PatchBatchPlanItem> tryLlmPatchBatchPlan(
            String instruction,
            List<CodePatchFileLoader.LoadedPatchFile> files,
            List<String> warnings
    ) {
        try {
            OllamaClient.ChatResult result = ollamaClient.chatResult(
                    patchBatchPlanSystemPrompt(),
                    patchBatchPlanUserPrompt(instruction, files),
                    1200
            );
            JsonNode root = objectMapper.readTree(cleanJson(result.content()));
            JsonNode batches = root.path("batches");
            if (!batches.isArray()) {
                warnings.add("LLM batch plan response did not contain a batches array.");
                return List.of();
            }
            Set<String> loadedPaths = files.stream()
                    .map(CodePatchFileLoader.LoadedPatchFile::path)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            List<PatchBatchPlanItem> planned = new ArrayList<>();
            for (JsonNode batch : batches) {
                List<String> targetFiles = textArrayOrEmpty(batch.path("targetFiles")).stream()
                        .filter(loadedPaths::contains)
                        .distinct()
                        .toList();
                if (targetFiles.isEmpty()) {
                    continue;
                }
                planned.add(new PatchBatchPlanItem(
                        firstNonBlank(batch.path("id").asText(null), "batch-" + (planned.size() + 1)),
                        targetFiles,
                        firstNonBlank(batch.path("goal").asText(null), "Apply the user-requested change for this batch."),
                        firstNonBlank(batch.path("rationale").asText(null), "")
                ));
                if (planned.size() >= files.size()) {
                    break;
                }
            }
            if (!planned.isEmpty()) {
                warnings.add("LLM batch plan selected " + planned.size() + " patch batch(es).");
            }
            return List.copyOf(planned);
        } catch (RuntimeException | java.io.IOException ex) {
            warnings.add("LLM batch plan parsing failed: " + ex.getMessage());
            return List.of();
        }
    }

    private String patchBatchPlanSystemPrompt() {
        return """
                You are LearnBot Patch Batch Planner.
                Return JSON only.
                Do not write code, diffs, markdown, or explanations.
                Decide how to split the requested code change into small patch-generation batches.
                Keep each batch small enough that a later patch agent can output compact JSON without truncation.
                Prefer one file per batch unless two files must be edited atomically.
                Use only the provided loaded file paths.
                JSON shape:
                {"batches":[{"id":"batch-1","targetFiles":["path"],"goal":"specific edit goal for this batch","rationale":"why these files belong together"}]}
                """;
    }

    private String patchBatchPlanUserPrompt(String instruction, List<CodePatchFileLoader.LoadedPatchFile> files) {
        StringBuilder builder = new StringBuilder();
        builder.append("USER_INSTRUCTION:\n")
                .append(instruction)
                .append("\n\nLOADED_FILES:\n");
        for (CodePatchFileLoader.LoadedPatchFile file : files == null ? List.<CodePatchFileLoader.LoadedPatchFile>of() : files) {
            builder.append("- path: ").append(file.path()).append("\n")
                    .append("  language: ").append(file.language()).append("\n")
                    .append("  lineCount: ").append(lineCount(file.content())).append("\n")
                    .append("  preview: ").append(preview(file.content())).append("\n");
        }
        return builder.toString();
    }

    private String patchBatchInstruction(String instruction, PatchBatchPlanItem item, int batchNumber, int batchCount) {
        return """
                %s

                PATCH_BATCH_SCOPE:
                - batch: %d/%d
                - batchId: %s
                - targetFiles: %s
                - batchGoal: %s
                - batchRationale: %s
                - Produce only this batch's small, self-contained edits.
                - Do not edit files outside this batch.
                - If a later batch should handle related work, leave that work out of this batch.
                - For a small HTML/HTM/XML/SVG batch that adds related navigation and content sections, prefer editFormat=full_file with complete updated file content authored by you instead of multiple anchor insert operations.
                """.formatted(
                instruction,
                batchNumber,
                batchCount,
                item.id(),
                item.targetFiles(),
                item.goal(),
                item.rationale()
        );
    }

    private List<CodePatchFileLoader.LoadedPatchFile> batchFiles(
            List<CodePatchFileLoader.LoadedPatchFile> loadedFiles,
            List<String> targetFiles
    ) {
        Set<String> requested = new LinkedHashSet<>(targetFiles == null ? List.of() : targetFiles);
        return loadedFiles == null
                ? List.of()
                : loadedFiles.stream()
                .filter(file -> requested.contains(file.path()))
                .toList();
    }

    private List<PatchFileDiff> combineBatchPatchFiles(List<PatchBatchResult> batchResults) {
        String combinedDiff = batchResults == null
                ? ""
                : batchResults.stream()
                .map(PatchBatchResult::response)
                .filter(response -> response != null && response.files() != null)
                .flatMap(response -> response.files().stream())
                .map(PatchFileDiff::diff)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .collect(java.util.stream.Collectors.joining("\n"));
        if (combinedDiff.isBlank()) {
            return List.of();
        }
        Set<String> paths = batchResults.stream()
                .map(PatchBatchResult::response)
                .filter(response -> response != null && response.files() != null)
                .flatMap(response -> response.files().stream())
                .map(PatchFileDiff::path)
                .filter(path -> path != null && !path.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return paths.stream()
                .map(path -> new PatchFileDiff(path, combinedDiff))
                .toList();
    }

    private CodeAgentPatchResponse tryRepairLlmPatch(
            String instruction,
            List<CodePatchFileLoader.LoadedPatchFile> files,
            String previousOutput,
            List<String> validationWarnings,
            List<String> warnings
    ) {
        String repairPreviousOutput = previousOutput;
        List<String> repairWarnings = validationWarnings == null ? List.of() : validationWarnings;
        for (int attempt = 1; attempt <= PATCH_REPAIR_ATTEMPTS; attempt++) {
            String phase = attempt == 1 ? "repair" : "repair retry " + (attempt - 1);
            try {
                OllamaClient.ChatResult repairedResult = patchChatResult(
                        patchRepairSystemPrompt(),
                        patchRepairUserPrompt(instruction, files, repairPreviousOutput, repairWarnings, attempt > 1),
                        warnings,
                        phase
                );
                String repairedOutput = repairedResult.content();
                if (repairedResult.stoppedByLength()) {
                    warnings.add("LLM patch " + phase + " stopped by length; retry context will use a compact preview instead of the full truncated output.");
                }
                String repairedDiff = materializePatchFromModelOutput(repairedOutput, files, warnings, phase);
                repairedDiff = normalizePatchDiffHeaders(repairedDiff, files, warnings, phase);
                repairedDiff = normalizePatchDiffExistingLineWhitespace(repairedDiff, files, warnings, phase);
                repairedDiff = normalizePatchDiffAbsentContextLinesAfterAdditions(repairedDiff, files, warnings, phase);
                if (looksLikeUnifiedDiffEnvelope(repairedDiff) && !hasPatchMutationLines(repairedDiff)) {
                    warnings.add("LLM patch " + phase + " output contained no added or removed lines.");
                    addLlmPatchOutputDiagnostics(warnings, phase, repairedResult, repairedDiff);
                    repairPreviousOutput = boundedPreviousPatchOutput(repairedDiff);
                    repairWarnings = List.of("Patch contains no added or removed file-content lines. Produce a real minimal unified diff against the exact current file contents.");
                    continue;
                }
                if (!looksLikeFormattingOnlyRequest(instruction) && isWhitespaceOnlyPatch(repairedDiff)) {
                    warnings.add("LLM patch " + phase + " output only changed whitespace for a non-formatting request.");
                    addLlmPatchOutputDiagnostics(warnings, phase, repairedResult, repairedDiff);
                    repairPreviousOutput = boundedPreviousPatchOutput(repairedDiff);
                    repairWarnings = List.of("Patch only changes whitespace, but the instruction asks for behavioral/content repair. Produce a meaningful minimal unified diff against the exact current file contents.");
                    continue;
                }
                PatchValidationResult repairedValidation = validationService.validate(
                        repairedDiff,
                        validationTargetsForPatch(repairedDiff, files),
                        instruction
                );
                warnings.add(attempt == 1
                        ? "LLM patch repair attempted after invalid initial diff."
                        : "LLM patch repair retry attempted after the first repair still did not match current file contents.");
                warnings.addAll(repairedValidation.warnings());
                if (!repairedValidation.valid()) {
                    addLlmPatchOutputDiagnostics(warnings, phase, repairedResult, repairedDiff);
                    repairPreviousOutput = boundedPreviousPatchOutput(repairedResult.stoppedByLength() ? repairedOutput : repairedDiff);
                    repairWarnings = repairedValidation.warnings();
                    continue;
                }
                PatchContextValidationResult repairedContextValidation = validatePatchContext(repairedDiff, files);
                warnings.addAll(repairedContextValidation.warnings());
                if (!repairedContextValidation.valid()) {
                    addLlmPatchOutputDiagnostics(warnings, phase, repairedResult, repairedDiff);
                    repairPreviousOutput = boundedPreviousPatchOutput(repairedDiff);
                    repairWarnings = repairedContextValidation.warnings();
                    continue;
                }
                PatchContextValidationResult repairedSemanticValidation = validatePatchResultSemantics(repairedDiff, files);
                warnings.addAll(repairedSemanticValidation.warnings());
                if (!repairedSemanticValidation.valid()) {
                    addLlmPatchOutputDiagnostics(warnings, phase, repairedResult, repairedDiff);
                    repairPreviousOutput = boundedPreviousPatchOutput(repairedDiff);
                    repairWarnings = repairedSemanticValidation.warnings();
                    continue;
                }
                String validatedRepairedDiff = repairedDiff;
                List<PatchFileDiff> patchFiles = changedPaths(validatedRepairedDiff).stream()
                        .map(path -> new PatchFileDiff(path, validatedRepairedDiff))
                        .toList();
                return new CodeAgentPatchResponse(
                        "Generated a server-validated unified diff after LLM repair. It has not been applied.",
                        patchFiles,
                        patchFiles.size() > 1 ? "medium" : "low",
                        List.copyOf(warnings),
                        testSuggestions(files),
                        true
                );
            } catch (RuntimeException ex) {
                warnings.add("LLM patch " + phase + " failed: " + ex.getMessage());
                return null;
            }
        }
        return null;
    }

    private List<String> materializationFailureWarnings(List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return List.of();
        }
        return warnings.stream()
                .filter(warning -> {
                    String clean = safe(warning);
                    return clean.contains("insert operation repeated its anchor")
                            || clean.contains("repeated its anchor text inside newText")
                            || clean.contains("JSON proposal parsing failed")
                            || clean.contains("malformed JSON patch proposal")
                            || clean.contains("did not contain materializable edits")
                            || clean.contains("operation_edit targeted an unloaded file")
                            || clean.contains("operation_edit anchor did not match exactly once");
                })
                .distinct()
                .toList();
    }

    private String cleanFullFileModelOutput(String value) {
        String clean = safe(value).replace("\r\n", "\n").trim();
        if (clean.startsWith("```")) {
            clean = clean.replaceFirst("^```[A-Za-z0-9_-]*\\s*", "");
            clean = clean.replaceFirst("\\s*```$", "");
        }
        clean = stripMalformedFullFileDiffHeader(clean);
        return clean.trim();
    }

    private boolean looksLikeStructuredJsonPatchEnvelope(String value) {
        String clean = safe(value).replace("\r\n", "\n").replace('\r', '\n').trim();
        if (clean.startsWith("```")) {
            clean = clean.replaceFirst("^```[A-Za-z0-9_-]*\\s*", "").trim();
        }
        String lower = clean.toLowerCase(Locale.ROOT);
        return (clean.startsWith("{") || clean.startsWith("["))
                && (lower.contains("\"action\"")
                || lower.contains("\"editformat\"")
                || lower.contains("\"targetfiles\"")
                || lower.contains("\"fullfilecontent\"")
                || lower.contains("\"unifieddiff\"")
                || lower.contains("\"edits\""));
    }

    private String stripMalformedFullFileDiffHeader(String value) {
        String clean = safe(value).replace("\r\n", "\n").trim();
        if (!clean.startsWith("--- a/") && !clean.startsWith("--- b/")) {
            return clean;
        }
        int firstNewline = clean.indexOf('\n');
        if (firstNewline < 0) {
            return clean;
        }
        String body = clean.substring(firstNewline + 1).trim();
        if (body.startsWith("+++ b/")) {
            int secondNewline = body.indexOf('\n');
            if (secondNewline < 0) {
                return clean;
            }
            String afterHeaders = body.substring(secondNewline + 1).trim();
            return afterHeaders.contains("@@") ? clean : afterHeaders;
        }
        return body.startsWith("@@") ? clean : body;
    }

    private boolean looksLikeSafeFullFileReplacement(CodePatchFileLoader.LoadedPatchFile file, String replacement) {
        if (file == null || replacement == null || replacement.isBlank() || replacement.startsWith("NO_PATCH")) {
            return false;
        }
        if (looksLikeUnifiedDiff(replacement)) {
            return false;
        }
        if (looksLikeStructuredJsonPatchEnvelope(replacement)) {
            return false;
        }
        if (replacement.length() > 25_000) {
            return false;
        }
        if (fileLoader.isSensitiveOrUnsafe(file.path())) {
            return false;
        }
        return looksLikeFullFileForPath(file.path(), replacement);
    }

    private boolean looksLikeFullFileForPath(String path, String replacement) {
        String extension = extensionForPath(path);
        String lower = safe(replacement).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "html", "htm" -> lower.contains("<!doctype html") || lower.contains("<html") || (lower.contains("<") && lower.contains(">"));
            case "java" -> containsAny(lower, "class ", "interface ", "enum ", "record ", "package ", "import ");
            case "cs" -> containsAny(lower, "namespace ", "class ", "interface ", "record ", "struct ", "using ");
            case "c", "h" -> containsAny(lower, "#include", "int main(", "typedef ", "struct ", "enum ")
                    || (lower.contains("{") && lower.contains("}") && lower.contains(";"));
            case "cpp", "cc", "cxx", "hpp", "hh", "hxx" -> containsAny(lower, "#include", "namespace ", "std::", "template<", "template <", "class ", "struct ")
                    || (lower.contains("{") && lower.contains("}") && lower.contains(";"));
            case "js", "jsx", "mjs", "cjs" -> containsAny(lower, "import ", "export ", "function ", "const ", "let ", "var ", "=>")
                    || (extension.equals("jsx") && lower.contains("<") && lower.contains(">"));
            case "ts", "tsx" -> containsAny(lower, "import ", "export ", "interface ", "type ", "function ", "const ", "let ", "=>")
                    || (extension.equals("tsx") && lower.contains("<") && lower.contains(">"));
            case "json" -> parsesAsJson(replacement);
            case "yaml", "yml" -> lower.contains(":") && !lower.contains("\u0000");
            case "xml", "svg" -> lower.trim().startsWith("<") && lower.contains(">");
            case "css", "scss", "sass", "less" -> lower.contains("{") && lower.contains("}") && lower.contains(":");
            case "md", "markdown", "txt" -> !looksLikeChattyPatchExplanation(lower);
            case "py" -> containsAny(lower, "def ", "class ", "import ", "from ", "if __name__");
            case "kt", "kts" -> containsAny(lower, "package ", "import ", "class ", "fun ", "val ", "var ");
            case "go" -> containsAny(lower, "package ", "import ", "func ", "type ");
            case "rs" -> containsAny(lower, "fn ", "use ", "mod ", "struct ", "enum ", "impl ");
            case "php" -> lower.contains("<?php") || containsAny(lower, "namespace ", "class ", "function ");
            case "rb" -> containsAny(lower, "def ", "class ", "module ", "require ");
            case "swift" -> containsAny(lower, "import ", "class ", "struct ", "func ", "let ", "var ");
            case "sh", "bash", "ps1" -> containsAny(lower, "#!", "function ", "param(", "$", "echo ");
            case "sql" -> containsAny(lower, "select ", "insert ", "update ", "delete ", "create ", "alter ");
            case "properties", "ini", "toml", "gradle", "dockerfile" -> !looksLikeChattyPatchExplanation(lower);
            default -> !replacement.contains("\u0000") && !looksLikeChattyPatchExplanation(lower);
        };
    }

    private String extensionForPath(String path) {
        String normalized = safe(path).replace('\\', '/').toLowerCase(Locale.ROOT);
        String basename = normalized.contains("/") ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
        if (basename.equals("dockerfile")) {
            return "dockerfile";
        }
        int dot = basename.lastIndexOf('.');
        return dot >= 0 && dot < basename.length() - 1 ? basename.substring(dot + 1) : "";
    }

    private boolean parsesAsJson(String value) {
        try {
            objectMapper.readTree(value);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean looksLikeChattyPatchExplanation(String lower) {
        String trimmed = safe(lower).trim();
        return trimmed.startsWith("here is")
                || trimmed.startsWith("here's")
                || trimmed.startsWith("sure")
                || trimmed.startsWith("i ")
                || trimmed.startsWith("the updated")
                || trimmed.contains("```")
                || trimmed.contains("unified diff");
    }

    private boolean looksLikeUnifiedDiff(String value) {
        String normalized = safe(value).replace("\r\n", "\n");
        return normalized.startsWith("--- a/")
                || normalized.contains("\n--- a/")
                || normalized.contains("\n+++ b/")
                || normalized.contains("\n@@");
    }

    private boolean containsAny(String value, String... needles) {
        String text = safe(value);
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
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
                    targets.add(new PatchTargetFile(path, planText(firstNonBlank(node.path("reason").asText(), "Selected by patch plan."))));
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
                    planText(firstNonBlank(root.path("summary").asText(), "모델이 수정 계획 요약을 제공하지 않았습니다.")),
                    List.copyOf(targets),
                    textArray(root.path("changePlan")).stream().map(this::planText).toList(),
                    risk(root.path("riskLevel").asText()),
                    root.path("needsMoreContext").asBoolean(false),
                    List.copyOf(warnings),
                    evidence(evidence)
            );
        } catch (Exception ex) {
            return null;
        }
    }

    private CodeAgentPatchResponse deterministicSafeFallbackPatch(
            String instruction,
            List<CodePatchFileLoader.LoadedPatchFile> files,
            List<String> warnings
    ) {
        warnings.add("No server-authored content fallback was used; patch content must come from the LLM or explicit model output conversion.");
        return null;
    }

    private void addLlmPatchOutputDiagnostics(
            List<String> warnings,
            String phase,
            OllamaClient.ChatResult result,
            String cleanedDiff
    ) {
        if (warnings == null || result == null) {
            return;
        }
        String content = safe(result.content());
        warnings.add("LLM patch " + phase + " output diagnostics: model="
                + safe(result.model())
                + ", doneReason=" + safe(result.doneReason())
                + ", contentChars=" + content.length()
                + ", cleanedDiffChars=" + safe(cleanedDiff).length()
                + ", preview=" + diagnosticPreview(content));
    }

    private String diagnosticPreview(String value) {
        String clean = safe(value)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\n", "\\n")
                .replaceAll("\\s+", " ")
                .trim();
        if (clean.length() <= LLM_DIAGNOSTIC_PREVIEW_CHARS) {
            return clean;
        }
        return clean.substring(0, LLM_DIAGNOSTIC_PREVIEW_CHARS) + "...<truncated>";
    }

    private PatchContextValidationResult validatePatchContext(
            String diff,
            List<CodePatchFileLoader.LoadedPatchFile> files
    ) {
        Map<String, String> contentByPath = new LinkedHashMap<>();
        for (CodePatchFileLoader.LoadedPatchFile file : files == null ? List.<CodePatchFileLoader.LoadedPatchFile>of() : files) {
            contentByPath.put(normalizePatchPath(file.path()), safe(file.content()));
        }
        Map<String, List<PatchHunk>> hunksByPath = parsePatchHunks(diff);
        Set<String> createdPaths = createdPaths(diff);
        List<String> warnings = new ArrayList<>();
        if (hunksByPath.isEmpty()) {
            warnings.add("Patch context validation found no applicable hunks.");
            return new PatchContextValidationResult(false, List.copyOf(warnings));
        }
        for (Map.Entry<String, List<PatchHunk>> entry : hunksByPath.entrySet()) {
            String path = normalizePatchPath(entry.getKey());
            String content = contentByPath.get(path);
            if (content == null) {
                if (createdPaths.contains(path)) {
                    continue;
                }
                warnings.add("Patch context validation could not find loaded current content for: " + path);
                continue;
            }
            List<String> lines = splitPatchLines(content);
            String error = tryApplyPatchHunksForContext(lines, entry.getValue(), path);
            if (error != null) {
                warnings.add(error);
            }
        }
        return new PatchContextValidationResult(warnings.isEmpty(), List.copyOf(warnings));
    }

    private PatchContextValidationResult validatePatchResultSemantics(
            String diff,
            List<CodePatchFileLoader.LoadedPatchFile> files
    ) {
        Map<String, List<PatchHunk>> hunksByPath = parsePatchHunks(diff);
        Set<String> createdPaths = createdPaths(diff);
        List<String> warnings = new ArrayList<>();
        for (String path : createdPaths) {
            if (isHtmlLikePath(path)) {
                String htmlWarning = validateHtmlPatchResult(path, createdFileContentFromDiff(diff, path));
                if (htmlWarning != null) {
                    warnings.add(htmlWarning);
                }
            }
        }
        for (CodePatchFileLoader.LoadedPatchFile file : files == null ? List.<CodePatchFileLoader.LoadedPatchFile>of() : files) {
            String path = normalizePatchPath(file.path());
            List<PatchHunk> hunks = hunksByPath.get(path);
            if (hunks == null || hunks.isEmpty()) {
                continue;
            }
            ApplyPreviewResult preview = applyPatchHunksPreview(splitPatchLines(file.content()), hunks, path);
            if (preview.error() != null) {
                warnings.add("Patch result semantic validation could not preview patched content: " + preview.error());
                continue;
            }
            if (isHtmlLikePath(path)) {
                String htmlWarning = validateHtmlPatchResult(path, preview.content());
                if (htmlWarning != null) {
                    warnings.add(htmlWarning);
                }
            }
        }
        return new PatchContextValidationResult(warnings.isEmpty(), List.copyOf(warnings));
    }

    private ApplyPreviewResult applyPatchHunksPreview(List<String> originalLines, List<PatchHunk> hunks, String path) {
        List<String> lines = new ArrayList<>(originalLines);
        int lineOffset = 0;
        for (PatchHunk hunk : hunks == null ? List.<PatchHunk>of() : hunks) {
            int startIndex = Math.max(0, hunk.oldStart() - 1 + lineOffset);
            ApplyHunkResult applied = tryApplyHunk(lines, hunk, startIndex);
            if (!applied.success()) {
                Integer shiftedIndex = findHunkApplyIndex(lines, hunk, startIndex);
                applied = shiftedIndex == null ? applied : tryApplyHunk(lines, hunk, shiftedIndex);
            }
            if (!applied.success()) {
                return new ApplyPreviewResult(null, "Patch hunk context did not match current file content: " + path + " near oldStart " + hunk.oldStart() + ". " + applied.error());
            }
            lineOffset += applied.delta();
        }
        return new ApplyPreviewResult(String.join("\n", lines), null);
    }

    private String validateHtmlPatchResult(String path, String content) {
        String lower = safe(content).toLowerCase(Locale.ROOT);
        int closeHtml = lower.indexOf("</html>");
        if (closeHtml >= 0) {
            int secondCloseHtml = lower.indexOf("</html>", closeHtml + "</html>".length());
            if (secondCloseHtml >= 0) {
                return "Patch result contains multiple </html> closing tags in " + path + ".";
            }
            String trailing = lower.substring(closeHtml + "</html>".length()).trim();
            if (!trailing.isBlank()) {
                return "Patch result leaves non-whitespace content after </html> in " + path + ". "
                        + patchedResultContext(content, closeHtml, "</html>".length());
            }
        }
        int closeBody = lower.indexOf("</body>");
        if (closeBody >= 0) {
            int secondCloseBody = lower.indexOf("</body>", closeBody + "</body>".length());
            if (secondCloseBody >= 0) {
                return "Patch result contains multiple </body> closing tags in " + path + ".";
            }
            if (closeHtml >= 0 && closeBody > closeHtml) {
                return "Patch result places </body> after </html> in " + path + ".";
            }
        }
        return null;
    }

    private String patchedResultContext(String content, int offset, int markerLength) {
        String normalized = safe(content).replace("\r\n", "\n").replace('\r', '\n');
        int lineNumber = lineNumberAtOffset(normalized, Math.max(0, offset));
        List<String> lines = splitPatchLines(normalized);
        int markerLineIndex = Math.max(0, lineNumber - 1);
        int start = Math.max(0, markerLineIndex - 3);
        int end = Math.min(lines.size(), markerLineIndex + 9);
        StringBuilder builder = new StringBuilder("PATCHED_RESULT_CONTEXT around line ")
                .append(lineNumber)
                .append(" after marker length ")
                .append(markerLength)
                .append(": ");
        for (int index = start; index < end; index++) {
            if (index > start) {
                builder.append(" | ");
            }
            builder.append(index + 1).append(":").append(lines.get(index));
        }
        return builder.toString();
    }

    private int lineNumberAtOffset(String content, int offset) {
        String normalized = safe(content);
        int line = 1;
        int end = Math.min(Math.max(0, offset), normalized.length());
        for (int index = 0; index < end; index++) {
            if (normalized.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }

    private boolean isHtmlLikePath(String path) {
        String extension = extensionForPath(path);
        return extension.equals("html") || extension.equals("htm");
    }

    private Map<String, List<PatchHunk>> parsePatchHunks(String diff) {
        Map<String, List<PatchHunk>> result = new LinkedHashMap<>();
        String currentPath = "";
        PatchHunk currentHunk = null;
        for (String rawLine : safe(diff).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (rawLine.startsWith("+++ ")) {
                currentPath = normalizeDiffPath(rawLine.substring(4).trim().split("\\s+", 2)[0]);
                if (!currentPath.equals("/dev/null")) {
                    result.putIfAbsent(currentPath, new ArrayList<>());
                }
                currentHunk = null;
                continue;
            }
            if (rawLine.startsWith("@@")) {
                PatchHunkHeader header = parseHunkHeader(rawLine);
                if (header == null || currentPath.isBlank() || currentPath.equals("/dev/null")) {
                    currentHunk = null;
                    continue;
                }
                currentHunk = new PatchHunk(header.oldStart(), header.oldCount(), header.newCount(), new ArrayList<>());
                result.computeIfAbsent(currentPath, ignored -> new ArrayList<>()).add(currentHunk);
                continue;
            }
            if (currentHunk == null || rawLine.startsWith("\\ No newline")) {
                continue;
            }
            if (hunkLineCountsSatisfied(currentHunk)) {
                currentHunk = null;
                continue;
            }
            if (rawLine.isEmpty()) {
                addPatchLineIfWithinHeaderCounts(currentHunk, new PatchLine(' ', ""));
                continue;
            }
            char marker = rawLine.charAt(0);
            if (marker == ' ' || marker == '-' || marker == '+') {
                addPatchLineIfWithinHeaderCounts(currentHunk, new PatchLine(marker, rawLine.substring(1)));
            }
        }
        result.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        return result;
    }

    private boolean hunkLineCountsSatisfied(PatchHunk hunk) {
        return countedOldLines(hunk) >= hunk.oldCount()
                && countedNewLines(hunk) >= hunk.newCount();
    }

    private void addPatchLineIfWithinHeaderCounts(PatchHunk hunk, PatchLine line) {
        int oldLines = countedOldLines(hunk);
        int newLines = countedNewLines(hunk);
        boolean consumesOld = line.marker() == ' ' || line.marker() == '-';
        boolean consumesNew = line.marker() == ' ' || line.marker() == '+';
        if ((consumesOld && oldLines >= hunk.oldCount())
                || (consumesNew && newLines >= hunk.newCount())) {
            return;
        }
        hunk.lines().add(line);
    }

    private int countedOldLines(PatchHunk hunk) {
        return (int) hunk.lines().stream()
                .filter(line -> line.marker() == ' ' || line.marker() == '-')
                .count();
    }

    private int countedNewLines(PatchHunk hunk) {
        return (int) hunk.lines().stream()
                .filter(line -> line.marker() == ' ' || line.marker() == '+')
                .count();
    }

    private String tryApplyPatchHunksForContext(List<String> originalLines, List<PatchHunk> hunks, String path) {
        List<String> lines = new ArrayList<>(originalLines);
        int lineOffset = 0;
        for (PatchHunk hunk : hunks == null ? List.<PatchHunk>of() : hunks) {
            int startIndex = Math.max(0, hunk.oldStart() - 1 + lineOffset);
            ApplyHunkResult applied = tryApplyHunk(lines, hunk, startIndex);
            if (!applied.success()) {
                Integer shiftedIndex = findHunkApplyIndex(lines, hunk, startIndex);
                applied = shiftedIndex == null ? applied : tryApplyHunk(lines, hunk, shiftedIndex);
            }
            if (!applied.success()) {
                return "Patch hunk context did not match current file content: " + path + " near oldStart " + hunk.oldStart() + ". " + applied.error();
            }
            lineOffset += applied.delta();
        }
        return null;
    }

    private ApplyHunkResult tryApplyHunk(List<String> lines, PatchHunk hunk, int startIndex) {
        if (startIndex < 0 || startIndex > lines.size()) {
            return new ApplyHunkResult(false, 0, "hunk start is outside the file");
        }
        int cursor = startIndex;
        int removeCount = 0;
        List<String> replacement = new ArrayList<>();
        for (PatchLine line : hunk.lines()) {
            if (line.marker() == ' ' || line.marker() == '-') {
                if (cursor >= lines.size()) {
                    return new ApplyHunkResult(false, 0, "hunk expected more existing lines than the file contains");
                }
                if (!lines.get(cursor).equals(line.text())) {
                    return new ApplyHunkResult(false, 0, "hunk context does not match at line " + (cursor + 1));
                }
                cursor++;
                removeCount++;
            }
            if (line.marker() == ' ' || line.marker() == '+') {
                replacement.add(line.text());
            }
        }
        lines.subList(startIndex, startIndex + removeCount).clear();
        lines.addAll(startIndex, replacement);
        return new ApplyHunkResult(true, replacement.size() - removeCount, null);
    }

    private Integer findHunkApplyIndex(List<String> lines, PatchHunk hunk, int preferredIndex) {
        List<String> expected = hunk.lines().stream()
                .filter(line -> line.marker() == ' ' || line.marker() == '-')
                .map(PatchLine::text)
                .toList();
        if (expected.isEmpty()) {
            return preferredIndex >= 0 && preferredIndex <= lines.size() ? preferredIndex : null;
        }
        Integer bestIndex = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int index = 0; index <= lines.size() - expected.size(); index++) {
            if (!matchesAt(lines, expected, index)) {
                continue;
            }
            int distance = Math.abs(index - preferredIndex);
            if (distance < bestDistance) {
                bestIndex = index;
                bestDistance = distance;
            }
        }
        return bestIndex;
    }

    private boolean matchesAt(List<String> lines, List<String> expected, int startIndex) {
        if (startIndex < 0 || startIndex + expected.size() > lines.size()) {
            return false;
        }
        for (int offset = 0; offset < expected.size(); offset++) {
            if (!lines.get(startIndex + offset).equals(expected.get(offset))) {
                return false;
            }
        }
        return true;
    }

    private List<String> splitPatchLines(String content) {
        List<String> lines = new ArrayList<>(List.of(safe(content).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private PatchHunkHeader parseHunkHeader(String hunkHeader) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")
                .matcher(safe(hunkHeader));
        if (!matcher.find()) {
            return null;
        }
        int oldStart = Integer.parseInt(matcher.group(1));
        int oldCount = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
        int newCount = matcher.group(4) == null ? 1 : Integer.parseInt(matcher.group(4));
        return new PatchHunkHeader(oldStart, oldCount, newCount);
    }

    private String normalizeDiffPath(String raw) {
        String path = safe(raw).trim().replace('\\', '/');
        if (path.equals("/dev/null")) {
            return path;
        }
        if (path.startsWith("a/") || path.startsWith("b/")) {
            path = path.substring(2);
        }
        return normalizePatchPath(path);
    }

    private String normalizePatchPath(String path) {
        return safe(path).trim().replace('\\', '/').replaceAll("^/+", "");
    }

    private record PatchContextValidationResult(boolean valid, List<String> warnings) {
    }

    private record PatchHunk(int oldStart, int oldCount, int newCount, List<PatchLine> lines) {
    }

    private record PatchHunkHeader(int oldStart, int oldCount, int newCount) {
    }

    private record PatchLine(char marker, String text) {
    }

    private record ApplyHunkResult(boolean success, int delta, String error) {
    }

    private record ApplyPreviewResult(String content, String error) {
    }

    private record OperationApplyResult(boolean success, String content, String warning, String reason) {
        static OperationApplyResult success(String content) {
            return new OperationApplyResult(true, content, "", "");
        }

        static OperationApplyResult failure(String warning, String reason) {
            return new OperationApplyResult(false, "", warning, reason);
        }
    }

    private record AnchorRange(int start, int end) {
    }

    private record AnchorPoint(int anchorIndex, int insertion) {
    }

    private record StructuredEdit(
            String editFormat,
            String path,
            String operation,
            String fullFileContent,
            String content,
            String search,
            String replace,
            String oldText,
            String newText,
            String anchorBefore,
            String anchorAfter
    ) {
    }

    private String compactReplacementDiff(String path, String currentContent, String replacementContent) {
        String cleanPath = safe(path).replace('\\', '/');
        List<String> oldLines = contentLines(currentContent);
        List<String> newLines = contentLines(replacementContent);
        if (oldLines.equals(newLines)) {
            return "";
        }
        int prefix = 0;
        while (prefix < oldLines.size()
                && prefix < newLines.size()
                && oldLines.get(prefix).equals(newLines.get(prefix))) {
            prefix++;
        }
        int oldSuffix = oldLines.size() - 1;
        int newSuffix = newLines.size() - 1;
        while (oldSuffix >= prefix
                && newSuffix >= prefix
                && oldLines.get(oldSuffix).equals(newLines.get(newSuffix))) {
            oldSuffix--;
            newSuffix--;
        }
        int context = 3;
        int hunkOldStartIndex = Math.max(0, prefix - context);
        int hunkNewStartIndex = Math.max(0, prefix - context);
        int hunkOldEndExclusive = Math.min(oldLines.size(), oldSuffix + 1 + context);
        int hunkNewEndExclusive = Math.min(newLines.size(), newSuffix + 1 + context);
        int oldStart = oldLines.isEmpty() ? 0 : hunkOldStartIndex + 1;
        int newStart = newLines.isEmpty() ? 0 : hunkNewStartIndex + 1;
        StringBuilder builder = new StringBuilder();
        builder.append("--- a/").append(cleanPath).append("\n");
        builder.append("+++ b/").append(cleanPath).append("\n");
        builder.append("@@ -")
                .append(oldStart)
                .append(",")
                .append(Math.max(0, hunkOldEndExclusive - hunkOldStartIndex))
                .append(" +")
                .append(newStart)
                .append(",")
                .append(Math.max(0, hunkNewEndExclusive - hunkNewStartIndex))
                .append(" @@\n");
        int oldCursor = hunkOldStartIndex;
        int newCursor = hunkNewStartIndex;
        while (oldCursor < prefix && newCursor < prefix) {
            builder.append(" ").append(oldLines.get(oldCursor)).append("\n");
            oldCursor++;
            newCursor++;
        }
        while (oldCursor <= oldSuffix) {
            builder.append("-").append(oldLines.get(oldCursor)).append("\n");
            oldCursor++;
        }
        while (newCursor <= newSuffix) {
            builder.append("+").append(newLines.get(newCursor)).append("\n");
            newCursor++;
        }
        while (oldCursor < hunkOldEndExclusive && newCursor < hunkNewEndExclusive) {
            builder.append(" ").append(oldLines.get(oldCursor)).append("\n");
            oldCursor++;
            newCursor++;
        }
        return builder.toString().trim();
    }

    private String fullFileReplacementDiff(String path, String currentContent, String replacementContent) {
        String cleanPath = safe(path).replace('\\', '/');
        List<String> oldLines = contentLines(currentContent);
        List<String> newLines = contentLines(replacementContent);
        StringBuilder builder = new StringBuilder();
        builder.append("--- a/").append(cleanPath).append("\n");
        builder.append("+++ b/").append(cleanPath).append("\n");
        int oldCount = oldLines.size();
        int newCount = newLines.size();
        builder.append("@@ -")
                .append(oldCount == 0 ? 0 : 1)
                .append(",")
                .append(oldCount)
                .append(" +")
                .append(newCount == 0 ? 0 : 1)
                .append(",")
                .append(newCount)
                .append(" @@\n");
        for (String line : oldLines) {
            builder.append("-").append(line).append("\n");
        }
        for (String line : newLines) {
            builder.append("+").append(line).append("\n");
        }
        return builder.toString().trim();
    }

    private String newFileDiff(String path, String content) {
        String cleanPath = normalizePatchPath(path);
        List<String> newLines = contentLines(content);
        StringBuilder builder = new StringBuilder();
        builder.append("--- /dev/null\n");
        builder.append("+++ b/").append(cleanPath).append("\n");
        builder.append("@@ -0,0 +")
                .append(newLines.isEmpty() ? 0 : 1)
                .append(",")
                .append(newLines.size())
                .append(" @@\n");
        for (String line : newLines) {
            builder.append("+").append(line).append("\n");
        }
        return builder.toString().trim();
    }

    private List<String> contentLines(String content) {
        String normalized = safe(content).replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return List.of();
        }
        return normalized.lines().toList();
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
                You are LearnBot Patch Agent v1.
                Return JSON only.
                Do not output <think> blocks, reasoning, analysis, or explanations.
                Do not use markdown fences.
                Modify only provided target files unless the user request requires creating new safe workspace files.
                You may create new files with operation=create_file when needed.
                Do not delete, rename, or chmod files.
                Preserve the existing style.
                Preserve the user's requested language and content constraints.
                If the user asks for Korean/Hangul text, added prose must be Korean.
                Do not invent generic placeholders such as "Added by LearnBot" unless the user explicitly asked for that text.
                If the user explicitly asks to add/create a JS, CSS, HTML, or other file, include a create_file operation for that file type unless an existing file of that type is clearly the intended target.
                If you add a new local <script src>, stylesheet href, import, or module reference, create or update the referenced local file in the same patch.
                Prefer editFormat=operation_edit.
                Keep output compact: omit diagnosis, changeIntent, verificationPlan, and riskNotes unless essential.
                Use small operations with exact anchors copied from EXACT_CONTENT.
                Prefer insert_before_anchor, insert_after_anchor, replace_exact, replace_between_anchors, or create_file.
                For insert_before_anchor and insert_after_anchor, newText must contain only the inserted text; do not repeat the anchor text or boundary line inside newText.
                For small HTML/HTM/XML/SVG files or markup edits that add related navigation and content sections, prefer editFormat=full_file over several insert operations. The fullFileContent must be the complete updated file authored by you.
                Do not use editFormat=full_file for large files unless the user explicitly asks to rewrite the whole file.
                Use search_replace only when the search block appears exactly once in the current file.
                Use legacy unifiedDiff only when you are certain every hunk context line is copied exactly from the current file.
                The server may reject unsafe or malformed output and may materialize your edits into a unified diff, but it must not author replacement content for you.
                Compact JSON shape:
                {"action":"propose_patch","editFormat":"operation_edit","targetFiles":["path"],"operations":[{"path":"path","operation":"replace_between_anchors|insert_after_anchor|insert_before_anchor|replace_exact|append_to_file|create_file","anchorBefore":"exact current text","anchorAfter":"exact current text","oldText":"exact current text","newText":"LLM-authored replacement or insertion text","content":"LLM-authored new file content for create_file"}]}
                For operation_edit, every anchorBefore, anchorAfter, and oldText value must be copied exactly from EXACT_CONTENT and must match uniquely.
                For create_file, path must be a safe relative workspace path and content/newText/fullFileContent must contain the complete new file content.
                For search_replace, edits items must be {"path":"path","search":"exact current text block","replace":"LLM-authored replacement block"}.
                For full_file, edits items must be {"path":"path","fullFileContent":"complete updated file content"}.
                For legacy unified_diff, set unifiedDiff to the complete diff.
                If a safe patch cannot be produced, set action to observe_more or ask_clarification and leave edits and unifiedDiff empty.
                """;
    }

    private String patchUserPrompt(String instruction, List<CodePatchFileLoader.LoadedPatchFile> files) {
        StringBuilder builder = new StringBuilder();
        builder.append("PATCH_CONTEXT_ENVELOPE v1\n")
                .append("USER_INSTRUCTION:\n")
                .append(instruction)
                .append("\n\nSERVER_ROLE:\n")
                .append("- The server only provides observations and validates safety.\n")
                .append("- You must decide the target lines and patch content.\n")
                .append("- Keep JSON compact so it is not truncated.\n")
                .append("- If the supplied file content is insufficient for modifying existing files, choose action=observe_more instead of guessing.\n")
                .append("- If no existing files are provided and the user asks to create something, choose create_file operations with safe relative paths.\n\n")
                .append("TARGET_FILES:\n");
        if (files == null || files.isEmpty()) {
            builder.append("- none; creation mode is allowed for safe relative workspace files.\n");
        } else {
            for (CodePatchFileLoader.LoadedPatchFile file : files) {
                appendFileContext(builder, file, false);
            }
        }
        return builder.toString();
    }

    private String patchRepairSystemPrompt() {
        return """
                You repair invalid LearnBot patch proposals.
                Return JSON only.
                Do not output <think> blocks, reasoning, analysis, or explanations.
                Do not use markdown fences.
                Modify only provided target files unless the user request requires creating new safe workspace files.
                You may create new files with operation=create_file when needed.
                Do not delete, rename, or chmod files.
                Prefer editFormat=operation_edit. Use small operations with exact anchors copied from EXACT_CONTENT.
                For insert_before_anchor and insert_after_anchor, newText must contain only the inserted text; do not repeat the anchor text or boundary line inside newText.
                Keep output compact: no diagnosis/changeIntent/verificationPlan/riskNotes unless essential.
                For small HTML/HTM/XML/SVG files or markup edits that add related navigation and content sections, prefer editFormat=full_file over several insert operations. The fullFileContent must be the complete updated file authored by you.
                Do not use editFormat=full_file for large files unless the user explicitly asks to rewrite the whole file.
                Use search_replace only when the search block appears exactly once in the current file.
                Use legacy unifiedDiff only when every hunk context line is copied exactly from the provided file contents, including indentation.
                Keep the patch small and targeted.
                Preserve the user's requested language and content constraints.
                If the user explicitly asks to add/create a JS, CSS, HTML, or other file, include a create_file operation for that file type unless an existing file of that type is clearly the intended target.
                If you add a new local <script src>, stylesheet href, import, or module reference, create or update the referenced local file in the same patch.
                The provided file contents are the actual current workspace state.
                If the previous output declined because a file looked incomplete or truncated, treat that incomplete file state as the bug and produce a minimal repair diff when it satisfies the user request.
                Compact JSON shape:
                {"action":"propose_patch","editFormat":"operation_edit","targetFiles":["path"],"operations":[{"path":"path","operation":"replace_between_anchors|insert_after_anchor|insert_before_anchor|replace_exact|append_to_file|create_file","anchorBefore":"exact current text","anchorAfter":"exact current text","oldText":"exact current text","newText":"LLM-authored replacement or insertion text","content":"LLM-authored new file content for create_file"}]}
                For operation_edit, every anchorBefore, anchorAfter, and oldText value must be copied exactly from EXACT_CONTENT and must match uniquely.
                For create_file, path must be a safe relative workspace path and content/newText/fullFileContent must contain the complete new file content.
                For search_replace, edits items must be {"path":"path","search":"exact current text block","replace":"LLM-authored replacement block"}.
                For full_file, edits items must be {"path":"path","fullFileContent":"complete updated file content"}.
                For legacy unified_diff, set unifiedDiff to the complete diff.
                If a safe valid patch cannot be produced, set action to observe_more or ask_clarification and leave edits and unifiedDiff empty.
                """;
    }

    private OllamaClient.ChatResult patchChatResult(
            String systemPrompt,
            String userPrompt,
            List<String> warnings,
            String phase
    ) {
        OllamaClient.ChatResult primary = ollamaClient.chatResult(systemPrompt, userPrompt, PATCH_OUTPUT_TOKENS);
        if (!blankLengthPatchOutput(primary)) {
            return primary;
        }
        warnings.add("LLM patch " + phase + " primary output was blank after a length stop; retrying code-patch generation with the auxiliary LLM role.");
        try {
            OllamaClient.ChatResult fallback = ollamaClient.chatResult(
                    systemPrompt,
                    userPrompt,
                    OllamaClient.ChatRole.AUXILIARY,
                    PATCH_OUTPUT_TOKENS
            );
            if (!blankLengthPatchOutput(fallback)) {
                warnings.add("LLM patch " + phase + " auxiliary fallback produced non-empty output.");
                return fallback;
            }
            warnings.add("LLM patch " + phase + " auxiliary fallback also returned blank output after a length stop.");
            return fallback;
        } catch (RuntimeException ex) {
            warnings.add("LLM patch " + phase + " auxiliary fallback failed: " + ex.getMessage());
            return primary;
        }
    }

    private boolean blankLengthPatchOutput(OllamaClient.ChatResult result) {
        return result != null && result.stoppedByLength() && safe(result.content()).isBlank();
    }

    private String patchRepairUserPrompt(
            String instruction,
            List<CodePatchFileLoader.LoadedPatchFile> files,
            String previousOutput,
            List<String> validationWarnings
    ) {
        return patchRepairUserPrompt(instruction, files, previousOutput, validationWarnings, false);
    }

    private String patchRepairUserPrompt(
            String instruction,
            List<CodePatchFileLoader.LoadedPatchFile> files,
            String previousOutput,
            List<String> validationWarnings,
            boolean currentContentAuthoritative
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("PATCH_REPAIR_CONTEXT_ENVELOPE v1\n")
                .append("USER_INSTRUCTION:\n")
                .append(instruction)
                .append("\n\n");
        if (currentContentAuthoritative) {
            builder.append("Critical repair retry rules:\n")
                    .append("- Treat only the target file contents below as the current workspace state.\n")
                    .append("- The previous invalid output was NOT applied and may describe lines that do not exist.\n")
                    .append("- Do not carry over additions from the previous invalid output unless they match the current file contents and the user request.\n")
                    .append("- Produce a new minimal unified diff against the exact current contents below.\n\n");
        }
        builder.append("Validation warnings:\n");
        for (String warning : validationWarnings == null ? List.<String>of() : validationWarnings) {
            builder.append("- ").append(boundedText(warning, VALIDATION_WARNING_PREVIEW_CHARS)).append("\n");
        }
        appendRepairFailureGuidance(builder, validationWarnings);
        builder.append("\nTarget files with exact current contents:\n");
        for (CodePatchFileLoader.LoadedPatchFile file : files) {
            appendFileContext(builder, file, true);
        }
        builder.append("Previous invalid output preview for reference only; do not assume it was applied:\n")
                .append(boundedPreviousPatchOutput(previousOutput))
                .append("\n");
        return builder.toString();
    }

    private void appendRepairFailureGuidance(StringBuilder builder, List<String> validationWarnings) {
        List<String> warnings = validationWarnings == null ? List.of() : validationWarnings;
        boolean tooLarge = warnings.stream().anyMatch(warning -> safe(warning).contains("Patch changes too many lines"));
        boolean ambiguousAnchor = warnings.stream().anyMatch(warning -> {
            String clean = safe(warning);
            return clean.contains("matched ") && clean.contains("exact single-match")
                    || clean.contains("anchor did not match exactly once")
                    || clean.contains("search_replace block did not match exactly once");
        });
        boolean malformed = warnings.stream().anyMatch(warning -> {
            String lower = safe(warning).toLowerCase(Locale.ROOT);
            return lower.contains("malformed json")
                    || lower.contains("json proposal parsing failed")
                    || lower.contains("not a unified diff")
                    || lower.contains("did not contain materializable edits");
        });
        boolean repeatedInsertAnchor = warnings.stream().anyMatch(warning -> {
            String clean = safe(warning);
            return clean.contains("insert operation repeated its anchor")
                    || clean.contains("repeated its anchor text inside newText");
        });
        if (!tooLarge && !ambiguousAnchor && !malformed && !repeatedInsertAnchor) {
            return;
        }
        builder.append("\nRepair guidance derived from validation failures:\n");
        if (tooLarge) {
            builder.append("- The previous patch exceeded the existing-file safe changed-line budget. Do not rewrite whole existing files unless the user explicitly asked for a full rewrite. Produce smaller operation_edit changes, split across only the necessary files, and preserve unchanged surrounding content through anchors instead of full_file content.\n");
        }
        if (ambiguousAnchor) {
            builder.append("- At least one anchor or search block was ambiguous. Copy a longer exact block from EXACT_CONTENT, or use replace_between_anchors with both unique surrounding anchors so each operation matches exactly once.\n");
        }
        if (malformed) {
            builder.append("- The previous patch envelope was malformed or not materializable. Return compact JSON only, with action=propose_patch, editFormat=operation_edit, targetFiles, and operations; do not include markdown, comments, or trailing prose.\n");
        }
        if (repeatedInsertAnchor) {
            builder.append("- An insert operation repeated anchor text inside newText. For insert_before_anchor and insert_after_anchor, keep the anchor only in anchorBefore/anchorAfter and put only the newly inserted lines in newText. If the target is a small markup file and the change touches multiple related locations, switch to editFormat=full_file and return complete updated file content authored by you.\n");
        }
        builder.append("- The server will only validate and materialize LLM-authored edits; it will not invent replacement content. If you cannot produce a bounded safe patch from the exact content below, return action=observe_more or ask_clarification.\n\n");
    }

    private void appendFileContext(StringBuilder builder, CodePatchFileLoader.LoadedPatchFile file, boolean repair) {
        String content = safe(file.content()).replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = splitPatchLines(content);
        builder.append("FILE: ").append(file.path()).append("\n")
                .append("LANGUAGE: ").append(file.language()).append("\n")
                .append("CHAR_COUNT: ").append(content.length()).append("\n")
                .append("LINE_COUNT: ").append(lines.size()).append("\n")
                .append("CONTENT_AUTHORITY: exact_current_workspace_file\n")
                .append("EDIT_RULE: Prefer operation_edit with small operations and exact anchors copied from EXACT_CONTENT.\n")
                .append("EDIT_RULE: For operation_edit, the server will materialize only your newText into a unified diff.\n")
                .append("EDIT_RULE: For full_file, return the complete updated file content authored by you.\n")
                .append("EDIT_RULE: For search_replace, the search block must be copied exactly from EXACT_CONTENT and match once.\n")
                .append("PATCH_RULE: If using legacy unifiedDiff, hunk context must copy exact lines from EXACT_CONTENT.\n")
                .append(markupFullFileGuidance(file, content))
                .append("EXACT_CONTENT_START ").append(file.path()).append("\n")
                .append(content)
                .append(content.endsWith("\n") || content.isEmpty() ? "" : "\n")
                .append("EXACT_CONTENT_END ").append(file.path()).append("\n\n");
        if (repair) {
            builder.append("REPAIR_NOTE: Previous invalid output may be wrong. Re-evaluate from this exact content.\n\n");
        }
    }

    private void appendLineNumberedContent(StringBuilder builder, List<String> lines) {
        int maxLines = Math.min(lines.size(), 400);
        for (int index = 0; index < maxLines; index++) {
            builder.append(String.format(Locale.ROOT, "%5d | %s%n", index + 1, lines.get(index)));
        }
        if (lines.size() > maxLines) {
            builder.append("... ").append(lines.size() - maxLines).append(" additional lines omitted from numbered view; exact content remains above/below if available.\n");
        }
    }

    private String markupFullFileGuidance(CodePatchFileLoader.LoadedPatchFile file, String content) {
        String extension = extensionForPath(file == null ? "" : file.path());
        boolean markup = Set.of("html", "htm", "xml", "svg").contains(extension);
        if (!markup || safe(content).length() > 12_000) {
            return "";
        }
        return "EDIT_RULE: This is a small markup file. If the requested change touches multiple related locations such as navigation buttons plus content sections, prefer editFormat=full_file with one complete fullFileContent value instead of multiple insert_before_anchor/insert_after_anchor operations.\n"
                + "EDIT_RULE: Do not repeat anchor or boundary text inside insert newText. If that is hard to guarantee for this markup file, use full_file.\n";
    }

    private String boundedPreviousPatchOutput(String value) {
        return boundedText(value, PREVIOUS_PATCH_OUTPUT_PREVIEW_CHARS);
    }

    private String boundedText(String value, int maxChars) {
        String clean = safe(value).replace("\r\n", "\n").replace('\r', '\n').trim();
        if (maxChars <= 0 || clean.length() <= maxChars) {
            return clean;
        }
        return clean.substring(0, maxChars) + "\n...<truncated>";
    }

    private String materializePatchFromModelOutput(
            String modelOutput,
            List<CodePatchFileLoader.LoadedPatchFile> files,
            List<String> warnings,
            String phase
    ) {
        String output = safe(modelOutput).trim();
        String json = cleanJson(output);
        if (looksLikeStructuredJsonPatchEnvelope(output)) {
            try {
                JsonNode root = objectMapper.readTree(json);
                String action = root.path("action").asText("");
                if (!action.isBlank()) {
                    warnings.add("LLM patch " + phase + " proposal action=" + action + ".");
                }
                String diagnosis = root.path("diagnosis").asText("");
                if (!diagnosis.isBlank()) {
                    warnings.add("LLM patch " + phase + " diagnosis: " + diagnosticPreview(diagnosis));
                }
                String materialized = materializeStructuredEdits(root, files, warnings, phase);
                if (!materialized.isBlank()) {
                    return materialized;
                }
                String diff = root.path("unifiedDiff").asText("");
                if (!diff.isBlank()) {
                    warnings.add("LLM patch " + phase + " used legacy unifiedDiff compatibility path.");
                    return cleanDiff(diff);
                }
                if (!action.isBlank() && !"propose_patch".equalsIgnoreCase(action)) {
                    return "NO_PATCH\nreason: LLM requested " + firstNonBlank(action, "no patch");
                }
                if (root.has("action") || root.has("editFormat") || root.has("edits")) {
                    return "NO_PATCH\nreason: LLM JSON patch proposal did not contain materializable edits";
                }
            } catch (Exception ex) {
                warnings.add("LLM patch " + phase + " JSON proposal parsing failed; raw diff/full-file fallback is blocked for JSON-like output: " + ex.getMessage());
                return "NO_PATCH\nreason: malformed JSON patch proposal";
            }
        }
        return cleanDiff(output);
    }

    private String materializeStructuredEdits(
            JsonNode root,
            List<CodePatchFileLoader.LoadedPatchFile> files,
            List<String> warnings,
            String phase
    ) {
        String editFormat = safe(root.path("editFormat").asText("")).toLowerCase(Locale.ROOT);
        String operationInferredFormat = inferOperationContainerEditFormat(root.path("operations"));
        if (!operationInferredFormat.isBlank()
                && (editFormat.isBlank() || "full_file".equals(editFormat) || "full-file".equals(editFormat) || "fullfile".equals(editFormat))) {
            if (!editFormat.isBlank() && !normalizeEditFormat(editFormat).equals(operationInferredFormat)) {
                warnings.add("LLM patch " + phase + " editFormat=" + editFormat
                        + " conflicted with operation payload; materializing as " + operationInferredFormat + ".");
            }
            editFormat = operationInferredFormat;
        }
        if (editFormat.isBlank() && root.has("fullFileContent")) {
            editFormat = "full_file";
        }
        if (editFormat.isBlank() && root.has("operations") && root.path("operations").isArray()) {
            editFormat = "operation_edit";
        }
        if (editFormat.isBlank() && root.has("edits") && root.path("edits").isArray()) {
            editFormat = inferEditFormat(root.path("edits"));
        }
        return switch (editFormat) {
            case "operation_edit", "operation-edit", "operation", "operations" -> materializeOperationEdits(root, files, warnings, phase);
            case "full_file", "full-file", "fullfile" -> materializeFullFileEdits(root, files, warnings, phase);
            case "search_replace", "search-replace", "searchreplace" -> materializeSearchReplaceEdits(root, files, warnings, phase);
            case "unified_diff", "unified-diff", "unifieddiff" -> "";
            default -> "";
        };
    }

    private String normalizeEditFormat(String editFormat) {
        return safe(editFormat).trim().replace('-', '_').toLowerCase(Locale.ROOT);
    }

    private String inferOperationContainerEditFormat(JsonNode operations) {
        if (operations == null || !operations.isArray() || operations.isEmpty()) {
            return "";
        }
        boolean hasOperationEdit = false;
        boolean hasFullFile = false;
        for (JsonNode operation : operations) {
            String operationName = normalizeOperationName(textField(operation, "operation", "type"));
            if ("create_file".equals(operationName)) {
                hasFullFile = true;
                continue;
            }
            if (!operationName.isBlank()
                    || operation.has("oldText")
                    || operation.has("old_text")
                    || operation.has("anchorBefore")
                    || operation.has("anchor_after")
                    || operation.has("anchorAfter")
                    || operation.has("newText")
                    || operation.has("new_text")) {
                hasOperationEdit = true;
            }
            if (operation.has("fullFileContent") || operation.has("full_file_content") || operation.has("fullFile") || operation.has("fileContent")) {
                hasFullFile = true;
            }
        }
        if (hasOperationEdit) {
            return "operation_edit";
        }
        return hasFullFile ? "full_file" : "";
    }

    private String inferEditFormat(JsonNode edits) {
        for (JsonNode edit : edits) {
            if (edit.has("operation") || edit.has("type") || edit.has("anchorBefore") || edit.has("anchorAfter") || edit.has("newText")) {
                return "operation_edit";
            }
            if (edit.has("fullFileContent") || edit.has("content")) {
                return "full_file";
            }
            if (edit.has("search") || edit.has("replace")) {
                return "search_replace";
            }
        }
        return "";
    }

    private String materializeOperationEdits(
            JsonNode root,
            List<CodePatchFileLoader.LoadedPatchFile> files,
            List<String> warnings,
            String phase
    ) {
        List<StructuredEdit> operations = structuredOperationEdits(root, files);
        if (operations.isEmpty()) {
            return "";
        }
        Map<String, String> updatedByPath = new LinkedHashMap<>();
        Map<String, String> createdByPath = new LinkedHashMap<>();
        for (StructuredEdit operation : operations) {
            CodePatchFileLoader.LoadedPatchFile file = loadedFileByPath(files, operation.path());
            String operationName = normalizeOperationName(operation.operation());
            if ("create_file".equals(operationName)) {
                OperationApplyResult created = createFileOperation(operation, file, phase);
                if (!created.success()) {
                    warnings.add(created.warning());
                    return "NO_PATCH\nreason: " + created.reason();
                }
                String path = normalizePatchPath(operation.path());
                if (createdByPath.containsKey(path)) {
                    warnings.add("LLM patch " + phase + " create_file operation repeated a new file path: " + path);
                    return "NO_PATCH\nreason: create_file path was repeated";
                }
                createdByPath.put(path, created.content());
                continue;
            }
            if (file == null) {
                warnings.add("LLM patch " + phase + " operation_edit targeted an unloaded file: " + operation.path());
                return "NO_PATCH\nreason: operation_edit targeted an unloaded file";
            }
            String path = normalizePatchPath(file.path());
            String current = updatedByPath.getOrDefault(path, safe(file.content()).replace("\r\n", "\n").replace('\r', '\n'));
            OperationApplyResult applied = applyStructuredOperation(current, operation, file.path(), phase);
            if (!applied.success()) {
                warnings.add(applied.warning());
                return "NO_PATCH\nreason: " + applied.reason();
            }
            updatedByPath.put(path, applied.content());
        }
        List<String> diffs = new ArrayList<>();
        for (Map.Entry<String, String> entry : createdByPath.entrySet()) {
            diffs.add(newFileDiff(entry.getKey(), entry.getValue()));
        }
        for (CodePatchFileLoader.LoadedPatchFile file : files == null ? List.<CodePatchFileLoader.LoadedPatchFile>of() : files) {
            String path = normalizePatchPath(file.path());
            String updated = updatedByPath.get(path);
            if (updated == null || sameNormalizedContent(file.content(), updated)) {
                continue;
            }
            diffs.add(compactReplacementDiff(file.path(), file.content(), updated));
        }
        if (diffs.isEmpty()) {
            return "NO_PATCH\nreason: operation_edit edits made no changes";
        }
        warnings.add("LLM patch " + phase + " editFormat=operation_edit; server materialized unified diff from LLM-authored operations and exact current files.");
        return String.join("\n", diffs).trim();
    }

    private String materializeFullFileEdits(
            JsonNode root,
            List<CodePatchFileLoader.LoadedPatchFile> files,
            List<String> warnings,
            String phase
    ) {
        List<StructuredEdit> edits = structuredEdits(root, files, "full_file");
        if (edits.isEmpty()) {
            return "";
        }
        List<String> diffs = new ArrayList<>();
        for (StructuredEdit edit : edits) {
            CodePatchFileLoader.LoadedPatchFile file = loadedFileByPath(files, edit.path());
            if (file == null) {
                warnings.add("LLM patch " + phase + " full_file edit targeted an unloaded file: " + edit.path());
                return "NO_PATCH\nreason: full_file edit targeted an unloaded file";
            }
            String replacement = cleanFullFileModelOutput(firstNonBlank(edit.fullFileContent(), edit.content()));
            if (!looksLikeSafeFullFileReplacement(file, replacement)) {
                warnings.add("LLM patch " + phase + " full_file edit was rejected by file-shape safety checks for: " + file.path());
                return "NO_PATCH\nreason: full_file content failed safety checks";
            }
            if (sameNormalizedContent(file.content(), replacement)) {
                warnings.add("LLM patch " + phase + " full_file edit made no content changes for: " + file.path());
                continue;
            }
            diffs.add(fullFileReplacementDiff(file.path(), file.content(), replacement));
        }
        if (diffs.isEmpty()) {
            return "NO_PATCH\nreason: full_file edits made no changes";
        }
        warnings.add("LLM patch " + phase + " editFormat=full_file; server materialized unified diff from LLM-authored replacement content and exact current files.");
        return String.join("\n", diffs).trim();
    }

    private String materializeSearchReplaceEdits(
            JsonNode root,
            List<CodePatchFileLoader.LoadedPatchFile> files,
            List<String> warnings,
            String phase
    ) {
        List<StructuredEdit> edits = structuredEdits(root, files, "search_replace");
        if (edits.isEmpty()) {
            return "";
        }
        Map<String, String> updatedByPath = new LinkedHashMap<>();
        for (StructuredEdit edit : edits) {
            CodePatchFileLoader.LoadedPatchFile file = loadedFileByPath(files, edit.path());
            if (file == null) {
                warnings.add("LLM patch " + phase + " search_replace edit targeted an unloaded file: " + edit.path());
                return "NO_PATCH\nreason: search_replace edit targeted an unloaded file";
            }
            String search = safe(edit.search()).replace("\r\n", "\n").replace('\r', '\n');
            String replace = safe(edit.replace()).replace("\r\n", "\n").replace('\r', '\n');
            if (search.isBlank()) {
                warnings.add("LLM patch " + phase + " search_replace edit had a blank search block for: " + file.path());
                return "NO_PATCH\nreason: search_replace edit had blank search";
            }
            String current = updatedByPath.getOrDefault(normalizePatchPath(file.path()), safe(file.content()).replace("\r\n", "\n").replace('\r', '\n'));
            int matches = countOccurrences(current, search);
            if (matches != 1) {
                warnings.add("LLM patch " + phase + " search_replace edit for " + file.path()
                        + " matched " + matches + " current blocks; exact single-match replacement is required.");
                return "NO_PATCH\nreason: search_replace block did not match exactly once";
            }
            updatedByPath.put(normalizePatchPath(file.path()), current.replace(search, replace));
        }
        List<String> diffs = new ArrayList<>();
        for (CodePatchFileLoader.LoadedPatchFile file : files == null ? List.<CodePatchFileLoader.LoadedPatchFile>of() : files) {
            String path = normalizePatchPath(file.path());
            String updated = updatedByPath.get(path);
            if (updated == null || sameNormalizedContent(file.content(), updated)) {
                continue;
            }
            diffs.add(compactReplacementDiff(file.path(), file.content(), updated));
        }
        if (diffs.isEmpty()) {
            return "NO_PATCH\nreason: search_replace edits made no changes";
        }
        warnings.add("LLM patch " + phase + " editFormat=search_replace; server materialized unified diff after exact single-match replacements.");
        return String.join("\n", diffs).trim();
    }

    private OperationApplyResult applyStructuredOperation(
            String current,
            StructuredEdit edit,
            String path,
            String phase
    ) {
        String operation = normalizeOperationName(edit.operation());
        if (operation.isBlank()) {
            operation = !safe(edit.oldText()).isBlank() ? "replace_exact" : "replace_between_anchors";
        }
        String normalizedCurrent = safe(current).replace("\r\n", "\n").replace('\r', '\n');
        String oldText = safe(edit.oldText()).replace("\r\n", "\n").replace('\r', '\n');
        String newText = safe(edit.newText()).replace("\r\n", "\n").replace('\r', '\n');
        String anchorBefore = safe(edit.anchorBefore()).replace("\r\n", "\n").replace('\r', '\n');
        String anchorAfter = safe(edit.anchorAfter()).replace("\r\n", "\n").replace('\r', '\n');
        return switch (operation) {
            case "replace_exact" -> replaceExactOperation(normalizedCurrent, oldText, newText, path, phase);
            case "replace_between_anchors" -> replaceBetweenAnchorsOperation(normalizedCurrent, anchorBefore, anchorAfter, oldText, newText, path, phase);
            case "insert_after_anchor" -> insertNearAnchorOperation(normalizedCurrent, anchorBefore, anchorAfter, newText, path, phase, true);
            case "insert_before_anchor" -> insertNearAnchorOperation(normalizedCurrent, anchorBefore, anchorAfter, newText, path, phase, false);
            case "append_to_file" -> OperationApplyResult.success(appendToFile(normalizedCurrent, newText));
            default -> OperationApplyResult.failure(
                    "LLM patch " + phase + " operation_edit for " + path + " used unsupported operation: " + edit.operation(),
                    "unsupported operation_edit operation"
            );
        };
    }

    private OperationApplyResult replaceExactOperation(String current, String oldText, String newText, String path, String phase) {
        if (oldText.isBlank()) {
            return OperationApplyResult.failure(
                    "LLM patch " + phase + " replace_exact operation for " + path + " had blank oldText.",
                    "replace_exact oldText was blank"
            );
        }
        int matches = countOccurrences(current, oldText);
        if (matches != 1) {
            return OperationApplyResult.failure(
                    "LLM patch " + phase + " replace_exact operation for " + path + " matched " + matches + " current blocks; exact single-match oldText is required.",
                    "replace_exact oldText did not match exactly once"
            );
        }
        return OperationApplyResult.success(current.replace(oldText, newText));
    }

    private OperationApplyResult replaceBetweenAnchorsOperation(
            String current,
            String anchorBefore,
            String anchorAfter,
            String oldText,
            String newText,
            String path,
            String phase
    ) {
        if (!oldText.isBlank()) {
            int oldTextMatches = countOccurrences(current, oldText);
            if (oldTextMatches == 1) {
                return OperationApplyResult.success(current.replace(oldText, newText));
            }
        }
        if (anchorBefore.isBlank() || anchorAfter.isBlank()) {
            return OperationApplyResult.failure(
                    "LLM patch " + phase + " replace_between_anchors operation for " + path + " required non-blank anchorBefore and anchorAfter.",
                    "replace_between_anchors anchors were blank"
            );
        }
        int beforeMatches = countOccurrences(current, anchorBefore);
        int afterMatches = countOccurrences(current, anchorAfter);
        AnchorRange range = uniqueAnchorRange(current, anchorBefore, anchorAfter);
        if (range == null) {
            return OperationApplyResult.failure(
                    "LLM patch " + phase + " replace_between_anchors operation for " + path
                            + " matched anchorBefore=" + beforeMatches + ", anchorAfter=" + afterMatches + "; exact single-match anchors are required.",
                    "replace_between_anchors anchors did not match exactly once"
            );
        }
        return OperationApplyResult.success(current.substring(0, range.start()) + newText + current.substring(range.end()));
    }

    private OperationApplyResult insertNearAnchorOperation(
            String current,
            String anchorBefore,
            String anchorAfter,
            String newText,
            String path,
            String phase,
            boolean after
    ) {
        String operationName = after ? "insert_after_anchor" : "insert_before_anchor";
        String primaryAnchor = after ? anchorBefore : anchorAfter;
        String compatibilityAnchor = after ? anchorAfter : anchorBefore;
        String anchor = primaryAnchor.isBlank() ? compatibilityAnchor : primaryAnchor;
        String disambiguatingBefore = after ? anchorBefore : "";
        String disambiguatingAfter = after ? anchorAfter : "";
        if (primaryAnchor.isBlank() && !compatibilityAnchor.isBlank()) {
            disambiguatingBefore = after ? anchor : "";
            disambiguatingAfter = after ? "" : anchor;
        }
        if (anchor.isBlank()) {
            return OperationApplyResult.failure(
                    "LLM patch " + phase + " " + operationName + " operation for " + path + " had a blank anchor.",
                    "operation_edit anchor was blank"
            );
        }
        int matches = countOccurrences(current, anchor);
        AnchorPoint point = matches == 1
                ? new AnchorPoint(current.indexOf(anchor), after ? current.indexOf(anchor) + anchor.length() : current.indexOf(anchor))
                : uniqueInsertionPoint(current, disambiguatingBefore, disambiguatingAfter, after);
        if (point == null) {
            return OperationApplyResult.failure(
                    "LLM patch " + phase + " " + operationName + " operation for " + path
                            + " matched " + matches + " anchors; exact single-match anchor is required.",
                    "operation_edit anchor did not match exactly once"
            );
        }
        String repeatedAnchor = repeatedInsertAnchorText(anchor, newText);
        if (!repeatedAnchor.isBlank()) {
            return OperationApplyResult.failure(
                    "LLM patch " + phase + " " + operationName + " operation for " + path
                            + " repeated its anchor text inside newText: " + diagnosticPreview(repeatedAnchor),
                    "insert operation repeated its anchor text inside newText"
            );
        }
        return OperationApplyResult.success(current.substring(0, point.insertion()) + newText + current.substring(point.insertion()));
    }

    private String repeatedInsertAnchorText(String anchor, String newText) {
        String cleanAnchor = safe(anchor).replace("\r\n", "\n").replace('\r', '\n');
        String cleanNewText = safe(newText).replace("\r\n", "\n").replace('\r', '\n');
        if (cleanAnchor.isBlank() || cleanNewText.isBlank()) {
            return "";
        }
        if (meaningfulInsertAnchorFragment(cleanAnchor) && cleanNewText.contains(cleanAnchor)) {
            return cleanAnchor;
        }
        for (String rawLine : cleanAnchor.split("\n")) {
            String line = rawLine.trim();
            if (meaningfulInsertAnchorFragment(line) && cleanNewText.contains(line)) {
                return line;
            }
        }
        return "";
    }

    private boolean meaningfulInsertAnchorFragment(String value) {
        String clean = safe(value).trim();
        return clean.length() >= 6 && (clean.contains("<") || clean.contains("@") || clean.matches(".*[A-Za-z0-9가-힣].*"));
    }

    private String normalizeOperationName(String operation) {
        String normalized = safe(operation)
                .trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "replace", "replace_text", "replace_exact_text", "search_replace" -> "replace_exact";
            case "replace_between", "replace_between_anchor", "replace_between_anchors" -> "replace_between_anchors";
            case "insert_after", "insert_after_anchor", "insert_after_anchors" -> "insert_after_anchor";
            case "insertafter", "insertafteranchor", "insert_afteranchor" -> "insert_after_anchor";
            case "insert_before", "insert_before_anchor", "insert_before_anchors" -> "insert_before_anchor";
            case "insertbefore", "insertbeforeanchor", "insert_beforeanchor" -> "insert_before_anchor";
            case "append", "append_file", "append_to_file" -> "append_to_file";
            case "create", "new_file", "add_file", "create_file", "write_file" -> "create_file";
            default -> normalized;
        };
    }

    private OperationApplyResult createFileOperation(
            StructuredEdit edit,
            CodePatchFileLoader.LoadedPatchFile existingFile,
            String phase
    ) {
        String path = normalizePatchPath(edit.path());
        if (path.isBlank()) {
            return OperationApplyResult.failure(
                    "LLM patch " + phase + " create_file operation had a blank path.",
                    "create_file path was blank"
            );
        }
        String rejection = fileLoader.rejectionReason(path);
        if (rejection != null) {
            return OperationApplyResult.failure(
                    "LLM patch " + phase + " create_file operation targeted an unsafe path: " + path + " (" + rejection + ")",
                    "create_file path was unsafe"
            );
        }
        if (existingFile != null) {
            return OperationApplyResult.failure(
                    "LLM patch " + phase + " create_file operation targeted an already loaded existing file: " + path,
                    "create_file targeted an existing file"
            );
        }
        String content = firstNonBlank(edit.content(), edit.fullFileContent(), edit.newText());
        if (content.isBlank()) {
            return OperationApplyResult.failure(
                    "LLM patch " + phase + " create_file operation for " + path + " had blank content.",
                    "create_file content was blank"
            );
        }
        if (!looksLikeFullFileForPath(path, content)) {
            return OperationApplyResult.failure(
                    "LLM patch " + phase + " create_file operation failed file-shape safety checks for: " + path,
                    "create_file content failed safety checks"
            );
        }
        return OperationApplyResult.success(content.replace("\r\n", "\n").replace('\r', '\n'));
    }

    private AnchorRange uniqueAnchorRange(String current, String anchorBefore, String anchorAfter) {
        List<Integer> starts = occurrenceIndexes(current, anchorBefore);
        List<Integer> ends = occurrenceIndexes(current, anchorAfter);
        if (starts.isEmpty() || ends.isEmpty()) {
            return null;
        }
        List<AnchorRange> ranges = new ArrayList<>();
        for (int beforeIndex : starts) {
            int start = beforeIndex + anchorBefore.length();
            Integer end = ends.stream()
                    .filter(candidate -> candidate >= start)
                    .findFirst()
                    .orElse(null);
            if (end != null) {
                ranges.add(new AnchorRange(start, end));
            }
        }
        if (ranges.size() == 1) {
            return ranges.get(0);
        }
        if (starts.size() == 1) {
            int start = starts.get(0) + anchorBefore.length();
            return ends.stream()
                    .filter(candidate -> candidate >= start)
                    .findFirst()
                    .map(end -> new AnchorRange(start, end))
                    .orElse(null);
        }
        if (ends.size() == 1) {
            int end = ends.get(0);
            Integer beforeIndex = starts.stream()
                    .filter(candidate -> candidate + anchorBefore.length() <= end)
                    .reduce((first, second) -> second)
                    .orElse(null);
            return beforeIndex == null ? null : new AnchorRange(beforeIndex + anchorBefore.length(), end);
        }
        return null;
    }

    private AnchorPoint uniqueInsertionPoint(String current, String anchorBefore, String anchorAfter, boolean after) {
        if (after) {
            if (anchorBefore.isBlank()) {
                return null;
            }
            List<Integer> anchorIndexes = occurrenceIndexes(current, anchorBefore);
            if (anchorIndexes.isEmpty()) {
                return null;
            }
            if (anchorAfter.isBlank()) {
                return anchorIndexes.size() == 1
                        ? new AnchorPoint(anchorIndexes.get(0), anchorIndexes.get(0) + anchorBefore.length())
                        : null;
            }
            List<AnchorPoint> points = new ArrayList<>();
            for (int index = 0; index < anchorIndexes.size(); index++) {
                int anchorIndex = anchorIndexes.get(index);
                int insertion = anchorIndex + anchorBefore.length();
                int nextSameAnchor = index + 1 < anchorIndexes.size() ? anchorIndexes.get(index + 1) : current.length();
                int boundary = current.indexOf(anchorAfter, insertion);
                if (boundary >= insertion && boundary <= nextSameAnchor) {
                    points.add(new AnchorPoint(anchorIndex, insertion));
                }
            }
            return points.size() == 1 ? points.get(0) : null;
        }

        if (anchorAfter.isBlank()) {
            return null;
        }
        List<Integer> anchorIndexes = occurrenceIndexes(current, anchorAfter);
        if (anchorIndexes.isEmpty()) {
            return null;
        }
        if (anchorBefore.isBlank()) {
            return anchorIndexes.size() == 1
                    ? new AnchorPoint(anchorIndexes.get(0), anchorIndexes.get(0))
                    : null;
        }
        List<AnchorPoint> points = new ArrayList<>();
        int previousSameAnchor = -1;
        for (int anchorIndex : anchorIndexes) {
            int beforeIndex = current.lastIndexOf(anchorBefore, anchorIndex);
            if (beforeIndex >= 0 && beforeIndex >= previousSameAnchor) {
                points.add(new AnchorPoint(anchorIndex, anchorIndex));
            }
            previousSameAnchor = anchorIndex;
        }
        return points.size() == 1 ? points.get(0) : null;
    }

    private List<Integer> occurrenceIndexes(String value, String needle) {
        if (value == null || needle == null || needle.isBlank()) {
            return List.of();
        }
        List<Integer> indexes = new ArrayList<>();
        int index = value.indexOf(needle);
        while (index >= 0) {
            indexes.add(index);
            index = value.indexOf(needle, index + Math.max(1, needle.length()));
        }
        return indexes;
    }

    private String appendToFile(String current, String newText) {
        if (current.isBlank()) {
            return newText;
        }
        if (current.endsWith("\n") || newText.startsWith("\n") || newText.isBlank()) {
            return current + newText;
        }
        return current + "\n" + newText;
    }

    private List<StructuredEdit> structuredEdits(JsonNode root, List<CodePatchFileLoader.LoadedPatchFile> files, String defaultFormat) {
        List<StructuredEdit> edits = new ArrayList<>();
        JsonNode editsNode = root.path("edits");
        if (editsNode.isArray()) {
            for (JsonNode edit : editsNode) {
                String path = firstNonBlank(edit.path("path").asText(""), singleLoadedFilePath(files));
                edits.add(new StructuredEdit(
                        safe(defaultFormat),
                        normalizePatchPath(path),
                        textField(edit, "operation", "type"),
                        textField(edit, "fullFileContent", "full_file_content", "fullFile", "replacementContent"),
                        textField(edit, "content", "replacement"),
                        textField(edit, "search", "oldText", "old_text"),
                        textField(edit, "replace", "newText", "new_text"),
                        textField(edit, "oldText", "old_text", "search"),
                        textField(edit, "newText", "new_text", "replacement", "replace"),
                        textField(edit, "anchorBefore", "anchor_before", "beforeAnchor", "before_anchor", "anchor"),
                        textField(edit, "anchorAfter", "anchor_after", "afterAnchor", "after_anchor")
                ));
            }
        } else if ("full_file".equals(defaultFormat)) {
            if (root.has("fullFileContent")) {
                edits.add(new StructuredEdit(
                        "full_file",
                        normalizePatchPath(firstTargetPath(root, files)),
                        "",
                        root.path("fullFileContent").asText(""),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                ));
            } else if (root.path("operations").isArray()) {
                for (JsonNode edit : root.path("operations")) {
                    String path = firstNonBlank(edit.path("path").asText(""), singleLoadedFilePath(files));
                    String fullContent = textField(edit, "fullFileContent", "full_file_content", "fullFile", "fileContent", "content", "newText", "new_text");
                    edits.add(new StructuredEdit(
                            "full_file",
                            normalizePatchPath(path),
                            textField(edit, "operation", "type"),
                            fullContent,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null
                    ));
                }
            }
        }
        return edits.stream()
                .filter(edit -> !edit.path().isBlank())
                .toList();
    }

    private List<StructuredEdit> structuredOperationEdits(JsonNode root, List<CodePatchFileLoader.LoadedPatchFile> files) {
        List<StructuredEdit> edits = new ArrayList<>();
        JsonNode operationsNode = root.path("operations");
        if (!operationsNode.isArray()) {
            operationsNode = root.path("edits");
        }
        if (operationsNode.isArray()) {
            for (JsonNode edit : operationsNode) {
                String path = firstNonBlank(edit.path("path").asText(""), singleLoadedFilePath(files));
                edits.add(new StructuredEdit(
                        "operation_edit",
                        normalizePatchPath(path),
                        textField(edit, "operation", "type"),
                        textField(edit, "fullFileContent", "full_file_content", "fullFile", "fileContent"),
                        textField(edit, "content", "fileContent"),
                        null,
                        null,
                        textField(edit, "oldText", "old_text", "search"),
                        textField(edit, "newText", "new_text", "replacement", "replace", "insertText", "insert_text"),
                        textField(edit, "anchorBefore", "anchor_before", "beforeAnchor", "before_anchor", "anchor"),
                        textField(edit, "anchorAfter", "anchor_after", "afterAnchor", "after_anchor")
                ));
            }
        }
        return edits.stream()
                .filter(edit -> !edit.path().isBlank())
                .toList();
    }

    private String textField(JsonNode node, String... names) {
        if (node == null || names == null) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isMissingNode() && !value.isNull()) {
                return value.asText(null);
            }
        }
        return null;
    }

    private String firstTargetPath(JsonNode root, List<CodePatchFileLoader.LoadedPatchFile> files) {
        JsonNode targetFiles = root.path("targetFiles");
        if (targetFiles.isArray() && !targetFiles.isEmpty()) {
            return targetFiles.get(0).asText("");
        }
        return singleLoadedFilePath(files);
    }

    private String singleLoadedFilePath(List<CodePatchFileLoader.LoadedPatchFile> files) {
        return files != null && files.size() == 1 ? files.get(0).path() : "";
    }

    private CodePatchFileLoader.LoadedPatchFile loadedFileByPath(List<CodePatchFileLoader.LoadedPatchFile> files, String path) {
        String normalized = normalizePatchPath(path);
        for (CodePatchFileLoader.LoadedPatchFile file : files == null ? List.<CodePatchFileLoader.LoadedPatchFile>of() : files) {
            if (normalizePatchPath(file.path()).equals(normalized)) {
                return file;
            }
        }
        return null;
    }

    private int countOccurrences(String value, String needle) {
        if (safe(value).isEmpty() || safe(needle).isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private boolean sameNormalizedContent(String left, String right) {
        return safe(left).replace("\r\n", "\n").replace('\r', '\n').trim()
                .equals(safe(right).replace("\r\n", "\n").replace('\r', '\n').trim());
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

    private String normalizePatchDiffHeaders(
            String diff,
            List<CodePatchFileLoader.LoadedPatchFile> files,
            List<String> warnings,
            String phase
    ) {
        String clean = safe(diff).replace("\r\n", "\n").replace('\r', '\n').trim();
        if (clean.isBlank() || clean.startsWith("NO_PATCH") || clean.contains("\n+++ b/") || clean.startsWith("+++ b/")) {
            return clean;
        }
        List<String> targetPaths = files == null
                ? List.of()
                : files.stream()
                .map(CodePatchFileLoader.LoadedPatchFile::path)
                .map(this::normalizePatchPath)
                .filter(path -> !path.isBlank())
                .toList();
        if (clean.startsWith("@@") && targetPaths.size() == 1) {
            String path = targetPaths.get(0);
            warnings.add("LLM patch " + phase + " output omitted file headers; inferred unified diff headers for target file: " + path);
            return "--- a/" + path + "\n+++ b/" + path + "\n" + clean;
        }
        String[] lines = clean.split("\n", -1);
        if (lines.length < 2 || !lines[0].startsWith("--- ")) {
            return clean;
        }
        String oldPath = normalizeDiffPath(lines[0].substring(4).trim().split("\\s+", 2)[0]);
        if (oldPath.isBlank() || "/dev/null".equals(oldPath) || !targetPaths.contains(oldPath)) {
            return clean;
        }
        if (!lines[1].startsWith("@@")) {
            return clean;
        }
        warnings.add("LLM patch " + phase + " output omitted +++ file header; inferred +++ b/" + oldPath + " from the matching --- header.");
        StringBuilder builder = new StringBuilder();
        builder.append(lines[0]).append('\n');
        builder.append("+++ b/").append(oldPath);
        for (int i = 1; i < lines.length; i++) {
            builder.append('\n').append(lines[i]);
        }
        return builder.toString().trim();
    }

    private String normalizePatchDiffExistingLineWhitespace(
            String diff,
            List<CodePatchFileLoader.LoadedPatchFile> files,
            List<String> warnings,
            String phase
    ) {
        String clean = safe(diff).replace("\r\n", "\n").replace('\r', '\n').trim();
        if (clean.isBlank() || clean.startsWith("NO_PATCH")) {
            return clean;
        }
        Map<String, List<String>> linesByPath = new LinkedHashMap<>();
        for (CodePatchFileLoader.LoadedPatchFile file : files == null ? List.<CodePatchFileLoader.LoadedPatchFile>of() : files) {
            linesByPath.put(normalizePatchPath(file.path()), splitPatchLines(file.content()));
        }
        if (linesByPath.isEmpty()) {
            return clean;
        }
        String currentPath = "";
        String inheritedIndent = null;
        boolean changed = false;
        StringBuilder builder = new StringBuilder();
        String[] lines = clean.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String updated = line;
            if (line.startsWith("+++ ")) {
                currentPath = normalizeDiffPath(line.substring(4).trim().split("\\s+", 2)[0]);
                inheritedIndent = null;
            } else if (line.startsWith("-") && !line.startsWith("---") && !currentPath.isBlank()) {
                String text = line.substring(1);
                String actual = findUniqueTrimMatchedLine(linesByPath.get(currentPath), text);
                if (actual != null && !actual.equals(text)) {
                    updated = "-" + actual;
                    inheritedIndent = leadingWhitespace(actual);
                    changed = true;
                }
            } else if (line.startsWith("+") && !line.startsWith("+++") && inheritedIndent != null) {
                String text = line.substring(1);
                String trimmed = text.stripLeading();
                if (!trimmed.isBlank()
                        && !trimmed.startsWith("<")
                        && !trimmed.startsWith("}")
                        && leadingWhitespace(text).length() < inheritedIndent.length()) {
                    updated = "+" + inheritedIndent + trimmed;
                    changed = true;
                }
            } else if (line.startsWith(" ") && !currentPath.isBlank()) {
                String text = line.substring(1);
                String actual = findUniqueTrimMatchedLine(linesByPath.get(currentPath), text);
                if (actual != null && !actual.equals(text)) {
                    updated = " " + actual;
                    inheritedIndent = leadingWhitespace(actual);
                    changed = true;
                } else {
                    inheritedIndent = null;
                }
            } else if (line.startsWith("@@")) {
                inheritedIndent = null;
            }
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(updated);
        }
        if (changed) {
            warnings.add("LLM patch " + phase + " output existing-line whitespace was normalized against the current file content.");
        }
        return changed ? builder.toString().trim() : clean;
    }

    private String normalizePatchDiffAbsentContextLinesAfterAdditions(
            String diff,
            List<CodePatchFileLoader.LoadedPatchFile> files,
            List<String> warnings,
            String phase
    ) {
        String clean = safe(diff).replace("\r\n", "\n").replace('\r', '\n').trim();
        if (clean.isBlank() || clean.startsWith("NO_PATCH")) {
            return clean;
        }
        Map<String, List<String>> linesByPath = new LinkedHashMap<>();
        for (CodePatchFileLoader.LoadedPatchFile file : files == null ? List.<CodePatchFileLoader.LoadedPatchFile>of() : files) {
            linesByPath.put(normalizePatchPath(file.path()), splitPatchLines(file.content()));
        }
        if (linesByPath.isEmpty()) {
            return clean;
        }
        String currentPath = "";
        boolean inHunk = false;
        boolean sawAdditionInHunk = false;
        boolean changed = false;
        StringBuilder builder = new StringBuilder();
        String[] lines = clean.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String updated = line;
            if (line.startsWith("+++ ")) {
                currentPath = normalizeDiffPath(line.substring(4).trim().split("\\s+", 2)[0]);
                inHunk = false;
                sawAdditionInHunk = false;
            } else if (line.startsWith("@@")) {
                inHunk = true;
                sawAdditionInHunk = false;
            } else if (inHunk && line.startsWith("+") && !line.startsWith("+++")) {
                sawAdditionInHunk = true;
            } else if (inHunk && sawAdditionInHunk && line.startsWith(" ") && !currentPath.isBlank()) {
                String text = line.substring(1);
                if (!text.isBlank() && !hasAnyTrimMatchedLine(linesByPath.get(currentPath), text)) {
                    updated = "+" + text;
                    changed = true;
                }
            }
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(updated);
        }
        if (changed) {
            warnings.add("LLM patch " + phase + " output absent trailing context lines were reclassified as additions because they do not exist in the current file.");
        }
        return changed ? builder.toString().trim() : clean;
    }

    private boolean hasPatchMutationLines(String diff) {
        for (String line : safe(diff).replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                return true;
            }
            if (line.startsWith("-") && !line.startsWith("---")) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeUnifiedDiffEnvelope(String diff) {
        String clean = safe(diff).replace("\r\n", "\n").replace('\r', '\n').trim();
        return clean.startsWith("--- ") && clean.contains("\n+++ ");
    }

    private boolean isWhitespaceOnlyPatch(String diff) {
        List<String> removed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        for (String line : safe(diff).replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (line.startsWith("---") || line.startsWith("+++") || line.startsWith("@@")) {
                continue;
            }
            if (line.startsWith("-")) {
                removed.add(line.substring(1));
            } else if (line.startsWith("+")) {
                added.add(line.substring(1));
            }
        }
        if (removed.isEmpty() && added.isEmpty()) {
            return false;
        }
        if (removed.size() != added.size()) {
            return false;
        }
        for (int i = 0; i < removed.size(); i++) {
            if (!removed.get(i).replaceAll("\\s+", "").equals(added.get(i).replaceAll("\\s+", ""))) {
                return false;
            }
        }
        return true;
    }

    private boolean looksLikeFormattingOnlyRequest(String instruction) {
        String lower = safe(instruction).toLowerCase(Locale.ROOT);
        return lower.contains("format")
                || lower.contains("formatting")
                || lower.contains("prettier")
                || lower.contains("indent")
                || lower.contains("whitespace")
                || lower.contains("lint")
                || lower.contains("정렬")
                || lower.contains("포맷")
                || lower.contains("들여쓰기")
                || lower.contains("공백");
    }

    private boolean hasAnyTrimMatchedLine(List<String> lines, String expected) {
        String needle = safe(expected).trim();
        if (needle.isBlank() || lines == null || lines.isEmpty()) {
            return false;
        }
        for (String line : lines) {
            if (safe(line).trim().equals(needle)) {
                return true;
            }
        }
        return false;
    }

    private String findUniqueTrimMatchedLine(List<String> lines, String expected) {
        String needle = safe(expected).trim();
        if (needle.isBlank() || lines == null || lines.isEmpty()) {
            return null;
        }
        String match = null;
        int count = 0;
        for (String line : lines) {
            if (safe(line).trim().equals(needle)) {
                match = line;
                count++;
                if (count > 1) {
                    return null;
                }
            }
        }
        return count == 1 ? match : null;
    }

    private String leadingWhitespace(String value) {
        String text = safe(value);
        int index = 0;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return text.substring(0, index);
    }

    private List<String> changedPaths(String diff) {
        Set<String> paths = new LinkedHashSet<>();
        safe(diff).lines()
                .filter(line -> line.startsWith("+++ b/"))
                .map(line -> line.substring("+++ b/".length()).trim().split("\\s+", 2)[0])
                .forEach(paths::add);
        return List.copyOf(paths);
    }

    private List<String> validationTargetsForPatch(String diff, List<CodePatchFileLoader.LoadedPatchFile> files) {
        Set<String> paths = new LinkedHashSet<>();
        for (CodePatchFileLoader.LoadedPatchFile file : files == null ? List.<CodePatchFileLoader.LoadedPatchFile>of() : files) {
            if (file != null && file.path() != null && !file.path().isBlank()) {
                paths.add(normalizePatchPath(file.path()));
            }
        }
        paths.addAll(createdPaths(diff));
        return List.copyOf(paths);
    }

    private Set<String> createdPaths(String diff) {
        Set<String> paths = new LinkedHashSet<>();
        String oldPath = null;
        for (String line : safe(diff).replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (line.startsWith("--- ")) {
                oldPath = normalizeDiffPath(line.substring(4).trim().split("\\s+", 2)[0]);
            } else if (line.startsWith("+++ ")) {
                String newPath = normalizeDiffPath(line.substring(4).trim().split("\\s+", 2)[0]);
                if ("/dev/null".equals(oldPath) && !newPath.isBlank() && !"/dev/null".equals(newPath)) {
                    paths.add(newPath);
                }
                oldPath = null;
            }
        }
        return paths;
    }

    private String createdFileContentFromDiff(String diff, String path) {
        String normalizedPath = normalizePatchPath(path);
        StringBuilder builder = new StringBuilder();
        boolean inTarget = false;
        String oldPath = null;
        for (String line : safe(diff).replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (line.startsWith("--- ")) {
                oldPath = normalizeDiffPath(line.substring(4).trim().split("\\s+", 2)[0]);
                inTarget = false;
            } else if (line.startsWith("+++ ")) {
                String newPath = normalizeDiffPath(line.substring(4).trim().split("\\s+", 2)[0]);
                inTarget = "/dev/null".equals(oldPath) && normalizedPath.equals(newPath);
            } else if (inTarget && line.startsWith("+") && !line.startsWith("+++")) {
                builder.append(line.substring(1)).append('\n');
            } else if (line.startsWith("--- ")) {
                inTarget = false;
            }
        }
        return builder.toString();
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
        return values.isEmpty() ? List.of("선택된 target file을 기준으로 최소 변경 계획을 검토하세요.") : List.copyOf(values);
    }

    private List<String> textArrayOrEmpty(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String value = item.asText();
                if (value != null && !value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return List.copyOf(values);
    }

    private List<String> testSuggestions(List<CodePatchFileLoader.LoadedPatchFile> files) {
        return testSuggestions(files, files == null ? List.of() : files.stream().map(CodePatchFileLoader.LoadedPatchFile::path).toList());
    }

    private List<String> testSuggestions(List<CodePatchFileLoader.LoadedPatchFile> files, List<String> changedPaths) {
        List<String> paths = changedPaths == null || changedPaths.isEmpty()
                ? (files == null ? List.of() : files.stream().map(CodePatchFileLoader.LoadedPatchFile::path).toList())
                : changedPaths;
        boolean frontend = paths.stream().anyMatch(path -> path.startsWith("frontend/") || path.endsWith(".jsx") || path.endsWith(".tsx") || path.endsWith(".js") || path.endsWith(".html") || path.endsWith(".css"));
        boolean backend = paths.stream().anyMatch(path -> path.startsWith("backend/") || path.endsWith(".java"));
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
        if (lower.contains("fix") || lower.contains("bug")) return "bugfix";
        if (lower.contains("docs") || lower.contains("readme")) return "docs";
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

    private int lineCount(String value) {
        String clean = safe(value);
        if (clean.isEmpty()) {
            return 0;
        }
        int count = 1;
        for (int index = 0; index < clean.length(); index++) {
            if (clean.charAt(index) == '\n') {
                count++;
            }
        }
        return count;
    }

    private String planText(String value) {
        String clean = safe(value).trim();
        if (containsHangul(clean) || !looksLikeUtf8DecodedAsLatin1(clean)) {
            return clean;
        }
        String repaired = new String(clean.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        return containsHangul(repaired) ? repaired : clean;
    }

    private boolean looksLikeUtf8DecodedAsLatin1(String value) {
        int suspicious = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if ((ch >= '\u0080' && ch <= '\u009F') || ch == 'ë' || ch == 'ì' || ch == 'í' || ch == 'ê') {
                suspicious++;
            }
        }
        return suspicious >= 2;
    }

    private boolean containsHangul(String value) {
        for (int i = 0; i < value.length(); i++) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(value.charAt(i));
            if (block == Character.UnicodeBlock.HANGUL_SYLLABLES
                    || block == Character.UnicodeBlock.HANGUL_JAMO
                    || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) {
                return true;
            }
        }
        return false;
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String firstNonBlank(String first, String second, String third) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return third == null ? "" : third;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record PatchBatchPlanItem(
            String id,
            List<String> targetFiles,
            String goal,
            String rationale
    ) {
    }

    private record PatchBatchResult(
            String id,
            List<String> targetFiles,
            CodeAgentPatchResponse response
    ) {
    }
}
