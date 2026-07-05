package com.learnbot.web;

import com.learnbot.dto.interactive.CodeAgentInteractiveTurnRequest;
import com.learnbot.dto.interactive.CodeAgentInteractiveTurnResponse;
import com.learnbot.dto.interactive.CodeAgentInteractiveContextReadResultRequest;
import com.learnbot.security.CurrentUserProvider;
import com.learnbot.service.AuthService;
import com.learnbot.service.CodeAgentInteractiveService;
import com.learnbot.service.CodeIndexingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/code-agent/interactive")
public class CodeAgentInteractiveController {
    private final CodeAgentInteractiveService interactiveService;
    private final CodeIndexingService indexingService;
    private final AuthService authService;
    private final CurrentUserProvider currentUserProvider;

    public CodeAgentInteractiveController(
            CodeAgentInteractiveService interactiveService,
            CodeIndexingService indexingService,
            AuthService authService,
            CurrentUserProvider currentUserProvider
    ) {
        this.interactiveService = interactiveService;
        this.indexingService = indexingService;
        this.authService = authService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/turns")
    CodeAgentInteractiveTurnResponse handleTurn(@Valid @RequestBody CodeAgentInteractiveTurnRequest request) {
        var user = currentUserProvider.currentUser();
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        return interactiveService.handleTurn(
                user,
                repositorySpaceId,
                authService.accessibleSpaceIds(user),
                request
        );
    }

    @PostMapping("/context/read-result")
    Map<String, Object> saveContextReadResult(@Valid @RequestBody CodeAgentInteractiveContextReadResultRequest request) {
        var user = currentUserProvider.currentUser();
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        return interactiveService.saveContextReadResult(user, repositorySpaceId, request);
    }

    @GetMapping("/sessions/{conversationId}/context")
    Map<String, Object> sessionContext(@PathVariable UUID conversationId, @RequestParam UUID repositoryId) {
        var user = currentUserProvider.currentUser();
        UUID repositorySpaceId = indexingService.repositorySpace(user, repositoryId);
        authService.requireSpace(user, repositorySpaceId);
        return interactiveService.sessionContext(user, repositorySpaceId, repositoryId, conversationId);
    }

    @GetMapping("/sessions/recent")
    List<?> recentSessions(@RequestParam UUID repositoryId) {
        var user = currentUserProvider.currentUser();
        UUID repositorySpaceId = indexingService.repositorySpace(user, repositoryId);
        authService.requireSpace(user, repositorySpaceId);
        return interactiveService.recentSessions(user, repositorySpaceId);
    }
}
