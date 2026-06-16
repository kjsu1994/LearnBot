package com.learnbot.web;

import com.learnbot.dto.CodeAskRequest;
import com.learnbot.dto.CodeAskResponse;
import com.learnbot.dto.CodeFileDetail;
import com.learnbot.dto.CodeFileSummary;
import com.learnbot.dto.CodeRepositoryCreatedResponse;
import com.learnbot.dto.CodeRepositoryCreateRequest;
import com.learnbot.dto.CodeRepositoryIndexRequest;
import com.learnbot.dto.CodeRepositorySummary;
import com.learnbot.dto.CodeSearchRequest;
import com.learnbot.dto.CodeSearchResult;
import com.learnbot.dto.IndexingJobSummary;
import com.learnbot.service.CodeFileBrowserService;
import com.learnbot.service.CodeIndexingService;
import com.learnbot.service.CodeRagService;
import com.learnbot.service.CodeRepositoryRecord;
import com.learnbot.service.CodeSearchService;
import com.learnbot.service.GitAccessToken;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/code")
public class CodeController {
    private final CodeIndexingService indexingService;
    private final CodeFileBrowserService fileBrowserService;
    private final CodeSearchService searchService;
    private final CodeRagService ragService;

    public CodeController(
            CodeIndexingService indexingService,
            CodeFileBrowserService fileBrowserService,
            CodeSearchService searchService,
            CodeRagService ragService
    ) {
        this.indexingService = indexingService;
        this.fileBrowserService = fileBrowserService;
        this.searchService = searchService;
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
        return indexingService.startIndex(repositoryId, accessToken);
    }

    @GetMapping("/repositories/{repositoryId}/jobs")
    List<IndexingJobSummary> listJobs(@PathVariable UUID repositoryId) {
        return indexingService.listJobs(repositoryId);
    }

    @PostMapping("/repositories/{repositoryId}/jobs/{jobId}/cancel")
    IndexingJobSummary cancelJob(@PathVariable UUID repositoryId, @PathVariable UUID jobId) {
        return indexingService.cancelIndex(repositoryId, jobId);
    }

    @GetMapping("/repositories/{repositoryId}/files")
    List<CodeFileSummary> listFiles(
            @PathVariable UUID repositoryId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Integer limit
    ) {
        return fileBrowserService.listFiles(repositoryId, query, limit);
    }

    @GetMapping("/repositories/{repositoryId}/files/{fileId}")
    CodeFileDetail getFile(@PathVariable UUID repositoryId, @PathVariable UUID fileId) {
        return fileBrowserService.getFile(repositoryId, fileId);
    }

    @PostMapping("/search")
    List<CodeSearchResult> search(@Valid @RequestBody CodeSearchRequest request) {
        int limit = request.limit() == null ? 10 : request.limit();
        return searchService.search(request.repositoryId(), request.query(), limit);
    }

    @PostMapping("/ask")
    CodeAskResponse ask(@Valid @RequestBody CodeAskRequest request) {
        return ragService.ask(request.repositoryId(), request.question(), request.mode(), request.limit());
    }
}
