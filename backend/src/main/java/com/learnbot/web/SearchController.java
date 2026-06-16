package com.learnbot.web;

import com.learnbot.dto.SearchRequest;
import com.learnbot.dto.SearchResult;
import com.learnbot.service.SearchService;
import com.learnbot.service.AuthService;
import com.learnbot.security.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/search")
public class SearchController {
    private final SearchService searchService;
    private final AuthService authService;
    private final CurrentUserProvider currentUserProvider;

    public SearchController(SearchService searchService, AuthService authService, CurrentUserProvider currentUserProvider) {
        this.searchService = searchService;
        this.authService = authService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping
    List<SearchResult> search(@Valid @RequestBody SearchRequest request) {
        int limit = request.limit() == null ? 10 : request.limit();
        var user = currentUserProvider.currentUser();
        var selectedSpaceId = request.spaceId() == null ? null : authService.resolveSpace(user, request.spaceId());
        return searchService.search(request.query(), request.filter(), limit, authService.accessibleSpaceIds(user), selectedSpaceId);
    }
}
