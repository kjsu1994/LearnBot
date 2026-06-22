package com.learnbot.web;

import com.learnbot.dto.RagConversationDetail;
import com.learnbot.dto.RagConversationSummary;
import com.learnbot.security.CurrentUserProvider;
import com.learnbot.service.AuthService;
import com.learnbot.service.RagConversationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/rag/conversations")
public class RagConversationController {
    private final RagConversationService conversationService;
    private final AuthService authService;
    private final CurrentUserProvider currentUserProvider;

    public RagConversationController(RagConversationService conversationService, AuthService authService, CurrentUserProvider currentUserProvider) {
        this.conversationService = conversationService;
        this.authService = authService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    List<RagConversationSummary> list(
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) UUID spaceId
    ) {
        var user = currentUserProvider.currentUser();
        var accessibleSpaceIds = authService.accessibleSpaceIds(user);
        if (spaceId == null && accessibleSpaceIds.isEmpty()) {
            return List.of();
        }
        UUID selectedSpaceId = spaceId == null ? accessibleSpaceIds.get(0) : authService.resolveSpace(user, spaceId);
        return conversationService.list(user, selectedSpaceId, domain);
    }

    @GetMapping("/{conversationId}")
    RagConversationDetail detail(@PathVariable UUID conversationId) {
        return conversationService.detail(currentUserProvider.currentUser(), conversationId);
    }

    @DeleteMapping("/{conversationId}")
    void delete(@PathVariable UUID conversationId) {
        conversationService.delete(currentUserProvider.currentUser(), conversationId);
    }
}
