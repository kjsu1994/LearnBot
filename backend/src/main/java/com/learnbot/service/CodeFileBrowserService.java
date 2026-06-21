package com.learnbot.service;

import com.learnbot.dto.CodeFileDetail;
import com.learnbot.dto.CodeFileSummary;
import com.learnbot.repository.CodeRepository;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Service
public class CodeFileBrowserService {
    private final CodeRepository repository;
    private final CodeContentReader contentReader;

    public CodeFileBrowserService(CodeRepository repository, CodeContentReader contentReader) {
        this.repository = repository;
        this.contentReader = contentReader;
    }

    public List<CodeFileSummary> listFiles(UUID repositoryId, String query, Integer limit) {
        int safeLimit = limit == null ? 200 : Math.max(1, Math.min(limit, 200));
        return repository.listActiveFiles(repositoryId, query, safeLimit);
    }

    public CodeFileDetail getFile(UUID repositoryId, UUID fileId) {
        CodeFileRecord file = repository.findActiveFile(repositoryId, fileId)
                .orElseThrow(() -> new IllegalArgumentException("코드 파일을 찾을 수 없습니다."));
        CodeRepositoryRecord repo = repository.findRepository(file.repositoryId())
                .orElseThrow(() -> new IllegalArgumentException("코드 저장소를 찾을 수 없습니다."));

        Path root = Path.of(repo.localPath()).normalize();
        Path target = root.resolve(file.filePath()).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("잘못된 파일 경로입니다.");
        }
        String content;
        try {
            content = contentReader.read(target);
        } catch (RuntimeException ex) {
            content = repository.activeFileContentFromChunks(file.id());
            if (content == null || content.isBlank()) {
                throw ex;
            }
        }
        return new CodeFileDetail(
                file.id(),
                file.repositoryId(),
                repo.name(),
                file.filePath(),
                file.language(),
                content,
                repository.listActiveChunksForFile(file.id())
        );
    }
}
