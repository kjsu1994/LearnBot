package com.learnbot.web;

import com.learnbot.dto.SearchRequest;
import com.learnbot.dto.SearchResult;
import com.learnbot.service.SearchService;
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

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping
    List<SearchResult> search(@Valid @RequestBody SearchRequest request) {
        int limit = request.limit() == null ? 10 : request.limit();
        return searchService.search(request.query(), request.filter(), limit);
    }
}
