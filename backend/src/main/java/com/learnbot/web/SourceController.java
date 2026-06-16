package com.learnbot.web;

import com.learnbot.dto.DocumentSummary;
import com.learnbot.dto.DocumentDetail;
import com.learnbot.dto.IngestResponse;
import com.learnbot.dto.WebIngestRequest;
import com.learnbot.service.IngestionService;
import com.learnbot.security.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class SourceController {
    private final IngestionService ingestionService;
    private final CurrentUserProvider currentUserProvider;

    public SourceController(IngestionService ingestionService, CurrentUserProvider currentUserProvider) {
        this.ingestionService = ingestionService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/sources/web")
    IngestResponse ingestWeb(@Valid @RequestBody WebIngestRequest request) {
        return ingestionService.ingestWeb(
                currentUserProvider.currentUser(),
                request.spaceId(),
                request.url(),
                request.recursive(),
                request.maxDepth(),
                request.maxPages()
        );
    }

    @PostMapping("/sources/files")
    IngestResponse ingestFile(@RequestParam("file") MultipartFile file, @RequestParam(required = false) UUID spaceId) {
        return ingestionService.ingestFile(currentUserProvider.currentUser(), spaceId, file);
    }

    @GetMapping("/documents")
    List<DocumentSummary> listDocuments(@RequestParam(required = false) UUID spaceId) {
        return ingestionService.listDocuments(currentUserProvider.currentUser(), spaceId);
    }

    @GetMapping("/documents/{documentId}")
    DocumentDetail getDocument(@PathVariable UUID documentId) {
        return ingestionService.getDocument(currentUserProvider.currentUser(), documentId);
    }

    @DeleteMapping("/documents/{documentId}")
    void deleteDocument(@PathVariable UUID documentId) {
        ingestionService.deleteDocument(currentUserProvider.currentUser(), documentId);
    }

    @PostMapping("/documents/{documentId}/reindex")
    IngestResponse reindexDocument(@PathVariable UUID documentId) {
        return ingestionService.reindexDocument(currentUserProvider.currentUser(), documentId);
    }
}
