package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.domain.SourceStatus;
import com.learnbot.domain.SourceType;
import com.learnbot.dto.DocumentSummary;
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

    public IngestionService(
            DocumentRepository repository,
            WebPageExtractor webPageExtractor,
            FileExtractor fileExtractor,
            ObjectStorageService objectStorageService,
            Chunker chunker,
            OllamaClient ollamaClient,
            LearnBotProperties properties
    ) {
        this.repository = repository;
        this.webPageExtractor = webPageExtractor;
        this.fileExtractor = fileExtractor;
        this.objectStorageService = objectStorageService;
        this.chunker = chunker;
        this.ollamaClient = ollamaClient;
        this.properties = properties;
    }

    @Transactional
    public IngestResponse ingestWeb(String url) {
        UUID sourceId = repository.createSource(SourceType.WEB, url, url);
        try {
            ExtractedDocument document = webPageExtractor.extract(sourceId, url);
            return index(sourceId, document);
        } catch (RuntimeException ex) {
            repository.updateSourceStatus(sourceId, SourceStatus.FAILED, ex.getMessage());
            throw ex;
        }
    }

    @Transactional
    public IngestResponse ingestFile(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "uploaded-file" : file.getOriginalFilename();
        UUID sourceId = repository.createSource(SourceType.FILE, name, name);
        try {
            StoredObject storedObject = objectStorageService.store(sourceId, file);
            repository.createSourceObject(sourceId, storedObject);
            ExtractedDocument document = fileExtractor.extract(file);
            return index(sourceId, document);
        } catch (RuntimeException ex) {
            repository.updateSourceStatus(sourceId, SourceStatus.FAILED, ex.getMessage());
            throw ex;
        }
    }

    public List<DocumentSummary> listDocuments() {
        return repository.listDocuments();
    }

    @Transactional
    public void deleteDocument(UUID documentId) {
        StoredSource source = repository.findSourceByDocumentId(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document was not found."));
        for (StoredObject object : repository.listSourceObjects(source.id())) {
            objectStorageService.delete(object);
        }
        repository.deleteSource(source.id());
    }

    @Transactional
    public IngestResponse reindexDocument(UUID documentId) {
        StoredSource source = repository.findSourceByDocumentId(documentId)
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
            return index(source.id(), document);
        } catch (RuntimeException ex) {
            repository.updateSourceStatus(source.id(), SourceStatus.FAILED, ex.getMessage());
            throw ex;
        }
    }

    private IngestResponse index(UUID sourceId, ExtractedDocument document) {
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
        return new IngestResponse(sourceId, documentId, chunks.size(), SourceStatus.INDEXED.name());
    }
}
