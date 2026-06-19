package com.learnbot.web;

import com.learnbot.dto.SavedAnswerDetail;
import com.learnbot.dto.SavedAnswerRequest;
import com.learnbot.dto.SavedAnswerSummary;
import com.learnbot.dto.SavedAnswerUpdateRequest;
import com.learnbot.security.CurrentUserProvider;
import com.learnbot.service.SavedAnswerService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/saved-answers")
public class SavedAnswerController {
    private final SavedAnswerService service;
    private final CurrentUserProvider currentUserProvider;

    public SavedAnswerController(SavedAnswerService service, CurrentUserProvider currentUserProvider) {
        this.service = service;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    List<SavedAnswerSummary> list(
            @RequestParam(required = false) UUID spaceId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Integer limit
    ) {
        return service.list(currentUserProvider.currentUser(), spaceId, type, query, limit);
    }

    @GetMapping("/{id}")
    SavedAnswerDetail find(@PathVariable UUID id) {
        return service.find(currentUserProvider.currentUser(), id);
    }

    @PostMapping
    SavedAnswerDetail create(@Valid @RequestBody SavedAnswerRequest request) {
        return service.create(currentUserProvider.currentUser(), request);
    }

    @PatchMapping("/{id}")
    SavedAnswerDetail update(@PathVariable UUID id, @Valid @RequestBody SavedAnswerUpdateRequest request) {
        return service.update(currentUserProvider.currentUser(), id, request);
    }

    @DeleteMapping("/{id}")
    void delete(@PathVariable UUID id) {
        service.delete(currentUserProvider.currentUser(), id);
    }
}
