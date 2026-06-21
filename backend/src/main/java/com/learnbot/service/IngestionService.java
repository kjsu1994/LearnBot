package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.domain.SourceStatus;
import com.learnbot.domain.SourceType;
import com.learnbot.dto.DocumentSummary;
import com.learnbot.dto.DocumentDetail;
import com.learnbot.dto.DocumentIndexingJobSummary;
import com.learnbot.dto.IngestResponse;
import com.learnbot.repository.DocumentRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final DocumentTypeClassifier documentTypeClassifier;
    private final DocumentSchemaProfileService documentSchemaProfileService;
    private final EmbeddingService embeddingService;
    private final LearnBotProperties properties;
    private final AuthService authService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService executor;

    public IngestionService(
            DocumentRepository repository,
            WebPageExtractor webPageExtractor,
            WebCrawler webCrawler,
            FileExtractor fileExtractor,
            WebUrlNormalizer webUrlNormalizer,
            ObjectStorageService objectStorageService,
            Chunker chunker,
            DocumentContextBuilder documentContextBuilder,
            DocumentTypeClassifier documentTypeClassifier,
            DocumentSchemaProfileService documentSchemaProfileService,
            EmbeddingService embeddingService,
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
        this.documentTypeClassifier = documentTypeClassifier;
        this.documentSchemaProfileService = documentSchemaProfileService;
        this.embeddingService = embeddingService;
        this.properties = properties;
        this.authService = authService;
        this.auditService = auditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.executor = Executors.newFixedThreadPool(properties.getDocument().getIndexThreads());
    }

    @PostConstruct
    void resetInterruptedJobs() {
        repository.resetInterruptedDocumentJobs();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public IngestResponse ingestWeb(AppUser user, UUID spaceId, String url) {
        return ingestWeb(user, spaceId, url, false, null, null, null, null, null, null, null);
    }

    public IngestResponse ingestWeb(
            AppUser user,
            UUID spaceId,
            String url,
            Boolean recursive,
            Integer maxDepth,
            Integer maxPages,
            String crawlScope,
            String robotsFailurePolicy,
            Boolean includeAttachments,
            Boolean useSitemap,
            String renderMode
    ) {
        UUID resolvedSpaceId = authService.resolveSpace(user, spaceId);
        String normalizedUrl = webUrlNormalizer.normalize(url);
        CrawlOptions crawlOptions = crawlOptions(crawlScope, robotsFailurePolicy, includeAttachments, useSitemap, renderMode);
        UUID sourceId = repository.createSource(SourceType.WEB, normalizedUrl, normalizedUrl, resolvedSpaceId, user.id());
        UUID jobId = repository.createDocumentJob(sourceId, resolvedSpaceId, "INITIAL_INDEX");
        executor.submit(() -> runWebIndex(user, sourceId, resolvedSpaceId, normalizedUrl, Boolean.TRUE.equals(recursive), maxDepth, maxPages, crawlOptions, jobId, false));
        auditService.log(user, "DOCUMENT_INGEST_STARTED", "SOURCE", sourceId, resolvedSpaceId, "Web document indexing started.");
        return new IngestResponse(sourceId, null, resolvedSpaceId, 0, SourceStatus.INDEXING.name(), 0, 0, 0);
    }

    public IngestResponse ingestFile(AppUser user, UUID spaceId, MultipartFile file) {
        UUID resolvedSpaceId = authService.resolveSpace(user, spaceId);
        String name = file.getOriginalFilename() == null ? "uploaded-file" : file.getOriginalFilename();
        UUID sourceId = repository.createSource(SourceType.FILE, name, name, resolvedSpaceId, user.id());
        try {
            StoredObject storedObject = objectStorageService.store(sourceId, file);
            UUID jobId = repository.createDocumentJob(sourceId, resolvedSpaceId, "INITIAL_INDEX");
            executor.submit(() -> runFileIndex(user, sourceId, resolvedSpaceId, storedObject, jobId, false));
        } catch (RuntimeException ex) {
            recordFailure(user, sourceId, resolvedSpaceId, "DOCUMENT_INGEST_FAILED", ex);
            throw ex;
        }
        auditService.log(user, "DOCUMENT_INGEST_STARTED", "SOURCE", sourceId, resolvedSpaceId, "File document indexing started.");
        return new IngestResponse(sourceId, null, resolvedSpaceId, 0, SourceStatus.INDEXING.name(), 0, 1, 0);
    }

    public List<DocumentSummary> listDocuments(AppUser user, UUID spaceId) {
        UUID selectedSpaceId = spaceId == null ? null : authService.resolveSpace(user, spaceId);
        return repository.listDocuments(authService.accessibleSpaceIds(user), selectedSpaceId);
    }

    public List<DocumentIndexingJobSummary> listDocumentJobs(AppUser user, UUID spaceId) {
        UUID selectedSpaceId = spaceId == null ? null : authService.resolveSpace(user, spaceId);
        return repository.listDocumentJobs(authService.accessibleSpaceIds(user), selectedSpaceId);
    }

    public DocumentIndexingJobSummary getDocumentJob(AppUser user, UUID jobId) {
        return repository.findDocumentJob(jobId, authService.accessibleSpaceIds(user))
                .orElseThrow(() -> new IllegalArgumentException("Document indexing job was not found."));
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
        UUID jobId = repository.createDocumentJob(source.id(), summary.spaceId(), "REINDEX");
        executor.submit(() -> runReindex(user, source, summary.spaceId(), jobId));
        auditService.log(user, "DOCUMENT_REINDEX_STARTED", "SOURCE", source.id(), summary.spaceId(), "Document reindexing started.");
        return new IngestResponse(source.id(), documentId, summary.spaceId(), 0, SourceStatus.INDEXING.name(), 0, 0, 0);
    }

    private void runWebIndex(
            AppUser user,
            UUID sourceId,
            UUID spaceId,
            String normalizedUrl,
            boolean recursive,
            Integer maxDepth,
            Integer maxPages,
            CrawlOptions crawlOptions,
            UUID jobId,
            boolean allowReuse
    ) {
        try {
            WebDocuments documents = extractWebDocuments(sourceId, normalizedUrl, recursive, maxDepth, maxPages, crawlOptions);
            IngestResponse response = indexAll(sourceId, spaceId, documents.documents(), documents.pageCount(), documents.skippedCount(), jobId, allowReuse, recursive);
            repository.finishDocumentJob(jobId, "SUCCEEDED", null);
            auditService.log(user, allowReuse ? "DOCUMENT_REINDEXED" : "DOCUMENT_INGESTED", "DOCUMENT", response.documentId(), spaceId, "Web document was indexed.");
        } catch (RuntimeException ex) {
            repository.finishDocumentJob(jobId, "FAILED", failureMessage(ex));
            recordFailure(user, sourceId, spaceId, allowReuse ? "DOCUMENT_REINDEX_FAILED" : "DOCUMENT_INGEST_FAILED", ex);
        }
    }

    private void runFileIndex(
            AppUser user,
            UUID sourceId,
            UUID spaceId,
            StoredObject storedObject,
            UUID jobId,
            boolean allowReuse
    ) {
        try {
            ExtractedDocument document = fileExtractor.extract(objectStorageService.load(storedObject));
            IngestResponse response = indexStoredFile(sourceId, spaceId, storedObject, document, jobId, allowReuse);
            repository.finishDocumentJob(jobId, "SUCCEEDED", null);
            auditService.log(user, allowReuse ? "DOCUMENT_REINDEXED" : "DOCUMENT_INGESTED", "DOCUMENT", response.documentId(), spaceId, "File document was indexed.");
        } catch (RuntimeException ex) {
            repository.finishDocumentJob(jobId, "FAILED", failureMessage(ex));
            recordFailure(user, sourceId, spaceId, allowReuse ? "DOCUMENT_REINDEX_FAILED" : "DOCUMENT_INGEST_FAILED", ex);
        }
    }

    private void runReindex(AppUser user, StoredSource source, UUID spaceId, UUID jobId) {
        try {
            int previousDocumentCount = repository.countDocumentsForSource(source.id());
            switch (source.type()) {
                case WEB -> {
                    String normalizedUrl = webUrlNormalizer.normalize(source.location());
                    runWebIndex(user, source.id(), spaceId, normalizedUrl, previousDocumentCount > 1, null, null, defaultCrawlOptions(), jobId, true);
                }
                case FILE -> {
                    StoredObject object = repository.findSourceObject(source.id())
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "Original file is not stored. Upload the file again before reindexing."
                            ));
                    runFileIndex(user, source.id(), spaceId, object, jobId, true);
                }
            }
        } catch (RuntimeException ex) {
            repository.finishDocumentJob(jobId, "FAILED", failureMessage(ex));
            recordFailure(user, source.id(), spaceId, "DOCUMENT_REINDEX_FAILED", ex);
        }
    }

    private IngestResponse indexAll(
            UUID sourceId,
            UUID spaceId,
            List<ExtractedDocument> documents,
            int pageCount,
            int skippedCount,
            UUID jobId,
            boolean allowReuse,
            boolean recursiveWeb
    ) {
        List<IndexedDocument> indexedDocuments = prepareIndex(sourceId, documents, allowReuse, recursiveWeb);
        return transactionTemplate.execute(status ->
                persistIndex(sourceId, spaceId, indexedDocuments, pageCount, skippedCount, jobId, allowReuse)
        );
    }

    private IngestResponse indexStoredFile(UUID sourceId, UUID spaceId, StoredObject storedObject, ExtractedDocument document, UUID jobId, boolean allowReuse) {
        List<IndexedDocument> indexedDocuments = prepareIndex(sourceId, List.of(document), allowReuse, false);
        return transactionTemplate.execute(status -> {
            repository.createSourceObject(sourceId, storedObject);
            return persistIndex(sourceId, spaceId, indexedDocuments, 1, 0, jobId, allowReuse);
        });
    }

    private List<IndexedDocument> prepareIndex(UUID sourceId, List<ExtractedDocument> documents, boolean allowReuse, boolean recursiveWeb) {
        List<PreparedDocument> preparedDocuments = new ArrayList<>();
        for (ExtractedDocument rawDocument : documents) {
            ExtractedDocument document = withGraphProfileMetadata(rawDocument);
            List<Chunk> chunks = withDocumentGraphMetadata(chunker.split(document), document);
            if (chunks.isEmpty()) {
                continue;
            }
            preparedDocuments.add(new PreparedDocument(document, chunks));
        }

        if (preparedDocuments.isEmpty()) {
            throw new IllegalArgumentException("No extractable text was found.");
        }

        List<PreparedDocument> enrichedDocuments = enrichWithDocumentContext(preparedDocuments, recursiveWeb);
        List<IndexedDocument> indexedDocuments = new ArrayList<>();
        for (PreparedDocument preparedDocument : enrichedDocuments) {
            List<Chunk> chunks = preparedDocument.chunks();
            String contentHash = contentHash(preparedDocument.document());
            UUID reusableDocumentId = null;
            int reusableChunkCount = 0;
            List<Chunk> chunksToEmbed = chunks;
            if (allowReuse) {
                var reusable = repository.findReusableDocument(sourceId, preparedDocument.document().sourceUri(), contentHash);
                if (reusable.isPresent()) {
                    reusableDocumentId = reusable.get().documentId();
                    List<Chunk> contextChunks = chunks.stream()
                            .filter(chunk -> isDocumentContext(chunk.metadata()))
                            .toList();
                    chunksToEmbed = contextChunks;
                    reusableChunkCount = reusable.get().chunkCount();
                }
            }
            List<List<Double>> embeddings = chunksToEmbed.isEmpty()
                    ? List.of()
                    : embeddingService.embed(chunksToEmbed.stream().map(Chunk::content).toList());
            indexedDocuments.add(new IndexedDocument(preparedDocument.document(), contentHash, chunksToEmbed, embeddings, reusableDocumentId, reusableChunkCount));
        }

        return indexedDocuments;
    }

    private ExtractedDocument withGraphProfileMetadata(ExtractedDocument document) {
        try {
            DocumentTypeClassifier.Classification classification = documentTypeClassifier.classify(document);
            String documentType = classification.documentType() == null || classification.documentType().isBlank()
                    ? DocumentSchemaProfileService.GENERAL_DOCUMENT
                    : classification.documentType();
            String schemaName = documentSchemaProfileService.schemaNameFor(documentType);
            Map<String, Object> metadata = new LinkedHashMap<>(document.metadata() == null ? Map.of() : document.metadata());
            metadata.putIfAbsent("documentType", documentType);
            metadata.putIfAbsent("documentTypeConfidence", classification.confidence());
            metadata.putIfAbsent("schemaName", schemaName);
            return new ExtractedDocument(document.title(), document.sourceUri(), document.contentType(), document.content(), metadata);
        } catch (RuntimeException ex) {
            Map<String, Object> metadata = new LinkedHashMap<>(document.metadata() == null ? Map.of() : document.metadata());
            metadata.putIfAbsent("documentType", DocumentSchemaProfileService.GENERAL_DOCUMENT);
            metadata.putIfAbsent("schemaName", DocumentSchemaProfileService.CORE_SCHEMA);
            metadata.putIfAbsent("documentTypeConfidence", 0.0);
            return new ExtractedDocument(document.title(), document.sourceUri(), document.contentType(), document.content(), metadata);
        }
    }

    private List<Chunk> withDocumentGraphMetadata(List<Chunk> chunks, ExtractedDocument document) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        Map<String, Object> documentMetadata = document.metadata() == null ? Map.of() : document.metadata();
        List<Chunk> enriched = new ArrayList<>();
        for (Chunk chunk : chunks) {
            Map<String, Object> metadata = new LinkedHashMap<>(chunk.metadata() == null ? Map.of() : chunk.metadata());
            copyMetadata(documentMetadata, metadata, "documentType");
            copyMetadata(documentMetadata, metadata, "documentTypeConfidence");
            copyMetadata(documentMetadata, metadata, "schemaName");
            enriched.add(new Chunk(chunk.index(), chunk.content(), metadata));
        }
        return enriched;
    }

    private void copyMetadata(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source == null ? null : source.get(key);
        if (value != null) {
            target.putIfAbsent(key, value);
        }
    }

    private List<PreparedDocument> enrichWithDocumentContext(List<PreparedDocument> documents, boolean recursiveWeb) {
        if (!documentContextBuilder.enabled()) {
            return documents;
        }
        try {
            List<DocumentContextBuilder.DocumentContextInput> inputs = documents.stream()
                    .map(document -> new DocumentContextBuilder.DocumentContextInput(document.document(), document.chunks()))
                    .toList();
            List<Chunk> sourceContext = documentContextBuilder.buildSourceContext(inputs, recursiveWeb);
            List<PreparedDocument> enriched = new ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                PreparedDocument prepared = documents.get(i);
                List<Chunk> chunks = new ArrayList<>(prepared.chunks());
                chunks.addAll(documentContextBuilder.buildDocumentContext(prepared.document(), prepared.chunks(), recursiveWeb));
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

    private IngestResponse persistIndex(
            UUID sourceId,
            UUID spaceId,
            List<IndexedDocument> indexedDocuments,
            int pageCount,
            int skippedCount,
            UUID jobId,
            boolean replaceExisting
    ) {
        UUID firstDocumentId = null;
        int documentCount = 0;
        int totalChunkCount = 0;
        int reusedChunkCount = 0;
        int embeddedChunkCount = 0;
        List<UUID> newDocumentIds = new ArrayList<>();

        for (IndexedDocument indexedDocument : indexedDocuments) {
            ExtractedDocument document = indexedDocument.document();
            UUID documentId = repository.createDocument(
                    sourceId,
                    document.title(),
                    document.sourceUri(),
                    document.contentType(),
                    document.metadata(),
                    indexedDocument.contentHash()
            );
            newDocumentIds.add(documentId);
            if (firstDocumentId == null) {
                firstDocumentId = documentId;
            }

            int documentReusedChunks = 0;
            if (indexedDocument.reusableDocumentId() != null) {
                documentReusedChunks = repository.copyReusableDocumentChunks(indexedDocument.reusableDocumentId(), documentId);
                reusedChunkCount += documentReusedChunks;
            }
            if (!indexedDocument.chunks().isEmpty()) {
                repository.addChunks(documentId, indexedDocument.chunks(), indexedDocument.embeddings());
                embeddedChunkCount += indexedDocument.chunks().size();
            }
            documentCount++;
            totalChunkCount += documentReusedChunks + indexedDocument.chunks().size();
            if (jobId != null) {
                repository.updateDocumentJobProgress(
                        jobId,
                        indexedDocuments.size(),
                        documentCount,
                        totalChunkCount,
                        reusedChunkCount,
                        embeddedChunkCount
                );
            }
        }
        if (replaceExisting) {
            repository.deleteDocumentsForSourceExcept(sourceId, newDocumentIds);
        }
        if (properties.getDocument().getGraph().isEnabled()) {
            try {
                repository.rebuildDocumentGraph(sourceId);
            } catch (RuntimeException ignored) {
                // Document graph is retrieval enrichment only; indexing remains valid without it.
            }
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
            Integer maxPages,
            CrawlOptions crawlOptions
    ) {
        CrawlOptions safeOptions = crawlOptions == null ? defaultCrawlOptions() : crawlOptions.normalized();
        if (!recursive) {
            return new WebDocuments(List.of(webPageExtractor.extract(sourceId, url, safeOptions)), 1, 0);
        }
        WebCrawler.CrawlResult result = webCrawler.crawl(
                sourceId,
                url,
                maxDepth == null ? properties.getCrawler().getMaxDepth() : maxDepth,
                maxPages == null ? properties.getCrawler().getMaxPagesPerRequest() : maxPages,
                safeOptions
        );
        return new WebDocuments(result.documents(), result.fetchedCount(), result.skippedCount());
    }

    private CrawlOptions crawlOptions(
            String crawlScope,
            String robotsFailurePolicy,
            Boolean includeAttachments,
            Boolean useSitemap,
            String renderMode
    ) {
        CrawlOptions defaults = defaultCrawlOptions();
        return new CrawlOptions(
                crawlScope == null || crawlScope.isBlank() ? defaults.scope() : CrawlScope.from(crawlScope),
                robotsFailurePolicy == null || robotsFailurePolicy.isBlank() ? defaults.robotsFailurePolicy() : RobotsFailurePolicy.from(robotsFailurePolicy),
                includeAttachments == null ? defaults.includeAttachments() : includeAttachments,
                useSitemap == null ? defaults.useSitemap() : useSitemap,
                renderMode == null || renderMode.isBlank() ? defaults.renderMode() : WebRenderMode.from(renderMode)
        ).normalized();
    }

    private CrawlOptions defaultCrawlOptions() {
        return new CrawlOptions(
                CrawlScope.from(properties.getCrawler().getCrawlScope()),
                RobotsFailurePolicy.from(properties.getCrawler().getRobotsFailurePolicy()),
                properties.getCrawler().isIncludeAttachments(),
                properties.getCrawler().isUseSitemap(),
                WebRenderMode.from(properties.getCrawler().getRenderMode())
        ).normalized();
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

    private String contentHash(ExtractedDocument document) {
        String value = safe(document.sourceUri()) + "\n"
                + safe(document.contentType()) + "\n"
                + normalizeContent(document.content());
        return sha256(value);
    }

    private String normalizeContent(String content) {
        return safe(content)
                .replace("\u0000", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }

    private record WebDocuments(List<ExtractedDocument> documents, int pageCount, int skippedCount) {
    }

    private record IndexedDocument(
            ExtractedDocument document,
            String contentHash,
            List<Chunk> chunks,
            List<List<Double>> embeddings,
            UUID reusableDocumentId,
            int reusableChunkCount
    ) {
    }

    private record PreparedDocument(ExtractedDocument document, List<Chunk> chunks) {
    }
}
