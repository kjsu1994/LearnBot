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
    private static final int LLM_DIAGNOSTIC_PREVIEW_CHARS = 2000;
    private static final int PATCH_OUTPUT_TOKENS = 4096;
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
                    "??㉱???袁⑤?獄??잙??딀뤃?살쾸? ?遊붋?브퀗?꿴뜮????깆쓧????瑜곸젧 ??ｌ뫓???嶺뚮씭??キ?????怨룸????덈펲.",
                    List.of(),
                    List.of("嶺뚯쉶?꾣룇 ?뺢퀡???낅ご???る궞??묒퀪?⑤벚?????逾х춯? ???????닿뎄, 嶺뚮∥?꾥땻??類ㅺ뎄???怨뺣뼺????낅슣?섋땻??"),
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
                "?롪틵????잙??딀뤃?용ご??リ옇????怨쀬Ŧ ??瑜곸젧 ?熬곣뫀沅????逾????ル‘????곕????덈펲.",
                List.of(),
                List.of(
                        "??ル‘??????逾???熬곣뫗????뚮뿭寃???筌먦끉逾??紐껊퉵??",
                        "??븐슙??????됱굚??嶺뚯쉳?????㉱???살춨 嶺뚣끉裕???곌떠??롪퍔?ε퐲???戮?닱??紐껊퉵??",
                        "diff ??諛댁뎽 ????類ㅼ뮅 ?롪틵?嶺뚯빘鍮?????沅??unified diff嶺??꾩룇瑗???紐껊퉵??"
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
                addLlmPatchOutputDiagnostics(warnings, "initial", modelResult, diff);
                CodeAgentPatchResponse repaired = tryRepairLlmPatch(
                        safeInstruction,
                        filesToPatch,
                        modelOutput,
                        List.of("Initial model output returned no patch. The provided file contents are the actual current workspace state; if the file appears incomplete or truncated, treat that as the bug and produce a minimal unified diff when it satisfies the user request."),
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
        PatchValidationResult validation = validationService.validate(diff, filesToPatch.stream().map(CodePatchFileLoader.LoadedPatchFile::path).toList());
        warnings.addAll(validation.warnings());
        if (!validation.valid()) {
            warnings.add("LLM patch generation produced an invalid diff; LLM repair will be attempted before any deterministic fallback.");
            addLlmPatchOutputDiagnostics(warnings, "initial", modelResult, diff);
            CodeAgentPatchResponse repaired = tryRepairLlmPatch(safeInstruction, filesToPatch, modelOutput, validation.warnings(), warnings);
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
                testSuggestions(filesToPatch),
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

    private CodeAgentPatchResponse tryRepairLlmPatch(
            String instruction,
            List<CodePatchFileLoader.LoadedPatchFile> files,
            String previousOutput,
            List<String> validationWarnings,
            List<String> warnings
    ) {
        String repairPreviousOutput = previousOutput;
        List<String> repairWarnings = validationWarnings == null ? List.of() : validationWarnings;
        for (int attempt = 1; attempt <= 2; attempt++) {
            String phase = attempt == 1 ? "repair" : "repair retry";
            try {
                OllamaClient.ChatResult repairedResult = patchChatResult(
                        patchRepairSystemPrompt(),
                        patchRepairUserPrompt(instruction, files, repairPreviousOutput, repairWarnings, attempt > 1),
                        warnings,
                        phase
                );
                String repairedOutput = repairedResult.content();
                String repairedDiff = materializePatchFromModelOutput(repairedOutput, files, warnings, phase);
                repairedDiff = normalizePatchDiffHeaders(repairedDiff, files, warnings, phase);
                repairedDiff = normalizePatchDiffExistingLineWhitespace(repairedDiff, files, warnings, phase);
                repairedDiff = normalizePatchDiffAbsentContextLinesAfterAdditions(repairedDiff, files, warnings, phase);
                if (looksLikeUnifiedDiffEnvelope(repairedDiff) && !hasPatchMutationLines(repairedDiff)) {
                    warnings.add("LLM patch " + phase + " output contained no added or removed lines.");
                    addLlmPatchOutputDiagnostics(warnings, phase, repairedResult, repairedDiff);
                    repairPreviousOutput = repairedDiff;
                    repairWarnings = List.of("Patch contains no added or removed file-content lines. Produce a real minimal unified diff against the exact current file contents.");
                    continue;
                }
                if (!looksLikeFormattingOnlyRequest(instruction) && isWhitespaceOnlyPatch(repairedDiff)) {
                    warnings.add("LLM patch " + phase + " output only changed whitespace for a non-formatting request.");
                    addLlmPatchOutputDiagnostics(warnings, phase, repairedResult, repairedDiff);
                    repairPreviousOutput = repairedDiff;
                    repairWarnings = List.of("Patch only changes whitespace, but the instruction asks for behavioral/content repair. Produce a meaningful minimal unified diff against the exact current file contents.");
                    continue;
                }
                PatchValidationResult repairedValidation = validationService.validate(
                        repairedDiff,
                        files.stream().map(CodePatchFileLoader.LoadedPatchFile::path).toList()
                );
                warnings.add(attempt == 1
                        ? "LLM patch repair attempted after invalid initial diff."
                        : "LLM patch repair retry attempted after the first repair still did not match current file contents.");
                warnings.addAll(repairedValidation.warnings());
                if (!repairedValidation.valid()) {
                    addLlmPatchOutputDiagnostics(warnings, phase, repairedResult, repairedDiff);
                    repairPreviousOutput = repairedDiff;
                    repairWarnings = repairedValidation.warnings();
                    continue;
                }
                PatchContextValidationResult repairedContextValidation = validatePatchContext(repairedDiff, files);
                warnings.addAll(repairedContextValidation.warnings());
                if (!repairedContextValidation.valid()) {
                    addLlmPatchOutputDiagnostics(warnings, phase, repairedResult, repairedDiff);
                    repairPreviousOutput = repairedDiff;
                    repairWarnings = repairedContextValidation.warnings();
                    continue;
                }
                PatchContextValidationResult repairedSemanticValidation = validatePatchResultSemantics(repairedDiff, files);
                warnings.addAll(repairedSemanticValidation.warnings());
                if (!repairedSemanticValidation.valid()) {
                    addLlmPatchOutputDiagnostics(warnings, phase, repairedResult, repairedDiff);
                    repairPreviousOutput = repairedDiff;
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
                    firstNonBlank(root.path("summary").asText(), "??瑜곸젧 ??ｌ뫓?????諛댁뎽???곕????덈펲."),
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
        List<String> warnings = new ArrayList<>();
        if (hunksByPath.isEmpty()) {
            warnings.add("Patch context validation found no applicable hunks.");
            return new PatchContextValidationResult(false, List.copyOf(warnings));
        }
        for (Map.Entry<String, List<PatchHunk>> entry : hunksByPath.entrySet()) {
            String path = normalizePatchPath(entry.getKey());
            String content = contentByPath.get(path);
            if (content == null) {
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
        List<String> warnings = new ArrayList<>();
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
                Integer oldStart = parseOldStart(rawLine);
                if (oldStart == null || currentPath.isBlank() || currentPath.equals("/dev/null")) {
                    currentHunk = null;
                    continue;
                }
                currentHunk = new PatchHunk(oldStart, new ArrayList<>());
                result.computeIfAbsent(currentPath, ignored -> new ArrayList<>()).add(currentHunk);
                continue;
            }
            if (currentHunk == null || rawLine.startsWith("\\ No newline")) {
                continue;
            }
            if (rawLine.isEmpty()) {
                currentHunk.lines().add(new PatchLine(' ', ""));
                continue;
            }
            char marker = rawLine.charAt(0);
            if (marker == ' ' || marker == '-' || marker == '+') {
                currentHunk.lines().add(new PatchLine(marker, rawLine.substring(1)));
            }
        }
        result.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        return result;
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

    private Integer parseOldStart(String hunkHeader) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("@@ -(\\d+)").matcher(safe(hunkHeader));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
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

    private record PatchHunk(int oldStart, List<PatchLine> lines) {
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
                You are the decision maker for LearnBot Patch Agent v1.
                Return JSON only.
                You decide whether more observation is needed, which exact current lines are wrong, and what patch should be proposed.
                Do not output <think> blocks, reasoning, analysis, or explanations.
                Do not use markdown fences.
                Modify only the provided target files.
                Do not create, delete, rename, or chmod files.
                Preserve the existing style.
                Preserve the user's requested language and content constraints.
                If the user asks for Korean/Hangul text, added prose must be Korean.
                Do not invent generic placeholders such as "Added by LearnBot" unless the user explicitly asked for that text.
                Prefer editFormat=operation_edit. Use small operations with exact anchors copied from EXACT_CONTENT.
                Do not use editFormat=full_file unless the user explicitly asks to rewrite the whole file or the file is very small.
                Use search_replace only when the search block appears exactly once in the current file.
                Use legacy unifiedDiff only when you are certain every hunk context line is copied exactly from the current file.
                The server may reject unsafe or malformed output and may materialize your edits into a unified diff, but it must not author replacement content for you.
                JSON shape:
                {"action":"propose_patch|observe_more|ask_clarification|stop","editFormat":"operation_edit|full_file|search_replace|unified_diff","targetFiles":["path"],"diagnosis":"...","changeIntent":"...","operations":[{"path":"path","operation":"replace_between_anchors|insert_after_anchor|insert_before_anchor|replace_exact|append_to_file","anchorBefore":"exact current text","anchorAfter":"exact current text","oldText":"exact current text","newText":"LLM-authored replacement or insertion text","reason":"..."}],"edits":[],"verificationPlan":["..."],"riskNotes":["..."]}
                For operation_edit, every anchorBefore, anchorAfter, and oldText value must be copied exactly from EXACT_CONTENT and must match uniquely.
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
                .append("- You must decide the target lines, diagnosis, and patch content.\n")
                .append("- If the supplied file content is insufficient, choose action=observe_more instead of guessing.\n\n")
                .append("TARGET_FILES:\n");
        for (CodePatchFileLoader.LoadedPatchFile file : files) {
            appendFileContext(builder, file, false);
        }
        return builder.toString();
    }

    private String patchRepairSystemPrompt() {
        return """
                You repair invalid LearnBot patch proposals.
                Return JSON only.
                You decide the corrected diagnosis and patch from the exact current file contents.
                Do not output <think> blocks, reasoning, analysis, or explanations.
                Do not use markdown fences.
                Modify only the provided target files.
                Do not create, delete, rename, or chmod files.
                Prefer editFormat=operation_edit. Use small operations with exact anchors copied from EXACT_CONTENT.
                Do not use editFormat=full_file unless the user explicitly asks to rewrite the whole file or the file is very small.
                Use search_replace only when the search block appears exactly once in the current file.
                Use legacy unifiedDiff only when every hunk context line is copied exactly from the provided file contents, including indentation.
                Keep the patch small and targeted.
                Preserve the user's requested language and content constraints.
                The provided file contents are the actual current workspace state.
                If the previous output declined because a file looked incomplete or truncated, treat that incomplete file state as the bug and produce a minimal repair diff when it satisfies the user request.
                JSON shape:
                {"action":"propose_patch|observe_more|ask_clarification|stop","editFormat":"operation_edit|full_file|search_replace|unified_diff","targetFiles":["path"],"diagnosis":"...","changeIntent":"...","operations":[{"path":"path","operation":"replace_between_anchors|insert_after_anchor|insert_before_anchor|replace_exact|append_to_file","anchorBefore":"exact current text","anchorAfter":"exact current text","oldText":"exact current text","newText":"LLM-authored replacement or insertion text","reason":"..."}],"edits":[],"verificationPlan":["..."],"riskNotes":["..."]}
                For operation_edit, every anchorBefore, anchorAfter, and oldText value must be copied exactly from EXACT_CONTENT and must match uniquely.
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
            builder.append("- ").append(warning).append("\n");
        }
        builder.append("\nTarget files with exact current contents:\n");
        for (CodePatchFileLoader.LoadedPatchFile file : files) {
            appendFileContext(builder, file, true);
        }
        builder.append("Previous invalid output for reference only; do not assume it was applied:\n")
                .append(safe(previousOutput))
                .append("\n");
        return builder.toString();
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
                .append("PATCH_RULE: If using legacy unifiedDiff, hunk context must copy exact lines from EXACT_CONTENT, not LINE_NUMBERED_VIEW.\n")
                .append("LINE_NUMBERED_VIEW:\n");
        appendLineNumberedContent(builder, lines);
        builder.append("EXACT_CONTENT_START ").append(file.path()).append("\n")
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
        for (StructuredEdit operation : operations) {
            CodePatchFileLoader.LoadedPatchFile file = loadedFileByPath(files, operation.path());
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
        for (CodePatchFileLoader.LoadedPatchFile file : files == null ? List.<CodePatchFileLoader.LoadedPatchFile>of() : files) {
            String path = normalizePatchPath(file.path());
            String updated = updatedByPath.get(path);
            if (updated == null || sameNormalizedContent(file.content(), updated)) {
                continue;
            }
            diffs.add(fullFileReplacementDiff(file.path(), file.content(), updated));
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
            diffs.add(fullFileReplacementDiff(file.path(), file.content(), updated));
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
        String operation = safe(edit.operation()).trim().toLowerCase(Locale.ROOT).replace('-', '_');
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
            case "replace_between_anchors" -> replaceBetweenAnchorsOperation(normalizedCurrent, anchorBefore, anchorAfter, newText, path, phase);
            case "insert_after_anchor" -> insertNearAnchorOperation(normalizedCurrent, anchorBefore, newText, path, phase, true);
            case "insert_before_anchor" -> insertNearAnchorOperation(normalizedCurrent, anchorAfter, newText, path, phase, false);
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
            String newText,
            String path,
            String phase
    ) {
        if (anchorBefore.isBlank() || anchorAfter.isBlank()) {
            return OperationApplyResult.failure(
                    "LLM patch " + phase + " replace_between_anchors operation for " + path + " required non-blank anchorBefore and anchorAfter.",
                    "replace_between_anchors anchors were blank"
            );
        }
        int beforeMatches = countOccurrences(current, anchorBefore);
        int afterMatches = countOccurrences(current, anchorAfter);
        if (beforeMatches != 1 || afterMatches != 1) {
            return OperationApplyResult.failure(
                    "LLM patch " + phase + " replace_between_anchors operation for " + path
                            + " matched anchorBefore=" + beforeMatches + ", anchorAfter=" + afterMatches + "; exact single-match anchors are required.",
                    "replace_between_anchors anchors did not match exactly once"
            );
        }
        int beforeIndex = current.indexOf(anchorBefore);
        int start = beforeIndex + anchorBefore.length();
        int end = current.indexOf(anchorAfter);
        if (end < start) {
            return OperationApplyResult.failure(
                    "LLM patch " + phase + " replace_between_anchors operation for " + path + " had anchorAfter before anchorBefore.",
                    "replace_between_anchors anchor order was invalid"
            );
        }
        return OperationApplyResult.success(current.substring(0, start) + newText + current.substring(end));
    }

    private OperationApplyResult insertNearAnchorOperation(
            String current,
            String anchor,
            String newText,
            String path,
            String phase,
            boolean after
    ) {
        String operationName = after ? "insert_after_anchor" : "insert_before_anchor";
        if (anchor.isBlank()) {
            return OperationApplyResult.failure(
                    "LLM patch " + phase + " " + operationName + " operation for " + path + " had a blank anchor.",
                    "operation_edit anchor was blank"
            );
        }
        int matches = countOccurrences(current, anchor);
        if (matches != 1) {
            return OperationApplyResult.failure(
                    "LLM patch " + phase + " " + operationName + " operation for " + path
                            + " matched " + matches + " anchors; exact single-match anchor is required.",
                    "operation_edit anchor did not match exactly once"
            );
        }
        int index = current.indexOf(anchor);
        int insertion = after ? index + anchor.length() : index;
        return OperationApplyResult.success(current.substring(0, insertion) + newText + current.substring(insertion));
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
                        firstNonBlank(edit.path("operation").asText(""), edit.path("type").asText("")),
                        edit.path("fullFileContent").asText(null),
                        edit.path("content").asText(null),
                        edit.path("search").asText(null),
                        edit.path("replace").asText(null),
                        edit.path("oldText").asText(null),
                        edit.path("newText").asText(null),
                        edit.path("anchorBefore").asText(null),
                        edit.path("anchorAfter").asText(null)
                ));
            }
        } else if ("full_file".equals(defaultFormat) && root.has("fullFileContent")) {
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
                        firstNonBlank(edit.path("operation").asText(""), edit.path("type").asText("")),
                        null,
                        null,
                        null,
                        null,
                        edit.path("oldText").asText(null),
                        edit.path("newText").asText(null),
                        edit.path("anchorBefore").asText(null),
                        edit.path("anchorAfter").asText(null)
                ));
            }
        }
        return edits.stream()
                .filter(edit -> !edit.path().isBlank())
                .toList();
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
        return values.isEmpty() ? List.of("??ル‘???target file??嶺뚣끉裕???곌떠??롪퍔????뿉???瑜곸젧??紐껊퉵??") : List.copyOf(values);
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

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
