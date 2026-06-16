package com.learnbot.web;

import com.learnbot.dto.DocumentSummary;
import com.learnbot.dto.IngestResponse;
import com.learnbot.dto.WebIngestRequest;
import com.learnbot.service.IngestionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api")
public class SourceController {
    private final IngestionService ingestionService;

    public SourceController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/sources/web")
    IngestResponse ingestWeb(@Valid @RequestBody WebIngestRequest request) {
        return ingestionService.ingestWeb(request.url());
    }

    @PostMapping("/sources/files")
    IngestResponse ingestFile(@RequestParam("file") MultipartFile file) {
        return ingestionService.ingestFile(file);
    }

    @GetMapping("/documents")
    List<DocumentSummary> listDocuments() {
        return ingestionService.listDocuments();
    }
}
