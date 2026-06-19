package com.learnbot.service;

import com.learnbot.dto.SavedAnswerDetail;
import com.learnbot.dto.SavedAnswerRequest;
import com.learnbot.dto.SavedAnswerSummary;
import com.learnbot.dto.SavedAnswerUpdateRequest;
import com.learnbot.repository.SavedAnswerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class SavedAnswerService {
    private final SavedAnswerRepository repository;
    private final AuthService authService;

    public SavedAnswerService(SavedAnswerRepository repository, AuthService authService) {
        this.repository = repository;
        this.authService = authService;
    }

    @Transactional
    public SavedAnswerDetail create(AppUser user, SavedAnswerRequest request) {
        UUID spaceId = authService.resolveSpace(user, request.spaceId());
        String type = normalizeType(request.answerType());
        String title = normalizeTitle(request.title(), request.question());
        SavedAnswerRequest resolved = new SavedAnswerRequest(
                spaceId,
                type,
                request.question(),
                request.mode(),
                request.answer(),
                request.citations(),
                request.evidence(),
                request.confidence(),
                request.diagnostics(),
                request.repositoryId(),
                title
        );
        return repository.create(user.id(), resolved, type, title);
    }

    public List<SavedAnswerSummary> list(AppUser user, UUID spaceId, String type, String query, Integer limit) {
        UUID resolvedSpaceId = spaceId == null ? null : authService.resolveSpace(user, spaceId);
        String normalizedType = type == null || type.isBlank() ? null : normalizeType(type);
        int resolvedLimit = limit == null ? 50 : Math.max(1, Math.min(limit, 100));
        return repository.list(user.id(), resolvedSpaceId, normalizedType, query, resolvedLimit);
    }

    public SavedAnswerDetail find(AppUser user, UUID id) {
        SavedAnswerDetail answer = repository.find(user.id(), id)
                .orElseThrow(() -> new IllegalArgumentException("Saved answer was not found."));
        authService.requireSpace(user, answer.spaceId());
        return answer;
    }

    @Transactional
    public SavedAnswerDetail update(AppUser user, UUID id, SavedAnswerUpdateRequest request) {
        UUID spaceId = repository.findSpaceId(user.id(), id)
                .orElseThrow(() -> new IllegalArgumentException("Saved answer was not found."));
        authService.requireSpace(user, spaceId);
        repository.updateTitle(user.id(), id, normalizeTitle(request.title(), request.title()));
        return find(user, id);
    }

    @Transactional
    public void delete(AppUser user, UUID id) {
        UUID spaceId = repository.findSpaceId(user.id(), id)
                .orElseThrow(() -> new IllegalArgumentException("Saved answer was not found."));
        authService.requireSpace(user, spaceId);
        repository.softDelete(user.id(), id);
    }

    private String normalizeType(String type) {
        String normalized = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("DOCUMENT") && !normalized.equals("CODE")) {
            throw new IllegalArgumentException("answerType must be DOCUMENT or CODE.");
        }
        return normalized;
    }

    private String normalizeTitle(String title, String question) {
        String clean = title == null || title.isBlank() ? question : title;
        clean = clean == null ? "" : clean.trim().replaceAll("\\s+", " ");
        if (clean.isBlank()) {
            clean = "Saved answer";
        }
        return clean.length() > 120 ? clean.substring(0, 120) : clean;
    }
}
