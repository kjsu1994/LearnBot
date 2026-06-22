package com.learnbot.web;

import com.learnbot.dto.AskRequest;
import com.learnbot.dto.AskResponse;
import com.learnbot.service.RagConversationService;
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
    private final RagConversationService conversationService;
    private final AuthService authService;
    private final CurrentUserProvider currentUserProvider;

    public RagController(RagService ragService, RagConversationService conversationService, AuthService authService, CurrentUserProvider currentUserProvider) {
        this.ragService = ragService;
        this.conversationService = conversationService;
        this.authService = authService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/ask")
    AskResponse ask(@Valid @RequestBody AskRequest request) {
        var user = currentUserProvider.currentUser();
        var selectedSpaceId = request.spaceId() == null ? null : authService.resolveSpace(user, request.spaceId());
        var accessibleSpaceIds = authService.accessibleSpaceIds(user);
        if (selectedSpaceId == null && !accessibleSpaceIds.isEmpty()) {
            selectedSpaceId = accessibleSpaceIds.get(0);
        }
        boolean conversational = Boolean.TRUE.equals(request.conversational()) || request.conversationId() != null;
        if (!conversational) {
            return ragService.ask(request.question(), request.filter(), request.mode(), request.speedProfile(), accessibleSpaceIds, selectedSpaceId);
        }
        var context = conversationService.prepare(
                user,
                selectedSpaceId,
                RagConversationService.DOCUMENT,
                null,
                request.conversationId(),
                request.question(),
                true
        );
        AskResponse response = ragService.ask(context.rewrittenQuestion(), request.filter(), request.mode(), request.speedProfile(), accessibleSpaceIds, selectedSpaceId);
        return conversationService.saveDocumentTurn(context, request.parentTurnId(), request.question(), response);
    }
}
