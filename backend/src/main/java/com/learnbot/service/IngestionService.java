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
    private final WebCrawler webCrawler;
    private final FileExtractor fileExtractor;
    private final WebUrlNormalizer webUrlNormalizer;
    private final ObjectStorageService objectStorageService;
    private final Chunker chunker;
    private final OllamaClient ollamaClient;
    private final LearnBotProperties properties;
    private final AuthService authService;
    private final AuditService auditService;

    public IngestionService(
            DocumentRepository repository,
            WebPageExtractor webPageExtractor,
            WebCrawler webCrawler,
            FileExtractor fileExtractor,
            WebUrlNormalizer webUrlNormalizer,
            ObjectStorageService objectStorageService,
            Chunker chunker,
            OllamaClient ollamaClient,
            LearnBotProperties properties,
            AuthService authService,
            AuditService auditService
    ) {
        this.repository = repository;
        this.webPageExtractor = webPageExtractor;
        this.webCrawler = webCrawler;
        this.fileExtractor = fileExtractor;
        this.webUrlNormalizer = webUrlNormalizer;
        this.objectStorageService = objectStorageService;
        this.chunker = chunker;
        this.ollamaClient = ollamaClient;
        this.properties = properties;
        this.authService = authService;
        this.auditService = auditService;
    }

    @Transactional
    public IngestResponse ingestWeb(AppUser user, UUID spaceId, String url) {
        return ingestWeb(user, spaceId, url, false, null, null);
    }

    @Transactional
    public IngestResponse ingestWeb(
            AppUser user,
            UUID spaceId,
            String url,
            Boolean recursive,
            Integer maxDepth,
            Integer maxPages
    ) {
        UUID resolvedSpaceId = authService.resolveSpace(user, spaceId);
        String normalizedUrl = webUrlNormalizer.normalize(url);
        UUID sourceId = repository.createSource(SourceType.WEB, normalizedUrl, normalizedUrl, resolvedSpaceId, user.id());
        try {
            WebDocuments documents = extractWebDocuments(sourceId, normalizedUrl, Boolean.TRUE.equals(recursive), maxDepth, maxPages);
            IngestResponse response = indexAll(
                    sourceId,
                    resolvedSpaceId,
                    documents.documents(),
                    documents.pageCount(),
                    documents.skippedCount()
            );
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
        int previousDocumentCount = repository.countDocumentsForSource(source.id());
        repository.deleteDocumentsForSource(source.id());

        try {
            IngestResponse response = switch (source.type()) {
                case WEB -> {
                    WebDocuments documents = extractWebDocuments(
                            source.id(),
                            source.location(),
                            previousDocumentCount > 1,
                            null,
                            null
                    );
                    yield indexAll(source.id(), summary.spaceId(), documents.documents(), documents.pageCount(), documents.skippedCount());
                }
                case FILE -> {
                    StoredObject object = repository.findSourceObject(source.id())
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "Original file is not stored. Upload the file again before reindexing."
                            ));
                    yield index(source.id(), summary.spaceId(), fileExtractor.extract(objectStorageService.load(object)));
                }
            };
            auditService.log(user, "DOCUMENT_REINDEXED", "DOCUMENT", response.documentId(), summary.spaceId(), "Document was reindexed.");
            return response;
        } catch (RuntimeException ex) {
            repository.updateSourceStatus(source.id(), SourceStatus.FAILED, ex.getMessage());
            auditService.log(user, "DOCUMENT_REINDEX_FAILED", "SOURCE", source.id(), summary.spaceId(), ex.getMessage());
            throw ex;
        }
    }

    private IngestResponse index(UUID sourceId, UUID spaceId, ExtractedDocument document) {
        return indexAll(sourceId, spaceId, List.of(document), 1, 0);
    }

    private IngestResponse indexAll(
            UUID sourceId,
            UUID spaceId,
            List<ExtractedDocument> documents,
            int pageCount,
            int skippedCount
    ) {
        UUID firstDocumentId = null;
        int documentCount = 0;
        int totalChunkCount = 0;

        for (ExtractedDocument document : documents) {
            List<Chunk> chunks = chunker.split(document.content());
            if (chunks.isEmpty()) {
                continue;
            }

            UUID documentId = repository.createDocument(
                    sourceId,
                    document.title(),
                    document.sourceUri(),
                    document.contentType(),
                    document.metadata()
            );
            if (firstDocumentId == null) {
                firstDocumentId = documentId;
            }

            List<String> chunkTexts = chunks.stream().map(Chunk::content).toList();
            List<List<Double>> embeddings = ollamaClient.embed(chunkTexts);
            validateEmbeddings(embeddings);
            repository.addChunks(documentId, chunks, embeddings);
            documentCount++;
            totalChunkCount += chunks.size();
        }

        if (documentCount == 0 || firstDocumentId == null) {
            throw new IllegalArgumentException("No extractable text was found.");
        }
        repository.updateSourceStatus(sourceId, SourceStatus.INDEXED, null);
        return new IngestResponse(
                sourceId,
                firstDocumentId,
                spaceId,
                totalChunkCount,
                SourceStatus.INDEXED.name(),
                documentCount,
                pageCount,
                skippedCount
        );
    }

    private WebDocuments extractWebDocuments(
            UUID sourceId,
            String url,
            boolean recursive,
            Integer maxDepth,
            Integer maxPages
    ) {
        if (!recursive) {
            return new WebDocuments(List.of(webPageExtractor.extract(sourceId, url)), 1, 0);
        }
        WebCrawler.CrawlResult result = webCrawler.crawl(
                sourceId,
                url,
                maxDepth == null ? properties.getCrawler().getMaxDepth() : maxDepth,
                maxPages == null ? properties.getCrawler().getMaxPagesPerRequest() : maxPages
        );
        return new WebDocuments(result.documents(), result.fetchedCount(), result.skippedCount());
    }

    private void validateEmbeddings(List<List<Double>> embeddings) {
        for (List<Double> embedding : embeddings) {
            if (embedding.size() != properties.getEmbedding().getDimensions()) {
                throw new IllegalArgumentException("Embedding dimension mismatch. Expected "
                        + properties.getEmbedding().getDimensions() + " but got " + embedding.size()
                        + ". Recreate the vector column and reindex when changing embedding models.");
            }
        }
    }

    private record WebDocuments(List<ExtractedDocument> documents, int pageCount, int skippedCount) {
    }
}
