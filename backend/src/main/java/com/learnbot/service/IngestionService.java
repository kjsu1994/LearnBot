package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.domain.SourceStatus;
import com.learnbot.domain.SourceType;
import com.learnbot.dto.DocumentSummary;
import com.learnbot.dto.DocumentDetail;
import com.learnbot.dto.IngestResponse;
import com.learnbot.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final DocumentContextBuilder documentContextBuilder;
    private final OllamaClient ollamaClient;
    private final LearnBotProperties properties;
    private final AuthService authService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public IngestionService(
            DocumentRepository repository,
            WebPageExtractor webPageExtractor,
            WebCrawler webCrawler,
            FileExtractor fileExtractor,
            WebUrlNormalizer webUrlNormalizer,
            ObjectStorageService objectStorageService,
            Chunker chunker,
            DocumentContextBuilder documentContextBuilder,
            OllamaClient ollamaClient,
            LearnBotProperties properties,
            AuthService authService,
            AuditService auditService,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.webPageExtractor = webPageExtractor;
        this.webCrawler = webCrawler;
        this.fileExtractor = fileExtractor;
        this.webUrlNormalizer = webUrlNormalizer;
        this.objectStorageService = objectStorageService;
        this.chunker = chunker;
        this.documentContextBuilder = documentContextBuilder;
        this.ollamaClient = ollamaClient;
        this.properties = properties;
        this.authService = authService;
        this.auditService = auditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public IngestResponse ingestWeb(AppUser user, UUID spaceId, String url) {
        return ingestWeb(user, spaceId, url, false, null, null);
    }

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
        IngestResponse response;
        try {
            WebDocuments documents = extractWebDocuments(sourceId, normalizedUrl, Boolean.TRUE.equals(recursive), maxDepth, maxPages);
            response = indexAll(
                    sourceId,
                    resolvedSpaceId,
                    documents.documents(),
                    documents.pageCount(),
                    documents.skippedCount()
            );
        } catch (RuntimeException ex) {
            recordFailure(user, sourceId, resolvedSpaceId, "DOCUMENT_INGEST_FAILED", ex);
            throw ex;
        }
        auditService.log(
                user,
                "DOCUMENT_INGESTED",
                "DOCUMENT",
                response.documentId(),
                resolvedSpaceId,
                "Web document was indexed."
        );
        return response;
    }

    public IngestResponse ingestFile(AppUser user, UUID spaceId, MultipartFile file) {
        UUID resolvedSpaceId = authService.resolveSpace(user, spaceId);
        String name = file.getOriginalFilename() == null ? "uploaded-file" : file.getOriginalFilename();
        UUID sourceId = repository.createSource(SourceType.FILE, name, name, resolvedSpaceId, user.id());
        IngestResponse response;
        try {
            StoredObject storedObject = objectStorageService.store(sourceId, file);
            ExtractedDocument document = fileExtractor.extract(file);
            response = indexStoredFile(sourceId, resolvedSpaceId, storedObject, document);
        } catch (RuntimeException ex) {
            recordFailure(user, sourceId, resolvedSpaceId, "DOCUMENT_INGEST_FAILED", ex);
            throw ex;
        }
        auditService.log(user, "DOCUMENT_INGESTED", "DOCUMENT", response.documentId(), resolvedSpaceId, "File document was indexed.");
        return response;
    }

    public List<DocumentSummary> listDocuments(AppUser user, UUID spaceId) {
        UUID selectedSpaceId = spaceId == null ? null : authService.resolveSpace(user, spaceId);
        return repository.listDocuments(authService.accessibleSpaceIds(user), selectedSpaceId);
    }

    public DocumentDetail getDocument(AppUser user, UUID documentId) {
        DocumentSummary summary = repository.findDocument(documentId, authService.accessibleSpaceIds(user))
                .orElseThrow(() -> new IllegalArgumentException("Document was not found."));
        var chunks = repository.listDocumentChunks(documentId).stream()
                .filter(chunk -> !isDocumentContext(chunk.metadata()))
                .toList();
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

    public IngestResponse reindexDocument(AppUser user, UUID documentId) {
        DocumentSummary summary = repository.findDocument(documentId, authService.accessibleSpaceIds(user))
                .orElseThrow(() -> new IllegalArgumentException("Document was not found."));
        StoredSource source = repository.findSourceByDocumentId(documentId, authService.accessibleSpaceIds(user))
                .orElseThrow(() -> new IllegalArgumentException("Document was not found."));
        repository.updateSourceStatus(source.id(), SourceStatus.INDEXING, null);

        IngestResponse response;
        try {
            int previousDocumentCount = repository.countDocumentsForSource(source.id());
            response = switch (source.type()) {
                case WEB -> {
                    String normalizedUrl = webUrlNormalizer.normalize(source.location());
                    WebDocuments documents = extractWebDocuments(
                            source.id(),
                            normalizedUrl,
                            previousDocumentCount > 1,
                            null,
                            null
                    );
                    yield reindexAll(
                            source.id(),
                            summary.spaceId(),
                            documents.documents(),
                            documents.pageCount(),
                            documents.skippedCount()
                    );
                }
                case FILE -> {
                    StoredObject object = repository.findSourceObject(source.id())
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "Original file is not stored. Upload the file again before reindexing."
                            ));
                    yield reindexAll(
                            source.id(),
                            summary.spaceId(),
                            List.of(fileExtractor.extract(objectStorageService.load(object))),
                            1,
                            0
                    );
                }
            };
        } catch (RuntimeException ex) {
            recordFailure(user, source.id(), summary.spaceId(), "DOCUMENT_REINDEX_FAILED", ex);
            throw ex;
        }
        auditService.log(user, "DOCUMENT_REINDEXED", "DOCUMENT", response.documentId(), summary.spaceId(), "Document was reindexed.");
        return response;
    }

    private IngestResponse indexAll(
            UUID sourceId,
            UUID spaceId,
            List<ExtractedDocument> documents,
            int pageCount,
            int skippedCount
    ) {
        List<IndexedDocument> indexedDocuments = prepareIndex(documents);
        return transactionTemplate.execute(status ->
                persistIndex(sourceId, spaceId, indexedDocuments, pageCount, skippedCount)
        );
    }

    private IngestResponse indexStoredFile(UUID sourceId, UUID spaceId, StoredObject storedObject, ExtractedDocument document) {
        List<IndexedDocument> indexedDocuments = prepareIndex(List.of(document));
        return transactionTemplate.execute(status -> {
            repository.createSourceObject(sourceId, storedObject);
            return persistIndex(sourceId, spaceId, indexedDocuments, 1, 0);
        });
    }

    private IngestResponse reindexAll(
            UUID sourceId,
            UUID spaceId,
            List<ExtractedDocument> documents,
            int pageCount,
            int skippedCount
    ) {
        List<IndexedDocument> indexedDocuments = prepareIndex(documents);
        return transactionTemplate.execute(status -> {
            repository.deleteDocumentsForSource(sourceId);
            return persistIndex(sourceId, spaceId, indexedDocuments, pageCount, skippedCount);
        });
    }

    private List<IndexedDocument> prepareIndex(List<ExtractedDocument> documents) {
        List<PreparedDocument> preparedDocuments = new ArrayList<>();
        for (ExtractedDocument document : documents) {
            List<Chunk> chunks = chunker.split(document);
            if (chunks.isEmpty()) {
                continue;
            }
            preparedDocuments.add(new PreparedDocument(document, chunks));
        }

        if (preparedDocuments.isEmpty()) {
            throw new IllegalArgumentException("No extractable text was found.");
        }

        List<PreparedDocument> enrichedDocuments = enrichWithDocumentContext(preparedDocuments);
        List<IndexedDocument> indexedDocuments = new ArrayList<>();
        for (PreparedDocument preparedDocument : enrichedDocuments) {
            List<Chunk> chunks = preparedDocument.chunks();
            List<String> chunkTexts = chunks.stream().map(Chunk::content).toList();
            List<List<Double>> embeddings = embedInBatches(chunkTexts);
            validateEmbeddings(embeddings, chunks.size());
            indexedDocuments.add(new IndexedDocument(preparedDocument.document(), chunks, embeddings));
        }

        return indexedDocuments;
    }

    private List<PreparedDocument> enrichWithDocumentContext(List<PreparedDocument> documents) {
        if (!documentContextBuilder.enabled()) {
            return documents;
        }
        try {
            List<DocumentContextBuilder.DocumentContextInput> inputs = documents.stream()
                    .map(document -> new DocumentContextBuilder.DocumentContextInput(document.document(), document.chunks()))
                    .toList();
            List<Chunk> sourceContext = documentContextBuilder.buildSourceContext(inputs);
            List<PreparedDocument> enriched = new ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                PreparedDocument prepared = documents.get(i);
                List<Chunk> chunks = new ArrayList<>(prepared.chunks());
                chunks.addAll(documentContextBuilder.buildDocumentContext(prepared.document(), prepared.chunks()));
                if (i == 0) {
                    chunks.addAll(sourceContext);
                }
                enriched.add(new PreparedDocument(prepared.document(), reindexChunks(chunks)));
            }
            return enriched;
        } catch (RuntimeException ex) {
            return documents;
        }
    }

    private List<Chunk> reindexChunks(List<Chunk> chunks) {
        List<Chunk> reindexed = new ArrayList<>();
        for (Chunk chunk : chunks) {
            reindexed.add(new Chunk(reindexed.size(), chunk.content(), chunk.metadata()));
        }
        return reindexed;
    }

    private List<List<Double>> embedInBatches(List<String> texts) {
        List<List<Double>> embeddings = new ArrayList<>();
        int batchSize = 32;
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            embeddings.addAll(ollamaClient.embed(texts.subList(start, end)));
        }
        return embeddings;
    }

    private IngestResponse persistIndex(
            UUID sourceId,
            UUID spaceId,
            List<IndexedDocument> indexedDocuments,
            int pageCount,
            int skippedCount
    ) {
        UUID firstDocumentId = null;
        int documentCount = 0;
        int totalChunkCount = 0;

        for (IndexedDocument indexedDocument : indexedDocuments) {
            ExtractedDocument document = indexedDocument.document();
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

            repository.addChunks(documentId, indexedDocument.chunks(), indexedDocument.embeddings());
            documentCount++;
            totalChunkCount += indexedDocument.chunks().size();
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

    private void validateEmbeddings(List<List<Double>> embeddings, int expectedCount) {
        if (embeddings.size() != expectedCount) {
            throw new IllegalArgumentException("Embedding count mismatch. Expected "
                    + expectedCount + " but got " + embeddings.size() + ".");
        }
        validateEmbeddings(embeddings);
    }

    private void recordFailure(AppUser user, UUID sourceId, UUID spaceId, String action, RuntimeException ex) {
        String message = failureMessage(ex);
        try {
            repository.updateSourceStatus(sourceId, SourceStatus.FAILED, message);
            auditService.log(user, action, "SOURCE", sourceId, spaceId, message);
        } catch (RuntimeException recordEx) {
            ex.addSuppressed(recordEx);
        }
    }

    private String failureMessage(Throwable throwable) {
        String topMessage = throwable.getMessage();
        if (throwable instanceof IllegalArgumentException && topMessage != null && !topMessage.isBlank()) {
            return topMessage;
        }
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = topMessage;
        }
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private boolean isDocumentContext(Map<String, Object> metadata) {
        Object value = metadata == null ? null : metadata.get("kind");
        return "document_context".equals(value == null ? "" : String.valueOf(value));
    }

    private record WebDocuments(List<ExtractedDocument> documents, int pageCount, int skippedCount) {
    }

    private record IndexedDocument(ExtractedDocument document, List<Chunk> chunks, List<List<Double>> embeddings) {
    }

    private record PreparedDocument(ExtractedDocument document, List<Chunk> chunks) {
    }
}
