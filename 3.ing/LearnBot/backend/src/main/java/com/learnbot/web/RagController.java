package com.learnbot.web;

import com.learnbot.dto.AskRequest;
import com.learnbot.dto.AskResponse;
import com.learnbot.service.RagService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag")
public class RagController {
    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/ask")
    AskResponse ask(@Valid @RequestBody AskRequest request) {
        return ragService.ask(request.question(), request.filter());
    }
}
