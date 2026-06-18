package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.CodeAskResponse;
import com.learnbot.dto.CodeEvidence;
import com.learnbot.dto.CodeChunkSummary;
import com.learnbot.dto.CodeFileSummary;
import com.learnbot.repository.CodeRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

@Service
public class CommitInsightService {
    private static final Pattern SHA_PATTERN = Pattern.compile("(?<![0-9a-fA-F])([0-9a-fA-F]{7,40})(?![0-9a-fA-F])");
    private static final int MAX_FILES = 30;
    private static final int MAX_EVIDENCE = 12;
    private static final int MAX_PATCH_CHARS_PER_FILE = 1400;
    private static final int MAX_CONTEXT_CHARS = 12000;
    private static final DateTimeFormatter COMMIT_TIME_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            .withZone(ZoneId.systemDefault());

    private static final List<String> EXPLICIT_COMMIT_TERMS = List.of(
            "commit", "committed", "커밋", "커밋된", "sha", "hash", "해시", "head", "revision", "rev"
    );
    private static final List<String> RECENT_TERMS = List.of(
            "latest", "last", "recent", "newest", "current", "head",
            "최신", "최근", "마지막", "이번", "방금", "현재", "헤드"
    );
    private static final List<String> CHANGE_TERMS = List.of(
            "change", "changes", "changed", "diff", "added", "add", "modified", "updated", "deleted", "implemented",
            "변경", "변경점", "변경사항", "수정", "개선", "추가", "삭제", "반영", "바뀐", "바뀌", "차이", "작업", "내용"
    );

    private final CodeRepository repository;
    private final OllamaClient ollamaClient;
    private final RagPipelineService pipelineService;

    public CommitInsightService(CodeRepository repository, OllamaClient ollamaClient) {
        this(repository, ollamaClient, new RagPipelineService(ollamaClient, new LearnBotProperties()));
    }

    @Autowired
    public CommitInsightService(CodeRepository repository, OllamaClient ollamaClient, RagPipelineService pipelineService) {
        this.repository = repository;
        this.ollamaClient = ollamaClient;
        this.pipelineService = pipelineService;
    }

    public boolean isCommitQuestion(String question) {
        return detect(question).commitQuestion();
    }

    public CodeAskResponse answer(UUID repositoryId, String question) {
        CommitIntent intent = detect(question);
        if (repositoryId == null) {
            return informational("커밋 질문은 먼저 코드 저장소를 선택해야 답변할 수 있습니다.",
                    List.of("repositoryId가 없어 커밋을 확인하지 않았습니다."));
        }
        Optional<CodeRepositoryRecord> maybeRepository = repository.findRepository(repositoryId);
        if (maybeRepository.isEmpty()) {
            return informational("선택한 코드 저장소를 찾을 수 없습니다.",
                    List.of("repositoryId=" + repositoryId + " 저장소가 없거나 삭제되었습니다."));
        }

        CodeRepositoryRecord record = maybeRepository.get();
        String target = intent.sha().orElseGet(record::lastIndexedCommit);
        if (isBlank(target)) {
            return informational("이 저장소는 아직 인덱싱된 커밋 정보가 없습니다. 먼저 저장소 인덱싱을 완료해 주세요.",
                    List.of("last_indexed_commit 값이 비어 있습니다."));
        }
        if (isBlank(record.localPath())) {
            return informational("이 저장소의 로컬 작업 경로가 없어 커밋 diff를 확인할 수 없습니다.",
                    List.of("code_repositories.local_path 값이 비어 있습니다."));
        }

        if (isPseudoLocalPath(record.localPath())) {
            return importedSnapshotFallback(record, target, "localPath=" + record.localPath() + " is not a local filesystem path.");
        }

        Path localPath = Path.of(record.localPath());
        if (!Files.isDirectory(localPath) || !Files.exists(localPath.resolve(".git"))) {
            return importedSnapshotFallback(record, target, "localPath=" + localPath + " 경로에 .git 디렉터리가 없습니다.");
        }

        try (Git git = Git.open(localPath.toFile())) {
            org.eclipse.jgit.lib.Repository gitRepository = git.getRepository();
            ObjectId objectId = resolveCommit(gitRepository, target);
            if (objectId == null) {
                return informational("요청한 커밋을 로컬 저장소에서 찾을 수 없습니다: `" + target + "`",
                        List.of("로컬 .git에서 대상 커밋을 resolve하지 못했습니다."));
            }
            CommitSnapshot snapshot = snapshot(gitRepository, record, objectId, intent.latest());
            List<CodeEvidence> evidence = buildEvidence(record, snapshot);
            boolean llmUnavailable = false;
            boolean answerRewritten = false;
            String answer;
            String answerDoneReason = null;
            try {
                OllamaClient.ChatResult chatResult = ollamaClient.chatResult(systemPrompt(), userPrompt(question, snapshot));
                answer = chatResult.content();
                answerDoneReason = chatResult.doneReason();
            } catch (RuntimeException ex) {
                answer = fallbackAnswer(snapshot);
                llmUnavailable = true;
            }
            if (qualityFailureReason(answer, evidence.size(), answerDoneReason) != null) {
                answer = fallbackAnswer(snapshot);
                answerRewritten = true;
            }
            return new CodeAskResponse(
                    "commit",
                    answer,
                    evidence,
                    confidence(snapshot, llmUnavailable, answerRewritten),
                    diagnostics(snapshot, llmUnavailable, answerRewritten)
            );
        } catch (Exception ex) {
            return informational("커밋 diff를 읽는 중 오류가 발생했습니다: " + safeMessage(ex),
                    List.of("Git 분석 실패: " + ex.getClass().getSimpleName()));
        }
    }

    private CommitIntent detect(String question) {
        String normalized = normalize(question);
        Optional<String> sha = shaFrom(question);
        if (sha.isPresent()) {
            return new CommitIntent(true, sha, false);
        }
        boolean explicitCommit = containsAny(normalized, EXPLICIT_COMMIT_TERMS);
        boolean recentChange = containsAny(normalized, RECENT_TERMS) && containsAny(normalized, CHANGE_TERMS);
        boolean headQuestion = normalized.contains("head") || normalized.contains("헤드");
        return new CommitIntent(explicitCommit || recentChange || headQuestion, Optional.empty(), recentChange || headQuestion);
    }

    private Optional<String> shaFrom(String question) {
        if (question == null) {
            return Optional.empty();
        }
        Matcher matcher = SHA_PATTERN.matcher(question);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private CodeAskResponse importedSnapshotFallback(CodeRepositoryRecord record, String targetCommit, String reason) {
        List<CodeFileSummary> files = repository.listActiveFiles(record.id(), null, MAX_EVIDENCE);
        List<CodeEvidence> evidence = buildImportedSnapshotEvidence(record, targetCommit, files);
        String answer = importedSnapshotAnswer(record, targetCommit, files, evidence);
        List<String> diagnostics = new ArrayList<>();
        diagnostics.add("이 저장소는 import된 코드 스냅샷이거나 로컬 Git checkout이 없어 Git diff를 계산하지 못했습니다.");
        diagnostics.add(reason);
        diagnostics.add("last_indexed_commit `" + targetCommit + "` 기준으로 가져온 코드 파일/청크를 근거로 fallback 답변을 생성했습니다.");
        diagnostics.add("정확한 커밋 변경점이 필요하면 원본 Git 저장소를 이 서버에서 다시 등록/인덱싱해야 합니다.");
        return new CodeAskResponse(
                "commit",
                answer,
                evidence,
                "낮음",
                diagnostics
        );
    }

    private List<CodeEvidence> buildImportedSnapshotEvidence(CodeRepositoryRecord record, String targetCommit, List<CodeFileSummary> files) {
        List<CodeEvidence> evidence = new ArrayList<>();
        for (int index = 0; index < Math.min(files.size(), MAX_EVIDENCE); index++) {
            CodeFileSummary file = files.get(index);
            List<CodeChunkSummary> chunks = repository.listActiveChunksForFile(file.id());
            CodeChunkSummary primary = chunks.isEmpty() ? null : chunks.get(0);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("kind", "imported_code_snapshot");
            metadata.put("commitHash", targetCommit);
            metadata.put("fileChunkCount", file.chunkCount());
            metadata.put("localGitAvailable", false);
            evidence.add(new CodeEvidence(
                    index + 1,
                    primary == null ? null : primary.id(),
                    record.id(),
                    file.id(),
                    record.name(),
                    file.filePath(),
                    primary == null ? "file" : primary.chunkType(),
                    primary == null ? null : primary.symbolName(),
                    primary == null ? null : primary.className(),
                    primary == null ? null : primary.methodName(),
                    primary == null ? null : primary.controlName(),
                    primary == null ? null : primary.eventName(),
                    primary == null ? 0 : primary.lineStart(),
                    primary == null ? 0 : primary.lineEnd(),
                    importedSnapshotPreview(file, chunks),
                    Math.max(0.1, 0.65 - (index * 0.03)),
                    metadata
            ));
        }
        return evidence;
    }

    private String importedSnapshotAnswer(
            CodeRepositoryRecord record,
            String targetCommit,
            List<CodeFileSummary> files,
            List<CodeEvidence> evidence
    ) {
        StringBuilder answer = new StringBuilder();
        answer.append("이 저장소는 import된 코드 스냅샷이라 로컬 `.git` 디렉터리가 없어 최신 커밋의 diff를 직접 계산할 수 없습니다.\n\n");
        answer.append("대신 import archive에 포함된 `last_indexed_commit` `").append(targetCommit)
                .append("` 시점의 인덱싱된 코드 상태를 기준으로 확인했습니다.\n\n");
        if (evidence.isEmpty()) {
            answer.append("확인 가능한 코드 파일/청크 근거가 없어 변경 내용을 추정할 수 없습니다.\n\n");
        } else {
            answer.append("확인 가능한 코드 스냅샷 근거:\n");
            for (int index = 0; index < evidence.size(); index++) {
                CodeEvidence item = evidence.get(index);
                answer.append("- [").append(index + 1).append("] `")
                        .append(item.filePath()).append("`: ")
                        .append(item.preview()).append("\n");
            }
            answer.append("\n");
        }
        answer.append("해석 한계:\n");
        answer.append("- 위 내용은 커밋 diff가 아니라 import된 코드 스냅샷입니다.\n");
        answer.append("- 어떤 파일이 추가/수정/삭제되었는지, 커밋 메시지와 parent 기준 변경량은 이 서버에서 확정할 수 없습니다.\n");
        answer.append("- 정확한 커밋 변경 분석이 필요하면 원본 Git 저장소를 이 서버에서 다시 등록하고 인덱싱해야 합니다.\n\n");
        answer.append("저장소 정보:\n");
        answer.append("- 저장소: ").append(record.name()).append("\n");
        answer.append("- 브랜치: ").append(record.branch()).append("\n");
        answer.append("- 인덱싱 커밋: ").append(targetCommit).append("\n");
        answer.append("- 인덱싱 파일: ").append(files.size()).append("개 이상");
        return answer.toString();
    }

    private String importedSnapshotPreview(CodeFileSummary file, List<CodeChunkSummary> chunks) {
        if (chunks.isEmpty()) {
            return file.language() + ", 청크 " + file.chunkCount() + "개";
        }
        List<String> parts = chunks.stream()
                .limit(2)
                .map(chunk -> {
                    String symbol = firstNonBlank(chunk.methodName(), chunk.className(), chunk.symbolName(), chunk.controlName(), chunk.eventName(), chunk.chunkType());
                    String line = chunk.lineStart() > 0 ? " lines " + chunk.lineStart() + "-" + chunk.lineEnd() : "";
                    return symbol + line + " - " + trimPreview(chunk.preview());
                })
                .toList();
        return file.language() + ", 청크 " + file.chunkCount() + "개. " + String.join(" / ", parts);
    }

    private ObjectId resolveCommit(org.eclipse.jgit.lib.Repository gitRepository, String target) throws IOException {
        ObjectId resolved = gitRepository.resolve(target + "^{commit}");
        return resolved != null ? resolved : gitRepository.resolve(target);
    }

    private CommitSnapshot snapshot(
            org.eclipse.jgit.lib.Repository gitRepository,
            CodeRepositoryRecord record,
            ObjectId objectId,
            boolean latest
    ) throws IOException {
        try (RevWalk walk = new RevWalk(gitRepository)) {
            RevCommit commit = walk.parseCommit(objectId);
            RevCommit parent = commit.getParentCount() > 0 ? walk.parseCommit(commit.getParent(0).getId()) : null;
            AbstractTreeIterator oldTree = parent == null ? new EmptyTreeIterator() : treeParser(gitRepository, parent);
            AbstractTreeIterator newTree = treeParser(gitRepository, commit);
            List<CommitChange> changes = new ArrayList<>();
            int totalInsertions = 0;
            int totalDeletions = 0;
            int totalChangedFiles = 0;
            boolean truncated = false;
            try (DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                formatter.setRepository(gitRepository);
                formatter.setDetectRenames(true);
                List<DiffEntry> diffs = formatter.scan(oldTree, newTree);
                totalChangedFiles = diffs.size();
                truncated = diffs.size() > MAX_FILES;
                for (DiffEntry diff : diffs.stream().limit(MAX_FILES).toList()) {
                    FileHeader header = formatter.toFileHeader(diff);
                    List<Edit> edits = header.toEditList();
                    int insertions = insertions(edits);
                    int deletions = deletions(edits);
                    totalInsertions += insertions;
                    totalDeletions += deletions;
                    changes.add(new CommitChange(
                            pathOf(diff),
                            oldPathOf(diff),
                            diff.getChangeType().name(),
                            insertions,
                            deletions,
                            lineStart(diff, edits),
                            lineEnd(diff, edits),
                            patchFor(gitRepository, diff)
                    ));
                }
                if (diffs.size() > MAX_FILES) {
                    changes.add(new CommitChange(
                            "(diff truncated)",
                            null,
                            "TRUNCATED",
                            0,
                            0,
                            0,
                            0,
                            "Changed files exceeded " + MAX_FILES + "; remaining " + (diffs.size() - MAX_FILES) + " files were omitted."
                    ));
                }
            }
            return new CommitSnapshot(
                    record.id(),
                    record.name(),
                    latest,
                    commit.getName(),
                    parent == null ? null : parent.getName(),
                    commit.getParentCount(),
                    commit.getShortMessage(),
                    commit.getFullMessage(),
                    commit.getAuthorIdent().getName(),
                    Instant.ofEpochSecond(commit.getCommitTime()),
                    changes,
                    totalChangedFiles,
                    truncated,
                    totalInsertions,
                    totalDeletions
            );
        }
    }

    private AbstractTreeIterator treeParser(org.eclipse.jgit.lib.Repository gitRepository, RevCommit commit) throws IOException {
        try (ObjectReader reader = gitRepository.newObjectReader()) {
            CanonicalTreeParser parser = new CanonicalTreeParser();
            parser.reset(reader, commit.getTree());
            return parser;
        }
    }

    private List<CodeEvidence> buildEvidence(CodeRepositoryRecord record, CommitSnapshot snapshot) {
        List<String> paths = snapshot.changes().stream()
                .map(CommitChange::path)
                .filter(path -> !isBlank(path) && !path.startsWith("("))
                .distinct()
                .toList();
        Map<String, UUID> activeFileIds = repository.findActiveFileIdsByPath(record.id(), paths);
        return IntStream.range(0, Math.min(snapshot.changes().size(), MAX_EVIDENCE))
                .mapToObj(index -> evidence(record, snapshot, snapshot.changes().get(index), index + 1, activeFileIds))
                .toList();
    }

    private CodeEvidence evidence(
            CodeRepositoryRecord record,
            CommitSnapshot snapshot,
            CommitChange change,
            int citationNumber,
            Map<String, UUID> activeFileIds
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", "commit_diff");
        metadata.put("commitHash", snapshot.commitHash());
        if (snapshot.parentHash() != null) {
            metadata.put("parentHash", snapshot.parentHash());
        }
        metadata.put("changeType", change.changeType());
        metadata.put("insertions", change.insertions());
        metadata.put("deletions", change.deletions());
        if (change.oldPath() != null) {
            metadata.put("oldPath", change.oldPath());
        }
        return new CodeEvidence(
                citationNumber,
                null,
                record.id(),
                activeFileIds.get(change.path()),
                record.name(),
                change.path(),
                "commit_diff",
                null,
                null,
                null,
                null,
                null,
                change.lineStart(),
                change.lineEnd(),
                preview(change),
                Math.max(0.1, 1.0 - (citationNumber * 0.03)),
                metadata
        );
    }

    private String systemPrompt() {
        return """
                You are LearnBot Commit Insight.
                Answer in Korean using only the provided Git commit metadata and diff excerpts.
                Separate confirmed diff facts from reasonable inferences.
                Cite changed-file evidence with bracket numbers like [1].
                Do not claim runtime behavior, intent, or test results unless directly supported by the diff.
                """;
    }

    private String userPrompt(String question, CommitSnapshot snapshot) {
        StringBuilder builder = new StringBuilder();
        builder.append("Question:\n").append(question).append("\n\n");
        builder.append("Commit metadata:\n")
                .append("- repository: ").append(snapshot.repositoryName()).append("\n")
                .append("- commit: ").append(snapshot.commitHash()).append("\n")
                .append("- parent: ").append(snapshot.parentHash() == null ? "(root commit)" : snapshot.parentHash()).append("\n")
                .append("- parent count: ").append(snapshot.parentCount()).append("\n")
                .append("- author: ").append(snapshot.author()).append("\n")
                .append("- authored at: ").append(COMMIT_TIME_FORMATTER.format(snapshot.commitTime())).append("\n")
                .append("- message: ").append(snapshot.shortMessage()).append("\n")
                .append("- changed files: ").append(snapshot.totalChangedFiles()).append(snapshot.truncated() ? " (diff excerpts truncated)" : "").append("\n")
                .append("- total stats: +").append(snapshot.totalInsertions()).append(" -").append(snapshot.totalDeletions()).append("\n\n");
        builder.append("Diff excerpts:\n");
        int remaining = MAX_CONTEXT_CHARS;
        for (int index = 0; index < Math.min(snapshot.changes().size(), MAX_EVIDENCE); index++) {
            CommitChange change = snapshot.changes().get(index);
            String item = "[" + (index + 1) + "] " + change.path()
                    + " " + change.changeType()
                    + " +" + change.insertions()
                    + " -" + change.deletions()
                    + " lines " + change.lineStart() + "-" + change.lineEnd()
                    + "\n" + change.patch() + "\n\n";
            if (item.length() > remaining) {
                break;
            }
            builder.append(item);
            remaining -= item.length();
        }
        return builder.toString();
    }

    private String fallbackAnswer(CommitSnapshot snapshot) {
        StringBuilder answer = new StringBuilder();
        List<WorkTheme> themes = workThemes(snapshot);
        answer.append("커밋 `").append(shortHash(snapshot.commitHash())).append("`은 `")
                .append(snapshot.shortMessage())
                .append("` 작업으로 보입니다.\n\n");
        answer.append("핵심 작업:\n");
        if (themes.isEmpty()) {
            answer.append("- 변경 파일은 확인됐지만, 파일명과 diff 조각만으로 작업 주제를 안정적으로 묶기 어렵습니다.\n");
        } else {
            for (WorkTheme theme : themes) {
                answer.append("- ").append(theme.title()).append(" ")
                        .append(theme.summary()).append(" ")
                        .append(citations(theme.citations()))
                        .append("\n");
            }
        }

        answer.append("\n주요 변경 근거:\n");
        if (snapshot.changes().isEmpty()) {
            answer.append("- diff에서 변경된 파일을 찾지 못했습니다.\n");
        } else {
            for (int index = 0; index < Math.min(snapshot.changes().size(), MAX_EVIDENCE); index++) {
                CommitChange change = snapshot.changes().get(index);
                answer.append("- [").append(index + 1).append("] `").append(change.path()).append("`: ")
                        .append(change.changeType()).append(", +").append(change.insertions())
                        .append("/-").append(change.deletions())
                        .append(". ").append(meaningfulPatchLine(change.patch())).append("\n");
            }
        }
        answer.append("\n해석 한계:\n");
        answer.append("- 위 내용은 Git diff와 파일명 기준 요약입니다. 실제 런타임 동작, 배포 성공 여부, 테스트 통과 여부는 이 diff만으로 확정하지 않습니다.\n");
        answer.append("\n커밋 정보:\n");
        answer.append("- 메시지: ").append(snapshot.shortMessage()).append("\n");
        answer.append("- 작성자: ").append(snapshot.author()).append("\n");
        answer.append("- 통계: 파일 ").append(snapshot.totalChangedFiles()).append("개, +")
                .append(snapshot.totalInsertions()).append("/-").append(snapshot.totalDeletions()).append("\n");
        if (snapshot.truncated()) {
            answer.append("- 주의: 변경 파일이 많아 상위 ").append(MAX_FILES).append("개 파일의 diff만 근거로 사용했습니다.\n");
        }
        return answer.toString();
    }

    private List<WorkTheme> workThemes(CommitSnapshot snapshot) {
        Map<String, WorkThemeBuilder> builders = new LinkedHashMap<>();
        int limit = Math.min(snapshot.changes().size(), MAX_EVIDENCE);
        for (int index = 0; index < limit; index++) {
            CommitChange change = snapshot.changes().get(index);
            String key = classify(change);
            WorkThemeBuilder builder = builders.computeIfAbsent(key, keyValue -> new WorkThemeBuilder(keyValue));
            builder.add(index + 1, change);
        }
        return builders.values().stream()
                .map(WorkThemeBuilder::build)
                .toList();
    }

    private String classify(CommitChange change) {
        String path = normalizePath(change.path());
        String patch = normalizePath(change.patch());
        String joined = path + " " + patch;
        if (joined.contains("documentpreview") || joined.contains("preview")) {
            return "document-preview";
        }
        if (joined.contains("filebatch") || joined.contains("batch")) {
            return "batch-ingest";
        }
        if (path.contains("chunker") || path.contains("codeindexingservice") || path.contains("ingestionservice")
                || path.contains("coderepository") || joined.contains("indexing_job_failures")
                || joined.contains("transaction") || joined.contains("status")) {
            return "indexing-reliability";
        }
        if (path.contains("docker") || path.contains("compose") || path.contains("nginx") || path.contains("scripts/")
                || joined.contains("ollama") || joined.contains("gpu")) {
            return "deployment";
        }
        if (path.contains("admin") || path.contains("authservice") || path.contains("securityrepository")
                || joined.contains("role") || joined.contains("permission")) {
            return "admin";
        }
        if (path.contains("frontend/") || path.endsWith("app.jsx") || path.endsWith(".css")) {
            return "frontend";
        }
        if (path.contains("/test/") || path.endsWith("test.java") || path.contains(".spec.")) {
            return "tests";
        }
        if (path.endsWith("readme.md") || path.contains("/docs/")) {
            return "docs";
        }
        return "other";
    }

    private String normalizePath(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('\\', '/');
    }

    private String themeTitle(String key) {
        return switch (key) {
            case "document-preview" -> "문서 미리보기/원문 확인 기능";
            case "batch-ingest" -> "파일 일괄 업로드/인입 결과 처리";
            case "indexing-reliability" -> "인덱싱 안정성 및 실패 처리";
            case "deployment" -> "Docker/Ollama/GPU 실행 설정";
            case "admin" -> "관리자/권한 관리";
            case "frontend" -> "프론트 화면 표시";
            case "tests" -> "회귀 테스트";
            case "docs" -> "운영 문서";
            default -> "기타 코드 변경";
        };
    }

    private String themeSummary(String key, int files, int insertions, int deletions) {
        String stats = "파일 " + files + "개, +" + insertions + "/-" + deletions + ".";
        return switch (key) {
            case "document-preview" -> "문서 내용을 화면에서 미리 확인하기 위한 응답 DTO와 관련 처리를 추가/확장했습니다. " + stats;
            case "batch-ingest" -> "여러 파일 업로드 결과를 항목별로 돌려주기 위한 응답 구조를 추가했습니다. " + stats;
            case "indexing-reliability" -> "인덱싱 상태 갱신, chunk 처리, 실패 상황 기록 쪽의 안정성을 보강했습니다. " + stats;
            case "deployment" -> "로컬/도커 실행 방식과 Ollama 또는 GPU 관련 실행 설정을 조정했습니다. " + stats;
            case "admin" -> "관리자 화면과 사용자/권한 관리 흐름을 확장했습니다. " + stats;
            case "frontend" -> "사용자가 변경 결과나 상태를 더 잘 볼 수 있도록 화면 표시를 조정했습니다. " + stats;
            case "tests" -> "이번 변경의 회귀를 막기 위한 테스트를 추가/수정했습니다. " + stats;
            case "docs" -> "실행 또는 운영 방법 설명을 갱신했습니다. " + stats;
            default -> "위 범주로 자동 분류되지 않는 코드 변경입니다. " + stats;
        };
    }

    private String citations(List<Integer> citations) {
        return citations.stream()
                .limit(4)
                .map(number -> "[" + number + "]")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private String confidence(CommitSnapshot snapshot, boolean llmUnavailable, boolean answerRewritten) {
        if (snapshot.changes().isEmpty()) {
            return "낮음";
        }
        if (snapshot.truncated() || snapshot.parentCount() > 1 || llmUnavailable || answerRewritten) {
            return "보통";
        }
        return "높음";
    }

    private List<String> diagnostics(CommitSnapshot snapshot, boolean llmUnavailable, boolean answerRewritten) {
        List<String> notes = new ArrayList<>();
        notes.add((snapshot.latest() ? "최신 인덱싱 커밋" : "요청 커밋") + " `" + snapshot.commitHash() + "`을 분석했습니다.");
        notes.add("최신 기준은 원격 HEAD가 아니라 마지막으로 성공 인덱싱된 last_indexed_commit입니다.");
        if (snapshot.parentHash() == null) {
            notes.add("루트 커밋이라 빈 트리 기준으로 diff를 계산했습니다.");
        } else {
            notes.add("비교 기준 parent는 `" + snapshot.parentHash() + "`입니다.");
        }
        if (snapshot.parentCount() > 1) {
            notes.add("merge commit이라 첫 번째 parent 기준으로 diff를 계산했습니다. 다른 parent 기준 변경은 별도 확인이 필요해 신뢰도를 보통으로 낮췄습니다.");
        }
        if (snapshot.truncated()) {
            notes.add("변경 파일 " + snapshot.totalChangedFiles() + "개 중 " + MAX_FILES + "개만 LLM 근거로 사용해 신뢰도를 보통으로 낮췄습니다.");
        }
        if (llmUnavailable) {
            notes.add("LLM 호출이 실패해 Git diff 기반 fallback 응답을 반환했고, 해석 범위가 좁아 신뢰도를 보통으로 낮췄습니다.");
        }
        if (answerRewritten) {
            notes.add("LLM 응답에 근거 인용이 부족하거나 답변이 완성되지 않아 Git diff 기반 fallback 응답으로 대체했고, 신뢰도를 보통으로 낮췄습니다.");
        }
        return notes;
    }

    private CodeAskResponse informational(String answer, List<String> diagnostics) {
        return new CodeAskResponse("commit", answer, List.of(), "낮음", diagnostics);
    }

    private String patchFor(org.eclipse.jgit.lib.Repository gitRepository, DiffEntry diff) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DiffFormatter formatter = new DiffFormatter(output)) {
            formatter.setRepository(gitRepository);
            formatter.setDetectRenames(true);
            formatter.format(diff);
        } catch (IOException ex) {
            return "[diff omitted: " + safeMessage(ex) + "]";
        }
        return trimPatch(new String(output.toByteArray(), StandardCharsets.UTF_8));
    }

    private String trimPatch(String patch) {
        if (patch == null || patch.isBlank()) {
            return "";
        }
        String clean = patch.replace("\r\n", "\n").trim();
        return clean.length() <= MAX_PATCH_CHARS_PER_FILE
                ? clean
                : clean.substring(0, MAX_PATCH_CHARS_PER_FILE).stripTrailing() + "\n...";
    }

    private int insertions(List<Edit> edits) {
        return edits.stream().mapToInt(edit -> edit.getEndB() - edit.getBeginB()).sum();
    }

    private int deletions(List<Edit> edits) {
        return edits.stream().mapToInt(edit -> edit.getEndA() - edit.getBeginA()).sum();
    }

    private int lineStart(DiffEntry diff, List<Edit> edits) {
        if (edits.isEmpty()) {
            return 0;
        }
        boolean deleted = diff.getChangeType() == DiffEntry.ChangeType.DELETE;
        return edits.stream()
                .mapToInt(edit -> (deleted ? edit.getBeginA() : edit.getBeginB()) + 1)
                .min()
                .orElse(0);
    }

    private int lineEnd(DiffEntry diff, List<Edit> edits) {
        if (edits.isEmpty()) {
            return 0;
        }
        boolean deleted = diff.getChangeType() == DiffEntry.ChangeType.DELETE;
        int start = lineStart(diff, edits);
        int end = edits.stream()
                .mapToInt(edit -> deleted ? edit.getEndA() : edit.getEndB())
                .max()
                .orElse(start);
        return Math.max(start, end);
    }

    private String pathOf(DiffEntry diff) {
        return diff.getChangeType() == DiffEntry.ChangeType.DELETE ? diff.getOldPath() : diff.getNewPath();
    }

    private String oldPathOf(DiffEntry diff) {
        return diff.getChangeType() == DiffEntry.ChangeType.ADD ? null : diff.getOldPath();
    }

    private String preview(CommitChange change) {
        return change.changeType() + " +" + change.insertions() + "/-" + change.deletions()
                + "\n" + trimPreview(change.patch());
    }

    private String trimPreview(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 420 ? compact : compact.substring(0, 420) + "...";
    }

    private String firstPatchLine(String patch) {
        if (patch == null || patch.isBlank()) {
            return "patch excerpt가 없습니다.";
        }
        return patch.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith("diff --git") && !line.startsWith("index ") && !line.startsWith("---") && !line.startsWith("+++"))
                .min(Comparator.comparingInt(line -> line.startsWith("@@") ? 1 : 0))
                .map(line -> line.length() <= 160 ? line : line.substring(0, 160) + "...")
                .orElse("patch excerpt가 없습니다.");
    }

    private String meaningfulPatchLine(String patch) {
        if (patch == null || patch.isBlank()) {
            return "의미 있는 patch excerpt가 없습니다.";
        }
        return patch.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith("diff --git"))
                .filter(line -> !line.startsWith("index "))
                .filter(line -> !line.startsWith("---") && !line.startsWith("+++"))
                .filter(line -> !line.startsWith("@@"))
                .filter(line -> !line.startsWith("new file mode") && !line.startsWith("deleted file mode"))
                .filter(line -> !line.matches("[+-]?\\s*[{});,]*"))
                .map(line -> line.replaceFirst("^[+-]\\s*", ""))
                .findFirst()
                .map(line -> line.length() <= 180 ? line : line.substring(0, 180) + "...")
                .orElse(firstPatchLine(patch));
    }

    private String qualityFailureReason(String answer, int evidenceCount, String doneReason) {
        if (answer == null || answer.isBlank()) {
            return "blank";
        }
        if (answer.trim().length() < 30) {
            return "too short";
        }
        if (!answer.matches("(?s).*\\[\\d+].*")) {
            return "missing citation";
        }
        RagPipelineService.AnswerAssessment assessment = pipelineService.assessAnswer(answer, evidenceCount, true, doneReason);
        return assessment.acceptable() ? null : assessment.reason();
    }

    private boolean containsAny(String normalizedQuestion, List<String> terms) {
        return terms.stream().anyMatch(term -> normalizedQuestion.contains(term.toLowerCase(Locale.ROOT)));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isPseudoLocalPath(String value) {
        return value != null && value.contains("://");
    }

    private String shortHash(String value) {
        return value == null || value.length() <= 12 ? value : value.substring(0, 12);
    }

    private String safeMessage(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private record CommitIntent(boolean commitQuestion, Optional<String> sha, boolean latest) {
    }

    private record CommitSnapshot(
            UUID repositoryId,
            String repositoryName,
            boolean latest,
            String commitHash,
            String parentHash,
            int parentCount,
            String shortMessage,
            String fullMessage,
            String author,
            Instant commitTime,
            List<CommitChange> changes,
            int totalChangedFiles,
            boolean truncated,
            int totalInsertions,
            int totalDeletions
    ) {
    }

    private record CommitChange(
            String path,
            String oldPath,
            String changeType,
            int insertions,
            int deletions,
            int lineStart,
            int lineEnd,
            String patch
    ) {
    }

    private record WorkTheme(String title, String summary, List<Integer> citations) {
    }

    private final class WorkThemeBuilder {
        private final String key;
        private final List<Integer> citations = new ArrayList<>();
        private int files;
        private int insertions;
        private int deletions;

        private WorkThemeBuilder(String key) {
            this.key = key;
        }

        private void add(int citation, CommitChange change) {
            citations.add(citation);
            files++;
            insertions += change.insertions();
            deletions += change.deletions();
        }

        private WorkTheme build() {
            return new WorkTheme(themeTitle(key), themeSummary(key, files, insertions, deletions), citations);
        }
    }
}
