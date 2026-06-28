package com.learnbot.web;

import com.learnbot.dto.CodeAgentApplyRequest;
import com.learnbot.dto.CodeAgentApplyResponse;
import com.learnbot.dto.CodeAgentPatchRequest;
import com.learnbot.dto.CodeAgentPatchResponse;
import com.learnbot.dto.CodeAgentPlanRequest;
import com.learnbot.dto.CodeAgentPlanResponse;
import com.learnbot.dto.CodeAgentRollbackRequest;
import com.learnbot.dto.CodeAgentRollbackResponse;
import com.learnbot.dto.CodeAgentTestRequest;
import com.learnbot.dto.CodeAgentTestResponse;
import com.learnbot.security.CurrentUserProvider;
import com.learnbot.service.AuthService;
import com.learnbot.service.CodeAgentApplyService;
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
    private final CodeAgentApplyService codeAgentApplyService;
    private final CodeIndexingService indexingService;
    private final AuthService authService;
    private final CurrentUserProvider currentUserProvider;

    public CodeAgentController(
            CodeAgentService codeAgentService,
            CodeAgentApplyService codeAgentApplyService,
            CodeIndexingService indexingService,
            AuthService authService,
            CurrentUserProvider currentUserProvider
    ) {
        this.codeAgentService = codeAgentService;
        this.codeAgentApplyService = codeAgentApplyService;
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

    @PostMapping("/apply")
    CodeAgentApplyResponse apply(@Valid @RequestBody CodeAgentApplyRequest request) {
        var user = currentUserProvider.currentUser();
        UUID selectedSpaceId = request.spaceId() == null ? null : authService.resolveSpace(user, request.spaceId());
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        selectedSpaceId = repositorySpaceId;
        return codeAgentApplyService.apply(
                request.repositoryId(),
                selectedSpaceId,
                user.id(),
                request.instruction(),
                request.diff(),
                request.targetFiles()
        );
    }

    @PostMapping("/rollback")
    CodeAgentRollbackResponse rollback(@Valid @RequestBody CodeAgentRollbackRequest request) {
        var user = currentUserProvider.currentUser();
        UUID selectedSpaceId = request.spaceId() == null ? null : authService.resolveSpace(user, request.spaceId());
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        selectedSpaceId = repositorySpaceId;
        return codeAgentApplyService.rollback(request.repositoryId(), selectedSpaceId, user.id(), request.patchSessionId());
    }

    @PostMapping("/test")
    CodeAgentTestResponse test(@Valid @RequestBody CodeAgentTestRequest request) {
        var user = currentUserProvider.currentUser();
        UUID selectedSpaceId = request.spaceId() == null ? null : authService.resolveSpace(user, request.spaceId());
        UUID repositorySpaceId = indexingService.repositorySpace(user, request.repositoryId());
        authService.requireSpace(user, repositorySpaceId);
        selectedSpaceId = repositorySpaceId;
        return codeAgentApplyService.runAllowedTest(
                request.repositoryId(),
                selectedSpaceId,
                user.id(),
                request.patchSessionId(),
                request.commandKey()
        );
    }
}
