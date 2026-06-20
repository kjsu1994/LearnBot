package com.learnbot.web;

import com.learnbot.dto.DocumentSummary;
import com.learnbot.dto.DocumentDetail;
import com.learnbot.dto.DocumentIndexingJobSummary;
import com.learnbot.dto.DocumentPreviewResponse;
import com.learnbot.dto.FileBatchIngestResponse;
import com.learnbot.dto.FileIngestItemResponse;
import com.learnbot.dto.IngestResponse;
import com.learnbot.dto.WebIngestRequest;
import com.learnbot.service.DocumentPreviewService;
import com.learnbot.service.IngestionService;
import com.learnbot.service.StoredFile;
import com.learnbot.security.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class SourceController {
    private final IngestionService ingestionService;
    private final DocumentPreviewService documentPreviewService;
    private final CurrentUserProvider currentUserProvider;

    public SourceController(
            IngestionService ingestionService,
            DocumentPreviewService documentPreviewService,
            CurrentUserProvider currentUserProvider
    ) {
        this.ingestionService = ingestionService;
        this.documentPreviewService = documentPreviewService;
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
                request.maxPages(),
                request.crawlScope(),
                request.robotsFailurePolicy(),
                request.includeAttachments(),
                request.useSitemap(),
                request.renderMode()
        );
    }

    @PostMapping("/sources/files")
    IngestResponse ingestFile(@RequestParam("file") MultipartFile file, @RequestParam(required = false) UUID spaceId) {
        return ingestionService.ingestFile(currentUserProvider.currentUser(), spaceId, file);
    }

    @PostMapping("/sources/files/batch")
    FileBatchIngestResponse ingestFiles(@RequestParam("files") List<MultipartFile> files, @RequestParam(required = false) UUID spaceId) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("At least one file is required.");
        }
        var user = currentUserProvider.currentUser();
        List<FileIngestItemResponse> items = new ArrayList<>();
        int succeeded = 0;
        for (MultipartFile file : files) {
            String filename = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                    ? "uploaded-file"
                    : file.getOriginalFilename();
            try {
                IngestResponse response = ingestionService.ingestFile(user, spaceId, file);
                items.add(new FileIngestItemResponse(filename, true, response, null));
                succeeded++;
            } catch (RuntimeException ex) {
                items.add(new FileIngestItemResponse(filename, false, null, ex.getMessage()));
            }
        }
        return new FileBatchIngestResponse(files.size(), succeeded, files.size() - succeeded, items);
    }

    @GetMapping("/documents")
    List<DocumentSummary> listDocuments(@RequestParam(required = false) UUID spaceId) {
        return ingestionService.listDocuments(currentUserProvider.currentUser(), spaceId);
    }

    @GetMapping("/document-indexing/jobs")
    List<DocumentIndexingJobSummary> listDocumentJobs(@RequestParam(required = false) UUID spaceId) {
        return ingestionService.listDocumentJobs(currentUserProvider.currentUser(), spaceId);
    }

    @GetMapping("/document-indexing/jobs/{jobId}")
    DocumentIndexingJobSummary getDocumentJob(@PathVariable UUID jobId) {
        return ingestionService.getDocumentJob(currentUserProvider.currentUser(), jobId);
    }

    @GetMapping("/documents/{documentId}")
    DocumentDetail getDocument(@PathVariable UUID documentId) {
        return ingestionService.getDocument(currentUserProvider.currentUser(), documentId);
    }

    @GetMapping("/documents/{documentId}/preview")
    DocumentPreviewResponse previewDocument(@PathVariable UUID documentId) {
        return documentPreviewService.preview(currentUserProvider.currentUser(), documentId);
    }

    @GetMapping("/documents/{documentId}/original")
    ResponseEntity<byte[]> originalDocument(@PathVariable UUID documentId) {
        StoredFile file = documentPreviewService.original(currentUserProvider.currentUser(), documentId);
        MediaType mediaType = mediaType(file.contentType());
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(file.content());
    }

    @DeleteMapping("/documents/{documentId}")
    void deleteDocument(@PathVariable UUID documentId) {
        ingestionService.deleteDocument(currentUserProvider.currentUser(), documentId);
    }

    @PostMapping("/documents/{documentId}/reindex")
    IngestResponse reindexDocument(@PathVariable UUID documentId) {
        return ingestionService.reindexDocument(currentUserProvider.currentUser(), documentId);
    }

    private MediaType mediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
