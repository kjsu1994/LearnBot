package com.learnbot.web;

import com.learnbot.dto.CodeAgentPatchRequest;
import com.learnbot.dto.CodeAgentPatchResponse;
import com.learnbot.dto.CodeAgentPlanRequest;
import com.learnbot.dto.CodeAgentPlanResponse;
import com.learnbot.security.CurrentUserProvider;
import com.learnbot.service.AuthService;
import com.learnbot.service.CodeAgentService;
import com.learnbot.service.CodeIndexingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/code-agent")
public class CodeAgentController {
    private final CodeAgentService codeAgentService;
    private final CodeIndexingService indexingService;
    private final AuthService authService;
    private final CurrentUserProvider currentUserProvider;

    public CodeAgentController(
            CodeAgentService codeAgentService,
            CodeIndexingService indexingService,
            AuthService authService,
            CurrentUserProvider currentUserProvider
    ) {
        this.codeAgentService = codeAgentService;
        this.indexingService = indexingService;
        this.authService = authService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/plan")
    CodeAgentPlanResponse plan(@Valid @RequestBody CodeAgentPlanRequest request) {
        var user = currentUserProvider.currentUser();
        UUID selectedSpaceId = request.spaceId() == null ? null : authService.resolveSpace(user, request.spaceId());
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        selectedSpaceId = repositorySpaceId;
        return codeAgentService.plan(
                request.repositoryId(),
                selectedSpaceId,
                authService.accessibleSpaceIds(user),
                request.instruction(),
                request.limit()
        );
    }

    @PostMapping("/patch")
    CodeAgentPatchResponse patch(@Valid @RequestBody CodeAgentPatchRequest request) {
        var user = currentUserProvider.currentUser();
        UUID selectedSpaceId = request.spaceId() == null ? null : authService.resolveSpace(user, request.spaceId());
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        selectedSpaceId = repositorySpaceId;
        return codeAgentService.patch(
                request.repositoryId(),
                selectedSpaceId,
                authService.accessibleSpaceIds(user),
                request.instruction(),
                request.targetFiles()
        );
    }
}
