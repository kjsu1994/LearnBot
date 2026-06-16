package com.learnbot.web;

import com.learnbot.dto.AskRequest;
import com.learnbot.dto.AskResponse;
import com.learnbot.service.RagService;
import com.learnbot.service.AuthService;
import com.learnbot.security.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag")
public class RagController {
    private final RagService ragService;
    private final AuthService authService;
    private final CurrentUserProvider currentUserProvider;

    public RagController(RagService ragService, AuthService authService, CurrentUserProvider currentUserProvider) {
        this.ragService = ragService;
        this.authService = authService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/ask")
    AskResponse ask(@Valid @RequestBody AskRequest request) {
        var user = currentUserProvider.currentUser();
        var selectedSpaceId = request.spaceId() == null ? null : authService.resolveSpace(user, request.spaceId());
        return ragService.ask(request.question(), request.filter(), request.mode(), authService.accessibleSpaceIds(user), selectedSpaceId);
    }
}
