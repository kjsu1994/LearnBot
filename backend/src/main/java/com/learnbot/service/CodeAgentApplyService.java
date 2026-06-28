package com.learnbot.service;

import com.learnbot.dto.CodeAgentApplyResponse;
import com.learnbot.dto.CodeAgentRollbackResponse;
import com.learnbot.dto.CodeAgentTestResponse;
import com.learnbot.dto.PatchApplySnapshot;
import com.learnbot.dto.PatchValidationResult;
import com.learnbot.repository.CodeAgentPatchSessionRepository;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class CodeAgentApplyService {
    private static final int TEST_TIMEOUT_SECONDS = 120;

    private final CodePatchFileLoader fileLoader;
    private final PatchValidationService validationService;
    private final CodeAgentPatchSessionRepository sessionRepository;

    public CodeAgentApplyService(
            CodePatchFileLoader fileLoader,
            PatchValidationService validationService,
            CodeAgentPatchSessionRepository sessionRepository
    ) {
        this.fileLoader = fileLoader;
        this.validationService = validationService;
        this.sessionRepository = sessionRepository;
    }

    public CodeAgentApplyResponse apply(
            UUID repositoryId,
            UUID spaceId,
            UUID userId,
            String instruction,
            String diff,
            List<String> targetFiles
    ) {
        List<String> warnings = new ArrayList<>();
        List<String> normalizedTargets = fileLoader.normalizeRequestedPaths(targetFiles, warnings);
        PatchValidationResult validation = validationService.validate(diff, normalizedTargets);
        warnings.addAll(validation.warnings());
        if (!validation.valid()) {
            return new CodeAgentApplyResponse(null, false, List.of(), List.copyOf(warnings), false);
        }

        Map<String, String> patchedContent = applyDiff(repositoryId, diff, normalizedTargets);
        List<PatchApplySnapshot> snapshots = new ArrayList<>();
        Map<String, String> afterHashes = new LinkedHashMap<>();
        for (String path : patchedContent.keySet()) {
            CodePatchFileLoader.LocalTargetFile target = fileLoader.localTarget(repositoryId, path);
            String before = target.content();
            String after = patchedContent.get(path);
            snapshots.add(new PatchApplySnapshot(path, sha256(before), sha256(after), before));
            afterHashes.put(path, sha256(after));
            try {
                Files.writeString(target.localPath(), after, StandardCharsets.UTF_8);
            } catch (Exception ex) {
                throw new IllegalArgumentException("Failed to write patched file: " + path, ex);
            }
        }
        CodeAgentPatchSession session = sessionRepository.createApplied(
                repositoryId,
                spaceId,
                userId,
                safe(instruction),
                safe(diff),
                List.copyOf(patchedContent.keySet()),
                List.copyOf(snapshots),
                Map.copyOf(afterHashes),
                List.copyOf(warnings)
        );
        return new CodeAgentApplyResponse(session.id(), true, List.copyOf(patchedContent.keySet()), List.copyOf(warnings), true);
    }

    public CodeAgentRollbackResponse rollback(UUID repositoryId, UUID spaceId, UUID userId, UUID patchSessionId) {
        CodeAgentPatchSession session = session(patchSessionId, repositoryId, spaceId, userId);
        List<String> warnings = new ArrayList<>();
        if (!"APPLIED".equals(session.status())) {
            warnings.add("Only APPLIED patch sessions can be rolled back.");
            return new CodeAgentRollbackResponse(patchSessionId, false, List.of(), warnings);
        }
        List<String> restored = new ArrayList<>();
        for (PatchApplySnapshot snapshot : session.beforeSnapshots()) {
            CodePatchFileLoader.LocalTargetFile target = fileLoader.localTarget(repositoryId, snapshot.path());
            String currentHash = sha256(target.content());
            String expectedAfter = session.afterHashes().get(snapshot.path());
            if (!currentHash.equals(expectedAfter)) {
                warnings.add("Rollback refused because file changed after patch apply: " + snapshot.path());
                return new CodeAgentRollbackResponse(patchSessionId, false, List.copyOf(restored), List.copyOf(warnings));
            }
        }
        for (PatchApplySnapshot snapshot : session.beforeSnapshots()) {
            CodePatchFileLoader.LocalTargetFile target = fileLoader.localTarget(repositoryId, snapshot.path());
            try {
                Files.writeString(target.localPath(), snapshot.beforeContent(), StandardCharsets.UTF_8);
                restored.add(snapshot.path());
            } catch (Exception ex) {
                throw new IllegalArgumentException("Failed to restore file: " + snapshot.path(), ex);
            }
        }
        sessionRepository.markRolledBack(patchSessionId);
        return new CodeAgentRollbackResponse(patchSessionId, true, List.copyOf(restored), List.copyOf(warnings));
    }

    public CodeAgentTestResponse runAllowedTest(UUID repositoryId, UUID spaceId, UUID userId, UUID patchSessionId, String commandKey) {
        CodeAgentPatchSession session = session(patchSessionId, repositoryId, spaceId, userId);
        List<String> warnings = new ArrayList<>();
        if (!"APPLIED".equals(session.status())) {
            warnings.add("Tests can run only for APPLIED patch sessions.");
            return new CodeAgentTestResponse(patchSessionId, commandKey, false, null, "", List.copyOf(warnings));
        }
        CommandSpec command = command(commandKey, repositoryId);
        if (command == null) {
            warnings.add("Command key is not allowlisted: " + commandKey);
            return new CodeAgentTestResponse(patchSessionId, commandKey, false, null, "", List.copyOf(warnings));
        }
        ProcessResult result = execute(command);
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("commandKey", commandKey);
        stored.put("exitCode", result.exitCode());
        stored.put("summary", result.summary());
        stored.put("createdAt", OffsetDateTime.now().toString());
        sessionRepository.appendTestResult(patchSessionId, stored);
        return new CodeAgentTestResponse(patchSessionId, commandKey, true, result.exitCode(), result.summary(), List.copyOf(warnings));
    }

    private Map<String, String> applyDiff(UUID repositoryId, String diff, List<String> targetFiles) {
        Map<String, List<Hunk>> hunksByPath = parseDiff(diff);
        Set<String> allowed = targetFiles.stream().map(this::normalizePath).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> patched = new LinkedHashMap<>();
        for (Map.Entry<String, List<Hunk>> entry : hunksByPath.entrySet()) {
            String path = normalizePath(entry.getKey());
            if (!allowed.contains(path)) {
                throw new IllegalArgumentException("Patch modifies a file outside targetFiles: " + path);
            }
            CodePatchFileLoader.LocalTargetFile target = fileLoader.localTarget(repositoryId, path);
            patched.put(path, applyHunks(target.content(), entry.getValue(), path));
        }
        return patched;
    }

    private Map<String, List<Hunk>> parseDiff(String diff) {
        List<String> lines = safe(diff).replace("\r\n", "\n").lines().toList();
        Map<String, List<Hunk>> result = new LinkedHashMap<>();
        String currentPath = "";
        Hunk currentHunk = null;
        for (String line : lines) {
            if (line.startsWith("+++ b/")) {
                currentPath = normalizePath(line.substring("+++ b/".length()).trim().split("\\s+", 2)[0]);
                result.putIfAbsent(currentPath, new ArrayList<>());
                currentHunk = null;
                continue;
            }
            if (line.startsWith("@@")) {
                currentHunk = new Hunk(oldStart(line), new ArrayList<>());
                result.computeIfAbsent(currentPath, ignored -> new ArrayList<>()).add(currentHunk);
                continue;
            }
            if (currentHunk != null && (line.startsWith(" ") || line.startsWith("+") || line.startsWith("-") || line.equals("\\"))) {
                currentHunk.lines().add(line);
            }
        }
        result.values().removeIf(List::isEmpty);
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Patch did not contain applicable hunks.");
        }
        return result;
    }

    private String applyHunks(String content, List<Hunk> hunks, String path) {
        List<String> source = new ArrayList<>(List.of(content.replace("\r\n", "\n").split("\n", -1)));
        boolean trailingNewline = !source.isEmpty() && source.get(source.size() - 1).isEmpty();
        if (trailingNewline) {
            source.remove(source.size() - 1);
        }
        List<String> output = new ArrayList<>();
        int sourceIndex = 0;
        for (Hunk hunk : hunks) {
            int hunkStart = Math.max(0, hunk.oldStart() - 1);
            while (sourceIndex < hunkStart && sourceIndex < source.size()) {
                output.add(source.get(sourceIndex++));
            }
            for (String raw : hunk.lines()) {
                if (raw.equals("\\ No newline at end of file")) {
                    continue;
                }
                char marker = raw.isEmpty() ? ' ' : raw.charAt(0);
                String line = raw.length() > 0 ? raw.substring(1) : "";
                if (marker == ' ' || marker == '-') {
                    if (sourceIndex >= source.size() || !source.get(sourceIndex).equals(line)) {
                        throw new IllegalArgumentException("Patch context mismatch in " + path + " near line " + (sourceIndex + 1));
                    }
                    if (marker == ' ') {
                        output.add(line);
                    }
                    sourceIndex++;
                } else if (marker == '+') {
                    output.add(line);
                }
            }
        }
        while (sourceIndex < source.size()) {
            output.add(source.get(sourceIndex++));
        }
        String joined = String.join("\n", output);
        return trailingNewline ? joined + "\n" : joined;
    }

    private int oldStart(String hunkHeader) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("@@ -(\\d+)").matcher(hunkHeader);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Invalid hunk header: " + hunkHeader);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private CodeAgentPatchSession session(UUID patchSessionId, UUID repositoryId, UUID spaceId, UUID userId) {
        CodeAgentPatchSession session = sessionRepository.find(patchSessionId)
                .orElseThrow(() -> new IllegalArgumentException("Patch session was not found."));
        if (!session.repositoryId().equals(repositoryId) || !session.spaceId().equals(spaceId) || !session.userId().equals(userId)) {
            throw new IllegalArgumentException("Patch session does not match the request scope.");
        }
        return session;
    }

    private CommandSpec command(String commandKey, UUID repositoryId) {
        return switch (safe(commandKey)) {
            case "backend-test" -> commandIfExists(repositoryId, "backend/pom.xml", List.of("cmd", "/c", "..\\.tools\\apache-maven-3.9.9\\bin\\mvn.cmd", "test"), "backend");
            case "frontend-build" -> commandIfExists(repositoryId, "frontend/package.json", List.of("cmd", "/c", "npm", "run", "build"), "frontend");
            default -> null;
        };
    }

    private CommandSpec commandIfExists(UUID repositoryId, String markerPath, List<String> command, String workSubdir) {
        CodePatchFileLoader.LocalTargetFile marker;
        try {
            marker = fileLoader.localTarget(repositoryId, markerPath);
        } catch (Exception ex) {
            return null;
        }
        Path workDir = marker.localPath().getParent();
        if (!workSubdir.isBlank() && !workDir.getFileName().toString().equals(workSubdir)) {
            workDir = workDir.resolve(workSubdir).normalize();
        }
        return new CommandSpec(command, workDir);
    }

    private ProcessResult execute(CommandSpec command) {
        StringBuilder output = new StringBuilder();
        int exitCode = -1;
        try {
            Process process = new ProcessBuilder(command.command())
                    .directory(command.workDir().toFile())
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() < 6000) {
                        output.append(line).append('\n');
                    }
                }
            }
            boolean finished = process.waitFor(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ProcessResult(-1, "Test command timed out after " + TEST_TIMEOUT_SECONDS + " seconds.");
            }
            exitCode = process.exitValue();
        } catch (Exception ex) {
            return new ProcessResult(-1, "Test command failed to start: " + ex.getMessage());
        }
        String summary = output.toString().trim();
        if (summary.length() > 2000) {
            summary = summary.substring(Math.max(0, summary.length() - 2000));
        }
        return new ProcessResult(exitCode, summary);
    }

    private String normalizePath(String path) {
        return safe(path).trim().replace('\\', '/').replaceAll("^/+", "");
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(safe(content).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record Hunk(int oldStart, List<String> lines) {
    }

    private record CommandSpec(List<String> command, Path workDir) {
    }

    private record ProcessResult(int exitCode, String summary) {
    }
}
