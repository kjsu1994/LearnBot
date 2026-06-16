package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.domain.SourceStatus;
import com.learnbot.domain.SourceType;
import com.learnbot.dto.DocumentSummary;
import com.learnbot.dto.DocumentDetail;
import com.learnbot.dto.IngestResponse;
import com.learnbot.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Service
public class IngestionService {
    private final DocumentRepository repository;
    private final WebPageExtractor webPageExtractor;
    private final FileExtractor fileExtractor;
    private final ObjectStorageService objectStorageService;
    private final Chunker chunker;
    private final OllamaClient ollamaClient;
    private final LearnBotProperties properties;
    private final AuthService authService;
    private final AuditService auditService;

    public IngestionService(
            DocumentRepository repository,
            WebPageExtractor webPageExtractor,
            FileExtractor fileExtractor,
            ObjectStorageService objectStorageService,
            Chunker chunker,
            OllamaClient ollamaClient,
            LearnBotProperties properties,
            AuthService authService,
            AuditService auditService
    ) {
        this.repository = repository;
        this.webPageExtractor = webPageExtractor;
        this.fileExtractor = fileExtractor;
        this.objectStorageService = objectStorageService;
        this.chunker = chunker;
        this.ollamaClient = ollamaClient;
        this.properties = properties;
        this.authService = authService;
        this.auditService = auditService;
    }

    @Transactional
    public IngestResponse ingestWeb(AppUser user, UUID spaceId, String url) {
        UUID resolvedSpaceId = authService.resolveSpace(user, spaceId);
        UUID sourceId = repository.createSource(SourceType.WEB, url, url, resolvedSpaceId, user.id());
        try {
            ExtractedDocument document = webPageExtractor.extract(sourceId, url);
            IngestResponse response = index(sourceId, resolvedSpaceId, document);
            auditService.log(user, "DOCUMENT_INGESTED", "DOCUMENT", response.documentId(), resolvedSpaceId, "Web document was indexed.");
            return response;
        } catch (RuntimeException ex) {
            repository.updateSourceStatus(sourceId, SourceStatus.FAILED, ex.getMessage());
            auditService.log(user, "DOCUMENT_INGEST_FAILED", "SOURCE", sourceId, resolvedSpaceId, ex.getMessage());
            throw ex;
        }
    }

    @Transactional
    public IngestResponse ingestFile(AppUser user, UUID spaceId, MultipartFile file) {
        UUID resolvedSpaceId = authService.resolveSpace(user, spaceId);
        String name = file.getOriginalFilename() == null ? "uploaded-file" : file.getOriginalFilename();
        UUID sourceId = repository.createSource(SourceType.FILE, name, name, resolvedSpaceId, user.id());
        try {
            StoredObject storedObject = objectStorageService.store(sourceId, file);
            repository.createSourceObject(sourceId, storedObject);
            ExtractedDocument document = fileExtractor.extract(file);
            IngestResponse response = index(sourceId, resolvedSpaceId, document);
            auditService.log(user, "DOCUMENT_INGESTED", "DOCUMENT", response.documentId(), resolvedSpaceId, "File document was indexed.");
            return response;
        } catch (RuntimeException ex) {
            repository.updateSourceStatus(sourceId, SourceStatus.FAILED, ex.getMessage());
            auditService.log(user, "DOCUMENT_INGEST_FAILED", "SOURCE", sourceId, resolvedSpaceId, ex.getMessage());
            throw ex;
        }
    }

    public List<DocumentSummary> listDocuments(AppUser user, UUID spaceId) {
        UUID selectedSpaceId = spaceId == null ? null : authService.resolveSpace(user, spaceId);
        return repository.listDocuments(authService.accessibleSpaceIds(user), selectedSpaceId);
    }

    public DocumentDetail getDocument(AppUser user, UUID documentId) {
        DocumentSummary summary = repository.findDocument(documentId, authService.accessibleSpaceIds(user))
                .orElseThrow(() -> new IllegalArgumentException("Document was not found."));
        var chunks = repository.listDocumentChunks(documentId);
        var storedObject = repository.findStoredObjectSummary(summary.sourceId()).orElse(null);
        var crawlAudits = repository.listCrawlAudits(summary.sourceId());
        return new DocumentDetail(summary, chunks.size(), storedObject, chunks, crawlAudits);
    }

    @Transactional
    public void deleteDocument(AppUser user, UUID documentId) {
        DocumentSummary summary = repository.findDocument(documentId, authService.accessibleSpaceIds(user))
                .orElseThrow(() -> new IllegalArgumentException("Document was not found."));
        StoredSource source = repository.findSourceByDocumentId(documentId, authService.accessibleSpaceIds(user))
                .orElseThrow(() -> new IllegalArgumentException("Document was not found."));
        repository.softDeleteSource(source.id(), user.id());
        auditService.log(user, "DOCUMENT_DELETED", "DOCUMENT", documentId, summary.spaceId(), "Document was soft deleted.");
    }

    @Transactional
    public IngestResponse reindexDocument(AppUser user, UUID documentId) {
        DocumentSummary summary = repository.findDocument(documentId, authService.accessibleSpaceIds(user))
                .orElseThrow(() -> new IllegalArgumentException("Document was not found."));
        StoredSource source = repository.findSourceByDocumentId(documentId, authService.accessibleSpaceIds(user))
                .orElseThrow(() -> new IllegalArgumentException("Document was not found."));
        repository.updateSourceStatus(source.id(), SourceStatus.INDEXING, null);
        repository.deleteDocumentsForSource(source.id());

        try {
            ExtractedDocument document = switch (source.type()) {
                case WEB -> webPageExtractor.extract(source.id(), source.location());
                case FILE -> {
                    StoredObject object = repository.findSourceObject(source.id())
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "Original file is not stored. Upload the file again before reindexing."
                            ));
                    yield fileExtractor.extract(objectStorageService.load(object));
                }
            };
            IngestResponse response = index(source.id(), summary.spaceId(), document);
            auditService.log(user, "DOCUMENT_REINDEXED", "DOCUMENT", response.documentId(), summary.spaceId(), "Document was reindexed.");
            return response;
        } catch (RuntimeException ex) {
            repository.updateSourceStatus(source.id(), SourceStatus.FAILED, ex.getMessage());
            auditService.log(user, "DOCUMENT_REINDEX_FAILED", "SOURCE", source.id(), summary.spaceId(), ex.getMessage());
            throw ex;
        }
    }

    private IngestResponse index(UUID sourceId, UUID spaceId, ExtractedDocument document) {
        List<Chunk> chunks = chunker.split(document.content());
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("No extractable text was found.");
        }

        UUID documentId = repository.createDocument(
                sourceId,
                document.title(),
                document.sourceUri(),
                document.contentType(),
                document.metadata()
        );

        List<String> chunkTexts = chunks.stream().map(Chunk::content).toList();
        List<List<Double>> embeddings = ollamaClient.embed(chunkTexts);
        for (List<Double> embedding : embeddings) {
            if (embedding.size() != properties.getEmbedding().getDimensions()) {
                throw new IllegalArgumentException("Embedding dimension mismatch. Expected "
                        + properties.getEmbedding().getDimensions() + " but got " + embedding.size()
                        + ". Recreate the vector column and reindex when changing embedding models.");
            }
        }

        repository.addChunks(documentId, chunks, embeddings);
        repository.updateSourceStatus(sourceId, SourceStatus.INDEXED, null);
        return new IngestResponse(sourceId, documentId, spaceId, chunks.size(), SourceStatus.INDEXED.name());
    }
}
