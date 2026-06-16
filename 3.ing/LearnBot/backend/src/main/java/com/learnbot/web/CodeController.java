package com.learnbot.web;

import com.learnbot.dto.CodeAskRequest;
import com.learnbot.dto.CodeAskResponse;
import com.learnbot.dto.CodeRepositoryCreatedResponse;
import com.learnbot.dto.CodeRepositoryCreateRequest;
import com.learnbot.dto.CodeRepositoryIndexRequest;
import com.learnbot.dto.CodeRepositorySummary;
import com.learnbot.dto.IndexingJobSummary;
import com.learnbot.service.CodeIndexingService;
import com.learnbot.service.CodeRagService;
import com.learnbot.service.CodeRepositoryRecord;
import com.learnbot.service.GitAccessToken;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/code")
public class CodeController {
    private final CodeIndexingService indexingService;
    private final CodeRagService ragService;

    public CodeController(CodeIndexingService indexingService, CodeRagService ragService) {
        this.indexingService = indexingService;
        this.ragService = ragService;
    }

    @PostMapping("/repositories")
    CodeRepositoryCreatedResponse createRepository(@Valid @RequestBody CodeRepositoryCreateRequest request) {
        CodeRepositoryRecord repository = indexingService.createRepository(
                request.gitUrl(),
                request.name(),
                request.branch(),
                request.authType()
        );
        return new CodeRepositoryCreatedResponse(
                repository.id(),
                repository.name(),
                repository.gitUrl(),
                repository.branch(),
                repository.authType(),
                repository.status(),
                repository.lastIndexedCommit()
        );
    }

    @GetMapping("/repositories")
    List<CodeRepositorySummary> listRepositories() {
        return indexingService.listRepositories();
    }

    @PostMapping("/repositories/{repositoryId}/index")
    IndexingJobSummary indexRepository(
            @PathVariable UUID repositoryId,
            @RequestBody(required = false) CodeRepositoryIndexRequest request
    ) {
        GitAccessToken accessToken = request == null
                ? new GitAccessToken(null, null)
                : new GitAccessToken(request.username(), request.token());
        return indexingService.index(repositoryId, accessToken);
    }

    @GetMapping("/repositories/{repositoryId}/jobs")
    List<IndexingJobSummary> listJobs(@PathVariable UUID repositoryId) {
        return indexingService.listJobs(repositoryId);
    }

    @PostMapping("/ask")
    CodeAskResponse ask(@Valid @RequestBody CodeAskRequest request) {
        return ragService.ask(request.repositoryId(), request.question(), request.mode(), request.limit());
    }
}
