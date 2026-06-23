package com.learnbot.web;

import com.learnbot.dto.CodeAskRequest;
import com.learnbot.dto.CodeAnalysisDiagnosticSummary;
import com.learnbot.dto.CodeAskResponse;
import com.learnbot.dto.CodeFileDetail;
import com.learnbot.dto.CodeFileSummary;
import com.learnbot.dto.CodeReferenceRequest;
import com.learnbot.dto.CodeReferenceResponse;
import com.learnbot.dto.CodeRepositoryCreatedResponse;
import com.learnbot.dto.CodeRepositoryCreateRequest;
import com.learnbot.dto.CodeRepositoryIndexRequest;
import com.learnbot.dto.CodeRepositorySummary;
import com.learnbot.dto.CodeSearchRequest;
import com.learnbot.dto.CodeSearchResult;
import com.learnbot.dto.IndexingJobSummary;
import com.learnbot.dto.IndexingJobFailureSummary;
import com.learnbot.security.CurrentUserProvider;
import com.learnbot.service.AuthService;
import com.learnbot.service.CodeFileBrowserService;
import com.learnbot.service.CodeIndexingService;
import com.learnbot.service.CodeRagService;
import com.learnbot.service.RagConversationService;
import com.learnbot.service.RagStreamLimiter;
import com.learnbot.service.CodeReferenceService;
import com.learnbot.service.CodeRepositoryRecord;
import com.learnbot.service.CodeSearchService;
import com.learnbot.service.GitAccessToken;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/code")
public class CodeController {
    private final CodeIndexingService indexingService;
    private final CodeFileBrowserService fileBrowserService;
    private final CodeSearchService searchService;
    private final CodeRagService ragService;
    private final RagConversationService conversationService;
    private final CodeReferenceService referenceService;
    private final AuthService authService;
    private final CurrentUserProvider currentUserProvider;
    private final RagStreamLimiter streamLimiter;

    public CodeController(
            CodeIndexingService indexingService,
            CodeFileBrowserService fileBrowserService,
            CodeSearchService searchService,
            CodeRagService ragService,
            RagConversationService conversationService,
            CodeReferenceService referenceService,
            AuthService authService,
            CurrentUserProvider currentUserProvider,
            RagStreamLimiter streamLimiter
    ) {
        this.indexingService = indexingService;
        this.fileBrowserService = fileBrowserService;
        this.searchService = searchService;
        this.ragService = ragService;
        this.conversationService = conversationService;
        this.referenceService = referenceService;
        this.authService = authService;
        this.currentUserProvider = currentUserProvider;
        this.streamLimiter = streamLimiter;
    }

    @PostMapping("/repositories")
    CodeRepositoryCreatedResponse createRepository(@Valid @RequestBody CodeRepositoryCreateRequest request) {
        CodeRepositoryRecord repository = indexingService.createRepository(
                currentUserProvider.currentUser(),
                request.spaceId(),
                request.gitUrl(),
                request.name(),
                request.branch(),
                request.authType(),
                request.username(),
                request.token(),
                request.storeToken()
        );
        return new CodeRepositoryCreatedResponse(
                repository.id(),
                repository.spaceId(),
                repository.name(),
                repository.sourceType(),
                repository.sourceLabel(),
                repository.sourceHash(),
                repository.gitUrl(),
                repository.branch(),
                repository.authType(),
                repository.status(),
                repository.lastIndexedCommit(),
                Boolean.TRUE.equals(request.storeToken()) && request.token() != null && !request.token().isBlank()
        );
    }

    @PostMapping("/repositories/zip")
    CodeRepositoryCreatedResponse createZipRepository(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) UUID spaceId
    ) {
        CodeIndexingService.ZipRepositoryIndexResult result = indexingService.createZipRepository(
                currentUserProvider.currentUser(),
                spaceId,
                file,
                name
        );
        return createdResponse(result.repository(), false);
    }

    @GetMapping("/repositories")
    List<CodeRepositorySummary> listRepositories(@RequestParam(required = false) UUID spaceId) {
        return indexingService.listRepositories(currentUserProvider.currentUser(), spaceId);
    }

    @PostMapping("/repositories/{repositoryId}/index")
    IndexingJobSummary indexRepository(
            @PathVariable UUID repositoryId,
            @RequestBody(required = false) CodeRepositoryIndexRequest request
    ) {
        GitAccessToken accessToken = request == null
                ? new GitAccessToken(null, null)
                : new GitAccessToken(request.username(), request.token());
        return indexingService.startIndex(currentUserProvider.currentUser(), repositoryId, accessToken, request != null && Boolean.TRUE.equals(request.storeToken()));
    }

    @PostMapping("/repositories/{repositoryId}/zip")
    IndexingJobSummary replaceZipRepository(@PathVariable UUID repositoryId, @RequestParam("file") MultipartFile file) {
        return indexingService.replaceZipSnapshot(currentUserProvider.currentUser(), repositoryId, file);
    }

    @DeleteMapping("/repositories/{repositoryId}")
    void deleteRepository(@PathVariable UUID repositoryId) {
        indexingService.deleteRepository(currentUserProvider.currentUser(), repositoryId);
    }

    @DeleteMapping("/repositories/{repositoryId}/jobs")
    Map<String, Integer> clearFailedJobs(@PathVariable UUID repositoryId) {
        return Map.of("deleted", indexingService.clearFailedJobHistory(currentUserProvider.currentUser(), repositoryId));
    }

    @GetMapping("/repositories/{repositoryId}/jobs")
    List<IndexingJobSummary> listJobs(@PathVariable UUID repositoryId) {
        return indexingService.listJobs(currentUserProvider.currentUser(), repositoryId);
    }

    @GetMapping("/repositories/{repositoryId}/jobs/{jobId}/failures")
    List<IndexingJobFailureSummary> listJobFailures(@PathVariable UUID repositoryId, @PathVariable UUID jobId) {
        return indexingService.listJobFailures(currentUserProvider.currentUser(), repositoryId, jobId);
    }

    @GetMapping("/repositories/{repositoryId}/jobs/{jobId}/diagnostics")
    List<CodeAnalysisDiagnosticSummary> listAnalysisDiagnostics(@PathVariable UUID repositoryId, @PathVariable UUID jobId) {
        return indexingService.listAnalysisDiagnostics(currentUserProvider.currentUser(), repositoryId, jobId);
    }

    @PostMapping("/repositories/{repositoryId}/jobs/{jobId}/cancel")
    IndexingJobSummary cancelJob(@PathVariable UUID repositoryId, @PathVariable UUID jobId) {
        return indexingService.cancelIndex(currentUserProvider.currentUser(), repositoryId, jobId);
    }

    @GetMapping("/repositories/{repositoryId}/files")
    List<CodeFileSummary> listFiles(
            @PathVariable UUID repositoryId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Integer limit
    ) {
        authService.requireSpace(currentUserProvider.currentUser(), repositorySpace(repositoryId));
        return fileBrowserService.listFiles(repositoryId, query, limit);
    }

    @GetMapping("/repositories/{repositoryId}/files/{fileId}")
    CodeFileDetail getFile(@PathVariable UUID repositoryId, @PathVariable UUID fileId) {
        authService.requireSpace(currentUserProvider.currentUser(), repositorySpace(repositoryId));
        return fileBrowserService.getFile(repositoryId, fileId);
    }

    @PostMapping("/search")
    List<CodeSearchResult> search(@Valid @RequestBody CodeSearchRequest request) {
        int limit = request.limit() == null ? 10 : request.limit();
        var user = currentUserProvider.currentUser();
        UUID selectedSpaceId = request.spaceId() == null ? null : authService.resolveSpace(user, request.spaceId());
        if (request.repositoryId() != null) {
            authService.requireSpace(user, repositorySpace(request.repositoryId()));
        }
        return searchService.search(request.repositoryId(), request.query(), limit, authService.accessibleSpaceIds(user), selectedSpaceId);
    }

    @PostMapping("/references")
    CodeReferenceResponse references(@Valid @RequestBody CodeReferenceRequest request) {
        var user = currentUserProvider.currentUser();
        UUID selectedSpaceId = request.spaceId() == null ? null : authService.resolveSpace(user, request.spaceId());
        if (request.repositoryId() != null) {
            authService.requireSpace(user, repositorySpace(request.repositoryId()));
        }
        return referenceService.findReferences(request.repositoryId(), selectedSpaceId, authService.accessibleSpaceIds(user), request.symbol(), request.limit());
    }

    @PostMapping("/ask")
    CodeAskResponse ask(@Valid @RequestBody CodeAskRequest request) {
        var user = currentUserProvider.currentUser();
        UUID selectedSpaceId = request.spaceId() == null ? null : authService.resolveSpace(user, request.spaceId());
        var accessibleSpaceIds = authService.accessibleSpaceIds(user);
        if (selectedSpaceId == null && !accessibleSpaceIds.isEmpty()) {
            selectedSpaceId = accessibleSpaceIds.get(0);
        }
        if (request.repositoryId() != null) {
            selectedSpaceId = repositorySpace(user, request.repositoryId());
            authService.requireSpace(user, selectedSpaceId);
        }
        boolean conversational = Boolean.TRUE.equals(request.conversational()) || request.conversationId() != null;
        if (!conversational) {
            return ragService.ask(request.repositoryId(), selectedSpaceId, accessibleSpaceIds, request.question(), request.mode(), request.limit());
        }
        var context = conversationService.prepare(
                user,
                selectedSpaceId,
                RagConversationService.CODE,
                request.repositoryId(),
                request.conversationId(),
                request.question(),
                true
        );
        CodeAskResponse response = ragService.askConversational(request.repositoryId(), selectedSpaceId, accessibleSpaceIds, request.question(), request.mode(), request.limit(), context);
        return conversationService.saveCodeTurn(context, request.parentTurnId(), request.question(), response);
    }

    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter askStream(@Valid @RequestBody CodeAskRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        var user = currentUserProvider.currentUser();
        var permit = streamLimiter.tryAcquire(user);
        if (permit == null) {
            try {
                sendEvent(emitter, "error", Map.of(
                        "code", "STREAM_LIMIT_EXCEEDED",
                        "message", "\uB3D9\uC2DC \uB2F5\uBCC0 \uC0DD\uC131 \uC694\uCCAD\uC774 \uB9CE\uC2B5\uB2C8\uB2E4. \uC7A0\uC2DC \uD6C4 \uB2E4\uC2DC \uC2DC\uB3C4\uD558\uC138\uC694."
                ));
            } catch (Exception ignored) {
                // Ignore SSE error reporting failures.
            }
            emitter.complete();
            return emitter;
        }
        Mono.fromRunnable(() -> {
            try {
                UUID selectedSpaceId = request.spaceId() == null ? null : authService.resolveSpace(user, request.spaceId());
                var accessibleSpaceIds = authService.accessibleSpaceIds(user);
                if (selectedSpaceId == null && !accessibleSpaceIds.isEmpty()) {
                    selectedSpaceId = accessibleSpaceIds.get(0);
                }
                if (request.repositoryId() != null) {
                    selectedSpaceId = repositorySpace(user, request.repositoryId());
                    authService.requireSpace(user, selectedSpaceId);
                }
                boolean conversational = Boolean.TRUE.equals(request.conversational()) || request.conversationId() != null;
                sendEvent(emitter, "metadata", Map.of(
                        "conversational", conversational,
                        "domain", RagConversationService.CODE,
                        "mode", request.mode() == null ? "" : request.mode()
                ));
                CodeRagService.CodeAnswerStreamSink sink = new CodeRagService.CodeAnswerStreamSink() {
                    @Override
                    public void onEvidence(List<com.learnbot.dto.CodeEvidence> evidence) {
                        sendEvent(emitter, "evidence", Map.of("evidence", evidence));
                    }

                    @Override
                    public void onDelta(String text) {
                        sendEvent(emitter, "delta", Map.of("text", text));
                    }

                    @Override
                    public void onReplace(String answer, String reason) {
                        sendEvent(emitter, "replace", Map.of("answer", answer, "reason", reason));
                    }
                };
                CodeAskResponse response;
                if (!conversational) {
                    response = ragService.askStreaming(request.repositoryId(), selectedSpaceId, accessibleSpaceIds, request.question(), request.mode(), request.limit(), sink);
                } else {
                    var context = conversationService.prepare(
                            user,
                            selectedSpaceId,
                            RagConversationService.CODE,
                            request.repositoryId(),
                            request.conversationId(),
                            request.question(),
                            true
                    );
                    response = ragService.askConversationalStreaming(request.repositoryId(), selectedSpaceId, accessibleSpaceIds, request.question(), request.mode(), request.limit(), context, sink);
                    response = conversationService.saveCodeTurn(context, request.parentTurnId(), request.question(), response);
                }
                sendEvent(emitter, "done", response);
                emitter.complete();
            } catch (Exception ex) {
                try {
                    sendEvent(emitter, "error", Map.of("message", ex.getMessage() == null ? "Code RAG stream failed." : ex.getMessage()));
                } catch (Exception ignored) {
                    // Ignore SSE error reporting failures.
                }
                emitter.completeWithError(ex);
            }
        }).subscribeOn(Schedulers.boundedElastic())
                .doFinally(signalType -> permit.close())
                .subscribe();
        return emitter;
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception ex) {
            throw new IllegalStateException("SSE client disconnected.", ex);
        }
    }

    private UUID repositorySpace(UUID repositoryId) {
        return repositorySpace(currentUserProvider.currentUser(), repositoryId);
    }

    private UUID repositorySpace(com.learnbot.service.AppUser user, UUID repositoryId) {
        return indexingService.listRepositories(user, null).stream()
                .filter(repository -> repository.id().equals(repositoryId))
                .findFirst()
                .map(CodeRepositorySummary::spaceId)
                .orElseThrow(() -> new IllegalArgumentException("Code repository was not found."));
    }

    private CodeRepositoryCreatedResponse createdResponse(CodeRepositoryRecord repository, boolean credentialStored) {
        return new CodeRepositoryCreatedResponse(
                repository.id(),
                repository.spaceId(),
                repository.name(),
                repository.sourceType(),
                repository.sourceLabel(),
                repository.sourceHash(),
                repository.gitUrl(),
                repository.branch(),
                repository.authType(),
                repository.status(),
                repository.lastIndexedCommit(),
                credentialStored
        );
    }
}
