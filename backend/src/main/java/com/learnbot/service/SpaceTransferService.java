package com.learnbot.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.SpaceExportResponse;
import com.learnbot.dto.SpaceImportResponse;
import com.learnbot.dto.SpaceTransferCounts;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

@Service
public class SpaceTransferService {
    private static final String ARCHIVE_VERSION = "learnbot-rag-space-v1";
    private static final String MANIFEST_ENTRY = "manifest.json";
    private static final String DOCUMENTS_ENTRY = "documents.jsonl";
    private static final String CODE_REPOSITORIES_ENTRY = "code-repositories.jsonl";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final LearnBotProperties properties;
    private final ObjectStorageService objectStorageService;
    private final AuditService auditService;
    private final AuthService authService;

    public SpaceTransferService(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            LearnBotProperties properties,
            ObjectStorageService objectStorageService,
            AuditService auditService,
            AuthService authService
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.objectStorageService = objectStorageService;
        this.auditService = auditService;
        this.authService = authService;
    }

    public SpaceExportResponse exportSpace(AppUser actor, UUID spaceId) {
        authService.requireAdmin(actor);
        authService.requireSpace(actor, spaceId);

        SpaceInfo space = findSpace(spaceId);
        SpaceTransferCounts counts = countExportable(spaceId);
        Path exportDir = exportDir();
        try {
            Files.createDirectories(exportDir);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not create export directory.", ex);
        }

        String fileName = exportFileName(space.name());
        Path target = exportDir.resolve(fileName).normalize();
        ManifestArchive manifest = new ManifestArchive(
                ARCHIVE_VERSION,
                OffsetDateTime.now(),
                new SpaceArchiveInfo(space.id().toString(), space.name(), space.description()),
                new EmbeddingArchive(properties.getOllama().getEmbeddingModel(), properties.getEmbedding().getDimensions()),
                counts
        );

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target), StandardCharsets.UTF_8)) {
            writeJsonEntry(zip, MANIFEST_ENTRY, manifest);
            writeDocuments(zip, spaceId);
            writeCodeRepositories(zip, spaceId);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not export workspace data.", ex);
        }

        long sizeBytes;
        try {
            sizeBytes = Files.size(target);
        } catch (IOException ex) {
            sizeBytes = 0L;
        }
        SpaceExportResponse response = new SpaceExportResponse(fileName, "./export/" + fileName, sizeBytes, counts);
        auditService.log(
                actor,
                "SPACE_RAG_EXPORT",
                "SPACE",
                spaceId.toString(),
                spaceId,
                "Workspace RAG data was exported.",
                Map.of(
                        "fileName", fileName,
                        "relativePath", response.relativePath(),
                        "sizeBytes", sizeBytes,
                        "counts", counts,
                        "embeddingModel", properties.getOllama().getEmbeddingModel(),
                        "embeddingDimensions", properties.getEmbedding().getDimensions()
                )
        );
        return response;
    }

    public Resource exportFile(String fileName) {
        Path file = safeExportFile(fileName);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Export file was not found.");
        }
        return new FileSystemResource(file);
    }

    @Transactional
    public SpaceImportResponse importSpace(AppUser actor, UUID spaceId, MultipartFile file) {
        authService.requireAdmin(actor);
        authService.requireSpace(actor, spaceId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Import ZIP file is required.");
        }

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("learnbot-rag-import-", ".zip");
            file.transferTo(tempFile);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not read import file.", ex);
        }

        List<StoredObject> importedObjects = new ArrayList<>();
        try (ZipFile zip = new ZipFile(tempFile.toFile(), StandardCharsets.UTF_8)) {
            ManifestArchive manifest = readManifest(zip);
            validateManifest(manifest);

            ImportAccumulator accumulator = new ImportAccumulator();
            importDocuments(zip, spaceId, actor.id(), importedObjects, accumulator);
            importCodeRepositories(zip, spaceId, actor.id(), accumulator);

            SpaceImportResponse response = new SpaceImportResponse(
                    accumulator.imported,
                    accumulator.skipped,
                    "Import completed."
            );
            auditService.log(
                    actor,
                    "SPACE_RAG_IMPORT",
                    "SPACE",
                    spaceId.toString(),
                    spaceId,
                    "Workspace RAG data was imported.",
                    Map.of(
                            "fileName", safeOriginalFilename(file.getOriginalFilename()),
                            "imported", response.imported(),
                            "skipped", response.skipped(),
                            "archiveVersion", manifest.archiveVersion(),
                            "embeddingModel", manifest.embedding().model(),
                            "embeddingDimensions", manifest.embedding().dimensions()
                    )
            );
            return response;
        } catch (RuntimeException | IOException ex) {
            for (StoredObject object : importedObjects) {
                try {
                    objectStorageService.delete(object);
                } catch (RuntimeException ignored) {
                    // Best-effort cleanup for objects written outside the DB transaction.
                }
            }
            if (ex instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalArgumentException("Could not import workspace data.", ex);
        } finally {
            try {
                if (tempFile != null) {
                    Files.deleteIfExists(tempFile);
                }
            } catch (IOException ignored) {
            }
        }
    }

    private void writeDocuments(ZipOutputStream zip, UUID spaceId) throws IOException {
        List<ObjectExportPayload> objects = new ArrayList<>();
        zip.putNextEntry(new ZipEntry(DOCUMENTS_ENTRY));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
        for (SourceRow source : documentSources(spaceId)) {
            StoredObject object = sourceObject(source.id());
            ObjectArchive objectArchive = null;
            if (object != null) {
                String objectEntry = "objects/" + source.id() + "/" + sanitizeFilePart(object.originalFilename());
                objectArchive = new ObjectArchive(objectEntry, object.originalFilename(), object.contentType(), object.sizeBytes());
                objects.add(new ObjectExportPayload(objectEntry, object));
            }
            SourceArchive archive = new SourceArchive(
                    source.id().toString(),
                    source.type(),
                    source.name(),
                    source.location(),
                    objectArchive,
                    documentsForSource(source.id())
            );
            writer.write(objectMapper.writeValueAsString(archive));
            writer.newLine();
        }
        writer.flush();
        zip.closeEntry();

        for (ObjectExportPayload object : objects) {
            StoredFile storedFile = objectStorageService.load(object.object());
            zip.putNextEntry(new ZipEntry(object.entry()));
            zip.write(storedFile.content());
            zip.closeEntry();
        }
    }

    private void writeCodeRepositories(ZipOutputStream zip, UUID spaceId) throws IOException {
        zip.putNextEntry(new ZipEntry(CODE_REPOSITORIES_ENTRY));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
        for (CodeRepositoryArchive archive : codeRepositories(spaceId)) {
            writer.write(objectMapper.writeValueAsString(archive));
            writer.newLine();
        }
        writer.flush();
        zip.closeEntry();
    }

    private void writeJsonEntry(ZipOutputStream zip, String entryName, Object value) throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        zip.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
        zip.closeEntry();
    }

    private void importDocuments(
            ZipFile zip,
            UUID spaceId,
            UUID actorId,
            List<StoredObject> importedObjects,
            ImportAccumulator accumulator
    ) throws IOException {
        ZipEntry entry = zip.getEntry(DOCUMENTS_ENTRY);
        if (entry == null) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                SourceArchive source = objectMapper.readValue(line, SourceArchive.class);
                SpaceTransferCounts sourceCounts = sourceCounts(source);
                if (documentSourceExists(spaceId, source.type(), source.location())) {
                    accumulator.skip(sourceCounts);
                    continue;
                }
                UUID newSourceId = UUID.randomUUID();
                insertSource(newSourceId, spaceId, actorId, source);
                if (source.object() != null) {
                    ZipEntry objectEntry = zip.getEntry(source.object().entry());
                    if (objectEntry == null) {
                        throw new IllegalArgumentException("Import archive is missing an original source file.");
                    }
                    byte[] content;
                    try (var stream = zip.getInputStream(objectEntry)) {
                        content = stream.readAllBytes();
                    }
                    StoredObject storedObject = objectStorageService.storeBytes(
                            newSourceId,
                            source.object().originalFilename(),
                            source.object().contentType(),
                            content
                    );
                    importedObjects.add(storedObject);
                    insertSourceObject(newSourceId, storedObject);
                }
                for (DocumentArchive document : source.documents()) {
                    UUID newDocumentId = UUID.randomUUID();
                    insertDocument(newDocumentId, newSourceId, document);
                    for (DocumentChunkArchive chunk : document.chunks()) {
                        validateVector(chunk.embedding());
                        insertDocumentChunk(newDocumentId, chunk);
                    }
                }
                accumulator.imported(sourceCounts);
            }
        }
    }

    private void importCodeRepositories(ZipFile zip, UUID spaceId, UUID actorId, ImportAccumulator accumulator) throws IOException {
        ZipEntry entry = zip.getEntry(CODE_REPOSITORIES_ENTRY);
        if (entry == null) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                CodeRepositoryArchive repository = objectMapper.readValue(line, CodeRepositoryArchive.class);
                SpaceTransferCounts repositoryCounts = repositoryCounts(repository);
                if (codeRepositoryExists(spaceId, repository.gitUrl(), repository.branch(), repository.lastIndexedCommit())) {
                    accumulator.skip(repositoryCounts);
                    continue;
                }
                UUID newRepositoryId = UUID.randomUUID();
                UUID indexVersion = UUID.randomUUID();
                insertCodeRepository(newRepositoryId, spaceId, actorId, repository);
                insertIndexingJob(indexVersion, newRepositoryId, repository);
                for (CodeFileArchive file : repository.files()) {
                    UUID newFileId = UUID.randomUUID();
                    insertCodeFile(newFileId, newRepositoryId, indexVersion, file);
                    for (CodeChunkArchive chunk : file.chunks()) {
                        validateVector(chunk.embedding());
                        insertCodeChunk(newRepositoryId, newFileId, indexVersion, file.filePath(), chunk);
                    }
                }
                accumulator.imported(repositoryCounts);
            }
        }
    }

    private ManifestArchive readManifest(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry(MANIFEST_ENTRY);
        if (entry == null) {
            throw new IllegalArgumentException("Import archive is missing manifest.json.");
        }
        return objectMapper.readValue(zip.getInputStream(entry), ManifestArchive.class);
    }

    private void validateManifest(ManifestArchive manifest) {
        if (manifest == null || !ARCHIVE_VERSION.equals(manifest.archiveVersion())) {
            throw new IllegalArgumentException("Unsupported import archive version.");
        }
        if (manifest.embedding() == null) {
            throw new IllegalArgumentException("Import archive is missing embedding metadata.");
        }
        String currentModel = properties.getOllama().getEmbeddingModel();
        int currentDimensions = properties.getEmbedding().getDimensions();
        if (!currentModel.equals(manifest.embedding().model()) || currentDimensions != manifest.embedding().dimensions()) {
            throw new IllegalArgumentException("Embedding model or dimension does not match current server settings.");
        }
    }

    private SpaceInfo findSpace(UUID spaceId) {
        return jdbc.query("""
                SELECT id, name, description
                FROM spaces
                WHERE id = :spaceId AND deleted_at IS NULL
                """, new MapSqlParameterSource().addValue("spaceId", spaceId), (rs, rowNum) -> new SpaceInfo(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("description")
        )).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Workspace was not found."));
    }

    private SpaceTransferCounts countExportable(UUID spaceId) {
        return jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*)
                     FROM documents d
                     JOIN data_sources s ON s.id = d.source_id
                     WHERE s.space_id = :spaceId AND s.deleted_at IS NULL AND s.status IN ('SEARCHABLE', 'READY', 'PARTIAL', 'INDEXED')) AS documents,
                    (SELECT COUNT(*)
                     FROM document_chunks c
                     JOIN documents d ON d.id = c.document_id
                     JOIN data_sources s ON s.id = d.source_id
                     WHERE s.space_id = :spaceId AND s.deleted_at IS NULL AND s.status IN ('SEARCHABLE', 'READY', 'PARTIAL', 'INDEXED')) AS document_chunks,
                    (SELECT COUNT(*)
                     FROM source_objects o
                     JOIN data_sources s ON s.id = o.source_id
                     WHERE s.space_id = :spaceId AND s.deleted_at IS NULL AND s.status IN ('SEARCHABLE', 'READY', 'PARTIAL', 'INDEXED')) AS source_objects,
                    (SELECT COUNT(*)
                     FROM code_repositories r
                     WHERE r.space_id = :spaceId AND r.deleted_at IS NULL AND r.status = 'INDEXED') AS code_repositories,
                    (SELECT COUNT(*)
                     FROM code_files f
                     JOIN code_repositories r ON r.id = f.repository_id
                     WHERE r.space_id = :spaceId AND r.deleted_at IS NULL AND r.status = 'INDEXED' AND f.active) AS code_files,
                    (SELECT COUNT(*)
                     FROM code_chunks c
                     JOIN code_repositories r ON r.id = c.repository_id
                     WHERE r.space_id = :spaceId AND r.deleted_at IS NULL AND r.status = 'INDEXED' AND c.active) AS code_chunks
                """, new MapSqlParameterSource().addValue("spaceId", spaceId), (rs, rowNum) -> new SpaceTransferCounts(
                rs.getInt("documents"),
                rs.getInt("document_chunks"),
                rs.getInt("source_objects"),
                rs.getInt("code_repositories"),
                rs.getInt("code_files"),
                rs.getInt("code_chunks")
        ));
    }

    private List<SourceRow> documentSources(UUID spaceId) {
        return jdbc.query("""
                SELECT id, type, name, location
                FROM data_sources
                WHERE space_id = :spaceId
                  AND deleted_at IS NULL
                  AND status IN ('SEARCHABLE', 'READY', 'PARTIAL', 'INDEXED')
                  AND EXISTS (SELECT 1 FROM documents d WHERE d.source_id = data_sources.id)
                ORDER BY created_at ASC
                """, new MapSqlParameterSource().addValue("spaceId", spaceId), (rs, rowNum) -> new SourceRow(
                rs.getObject("id", UUID.class),
                rs.getString("type"),
                rs.getString("name"),
                rs.getString("location")
        ));
    }

    private StoredObject sourceObject(UUID sourceId) {
        return jdbc.query("""
                SELECT bucket, object_key, original_filename, content_type, size_bytes
                FROM source_objects
                WHERE source_id = :sourceId
                """, new MapSqlParameterSource().addValue("sourceId", sourceId), (rs, rowNum) -> new StoredObject(
                rs.getString("bucket"),
                rs.getString("object_key"),
                rs.getString("original_filename"),
                rs.getString("content_type"),
                rs.getLong("size_bytes")
        )).stream().findFirst().orElse(null);
    }

    private List<DocumentArchive> documentsForSource(UUID sourceId) {
        return jdbc.query("""
                SELECT id, title, source_uri, content_type, metadata::text AS metadata
                FROM documents
                WHERE source_id = :sourceId
                ORDER BY created_at ASC
                """, new MapSqlParameterSource().addValue("sourceId", sourceId), (rs, rowNum) -> new DocumentArchive(
                rs.getObject("id", UUID.class).toString(),
                rs.getString("title"),
                rs.getString("source_uri"),
                rs.getString("content_type"),
                fromJson(rs.getString("metadata")),
                documentChunks(rs.getObject("id", UUID.class))
        ));
    }

    private List<DocumentChunkArchive> documentChunks(UUID documentId) {
        return jdbc.query("""
                SELECT chunk_index, content, metadata::text AS metadata, embedding::text AS embedding
                FROM document_chunks
                WHERE document_id = :documentId
                ORDER BY chunk_index ASC
                """, new MapSqlParameterSource().addValue("documentId", documentId), (rs, rowNum) -> new DocumentChunkArchive(
                rs.getInt("chunk_index"),
                rs.getString("content"),
                fromJson(rs.getString("metadata")),
                rs.getString("embedding")
        ));
    }

    private List<CodeRepositoryArchive> codeRepositories(UUID spaceId) {
        return jdbc.query("""
                SELECT id, name, git_url, branch, last_indexed_commit
                FROM code_repositories
                WHERE space_id = :spaceId
                  AND deleted_at IS NULL
                  AND status IN ('SEARCHABLE', 'READY', 'PARTIAL', 'INDEXED')
                  AND EXISTS (SELECT 1 FROM code_files f WHERE f.repository_id = code_repositories.id AND f.active)
                ORDER BY created_at ASC
                """, new MapSqlParameterSource().addValue("spaceId", spaceId), (rs, rowNum) -> {
            UUID repositoryId = rs.getObject("id", UUID.class);
            return new CodeRepositoryArchive(
                    repositoryId.toString(),
                    rs.getString("name"),
                    rs.getString("git_url"),
                    rs.getString("branch"),
                    rs.getString("last_indexed_commit"),
                    activeFiles(repositoryId)
            );
        });
    }

    private List<CodeFileArchive> activeFiles(UUID repositoryId) {
        return jdbc.query("""
                SELECT id, file_path, language, content_hash
                FROM code_files
                WHERE repository_id = :repositoryId AND active
                ORDER BY file_path ASC
                """, new MapSqlParameterSource().addValue("repositoryId", repositoryId), (rs, rowNum) -> {
            UUID fileId = rs.getObject("id", UUID.class);
            return new CodeFileArchive(
                    fileId.toString(),
                    rs.getString("file_path"),
                    rs.getString("language"),
                    rs.getString("content_hash"),
                    activeCodeChunks(fileId)
            );
        });
    }

    private List<CodeChunkArchive> activeCodeChunks(UUID fileId) {
        return jdbc.query("""
                SELECT chunk_index, chunk_type, symbol_name, class_name, method_name, namespace_name,
                       control_name, event_name, line_start, line_end, content, metadata::text AS metadata,
                       embedding::text AS embedding
                FROM code_chunks
                WHERE file_id = :fileId AND active
                ORDER BY chunk_index ASC
                """, new MapSqlParameterSource().addValue("fileId", fileId), (rs, rowNum) -> new CodeChunkArchive(
                rs.getInt("chunk_index"),
                rs.getString("chunk_type"),
                rs.getString("symbol_name"),
                rs.getString("class_name"),
                rs.getString("method_name"),
                rs.getString("namespace_name"),
                rs.getString("control_name"),
                rs.getString("event_name"),
                rs.getInt("line_start"),
                rs.getInt("line_end"),
                rs.getString("content"),
                fromJson(rs.getString("metadata")),
                rs.getString("embedding")
        ));
    }

    private boolean documentSourceExists(UUID spaceId, String type, String location) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM data_sources
                WHERE space_id = :spaceId
                  AND deleted_at IS NULL
                  AND type = :type
                  AND location = :location
                """, new MapSqlParameterSource()
                .addValue("spaceId", spaceId)
                .addValue("type", type)
                .addValue("location", location), Integer.class);
        return count != null && count > 0;
    }

    private boolean codeRepositoryExists(UUID spaceId, String gitUrl, String branch, String lastIndexedCommit) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM code_repositories
                WHERE space_id = :spaceId
                  AND deleted_at IS NULL
                  AND lower(git_url) = lower(:gitUrl)
                  AND branch = :branch
                  AND COALESCE(last_indexed_commit, '') = COALESCE(:lastIndexedCommit, '')
                """, new MapSqlParameterSource()
                .addValue("spaceId", spaceId)
                .addValue("gitUrl", gitUrl)
                .addValue("branch", branch)
                .addValue("lastIndexedCommit", lastIndexedCommit), Integer.class);
        return count != null && count > 0;
    }

    private void insertSource(UUID sourceId, UUID spaceId, UUID actorId, SourceArchive source) {
        jdbc.update("""
                INSERT INTO data_sources (id, type, name, location, status, space_id, created_by, created_at, updated_at)
                VALUES (:id, :type, :name, :location, 'READY', :spaceId, :createdBy, now(), now())
                """, new MapSqlParameterSource()
                .addValue("id", sourceId)
                .addValue("type", source.type())
                .addValue("name", source.name())
                .addValue("location", source.location())
                .addValue("spaceId", spaceId)
                .addValue("createdBy", actorId));
    }

    private void insertSourceObject(UUID sourceId, StoredObject object) {
        jdbc.update("""
                INSERT INTO source_objects (id, source_id, bucket, object_key, original_filename, content_type, size_bytes)
                VALUES (:id, :sourceId, :bucket, :objectKey, :originalFilename, :contentType, :sizeBytes)
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("sourceId", sourceId)
                .addValue("bucket", object.bucket())
                .addValue("objectKey", object.objectKey())
                .addValue("originalFilename", object.originalFilename())
                .addValue("contentType", object.contentType())
                .addValue("sizeBytes", object.sizeBytes()));
    }

    private void insertDocument(UUID documentId, UUID sourceId, DocumentArchive document) {
        jdbc.update("""
                INSERT INTO documents (id, source_id, title, source_uri, content_type, metadata)
                VALUES (:id, :sourceId, :title, :sourceUri, :contentType, CAST(:metadata AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("id", documentId)
                .addValue("sourceId", sourceId)
                .addValue("title", document.title())
                .addValue("sourceUri", document.sourceUri())
                .addValue("contentType", document.contentType())
                .addValue("metadata", toJson(document.metadata())));
    }

    private void insertDocumentChunk(UUID documentId, DocumentChunkArchive chunk) {
        jdbc.update("""
                INSERT INTO document_chunks (id, document_id, chunk_index, content, metadata, embedding)
                VALUES (:id, :documentId, :chunkIndex, :content, CAST(:metadata AS jsonb), CAST(:embedding AS vector))
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("documentId", documentId)
                .addValue("chunkIndex", chunk.chunkIndex())
                .addValue("content", chunk.content())
                .addValue("metadata", toJson(chunk.metadata()))
                .addValue("embedding", chunk.embedding()));
    }

    private void insertCodeRepository(UUID repositoryId, UUID spaceId, UUID actorId, CodeRepositoryArchive repository) {
        jdbc.update("""
                INSERT INTO code_repositories (
                    id, name, source_type, source_label, git_url, branch, auth_type, local_path, status, last_indexed_commit, space_id, created_by, created_at, updated_at
                )
                VALUES (
                    :id, :name, 'GIT', :gitUrl, :gitUrl, :branch, 'NONE', :localPath, 'INDEXED', :lastIndexedCommit, :spaceId, :createdBy, now(), now()
                )
                """, new MapSqlParameterSource()
                .addValue("id", repositoryId)
                .addValue("name", repository.name())
                .addValue("gitUrl", repository.gitUrl())
                .addValue("branch", repository.branch())
                .addValue("localPath", "imported://" + repositoryId)
                .addValue("lastIndexedCommit", repository.lastIndexedCommit())
                .addValue("spaceId", spaceId)
                .addValue("createdBy", actorId));
    }

    private void insertIndexingJob(UUID jobId, UUID repositoryId, CodeRepositoryArchive repository) {
        SpaceTransferCounts counts = repositoryCounts(repository);
        jdbc.update("""
                INSERT INTO indexing_jobs (
                    id, repository_id, job_type, status, total_files, processed_files, total_chunks,
                    failed_files, commit_hash, started_at, finished_at, created_at
                )
                VALUES (
                    :id, :repositoryId, 'IMPORT', 'SUCCEEDED', :totalFiles, :processedFiles, :totalChunks,
                    0, :commitHash, now(), now(), now()
                )
                """, new MapSqlParameterSource()
                .addValue("id", jobId)
                .addValue("repositoryId", repositoryId)
                .addValue("totalFiles", counts.codeFiles())
                .addValue("processedFiles", counts.codeFiles())
                .addValue("totalChunks", counts.codeChunks())
                .addValue("commitHash", repository.lastIndexedCommit()));
    }

    private void insertCodeFile(UUID fileId, UUID repositoryId, UUID indexVersion, CodeFileArchive file) {
        jdbc.update("""
                INSERT INTO code_files (id, repository_id, index_version, file_path, language, content_hash, active, created_at, updated_at)
                VALUES (:id, :repositoryId, :indexVersion, :filePath, :language, :contentHash, TRUE, now(), now())
                """, new MapSqlParameterSource()
                .addValue("id", fileId)
                .addValue("repositoryId", repositoryId)
                .addValue("indexVersion", indexVersion)
                .addValue("filePath", file.filePath())
                .addValue("language", file.language())
                .addValue("contentHash", file.contentHash()));
    }

    private void insertCodeChunk(UUID repositoryId, UUID fileId, UUID indexVersion, String filePath, CodeChunkArchive chunk) {
        jdbc.update("""
                INSERT INTO code_chunks (
                    id, repository_id, file_id, index_version, file_path, chunk_index, chunk_type,
                    symbol_name, class_name, method_name, namespace_name, control_name, event_name,
                    line_start, line_end, content, metadata, embedding, active
                )
                VALUES (
                    :id, :repositoryId, :fileId, :indexVersion, :filePath, :chunkIndex, :chunkType,
                    :symbolName, :className, :methodName, :namespaceName, :controlName, :eventName,
                    :lineStart, :lineEnd, :content, CAST(:metadata AS jsonb), CAST(:embedding AS vector), TRUE
                )
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("repositoryId", repositoryId)
                .addValue("fileId", fileId)
                .addValue("indexVersion", indexVersion)
                .addValue("filePath", filePath)
                .addValue("chunkIndex", chunk.chunkIndex())
                .addValue("chunkType", chunk.chunkType())
                .addValue("symbolName", chunk.symbolName())
                .addValue("className", chunk.className())
                .addValue("methodName", chunk.methodName())
                .addValue("namespaceName", chunk.namespaceName())
                .addValue("controlName", chunk.controlName())
                .addValue("eventName", chunk.eventName())
                .addValue("lineStart", chunk.lineStart())
                .addValue("lineEnd", chunk.lineEnd())
                .addValue("content", chunk.content())
                .addValue("metadata", toJson(chunk.metadata()))
                .addValue("embedding", chunk.embedding()));
    }

    private Path exportDir() {
        return Path.of(properties.getTransfer().getExportDir()).toAbsolutePath().normalize();
    }

    private Path safeExportFile(String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new IllegalArgumentException("Invalid export file name.");
        }
        Path dir = exportDir();
        Path file = dir.resolve(fileName).normalize();
        if (!file.startsWith(dir)) {
            throw new IllegalArgumentException("Invalid export file path.");
        }
        return file;
    }

    private String exportFileName(String spaceName) {
        String safeName = sanitizeFilePart(spaceName == null || spaceName.isBlank() ? "space" : spaceName);
        String timestamp = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return "learnbot-space-" + safeName + "-" + timestamp + ".zip";
    }

    private String sanitizeFilePart(String value) {
        String clean = value == null ? "file" : value.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        clean = clean.replaceAll("_+", "_");
        if (clean.isBlank() || ".".equals(clean) || "..".equals(clean)) {
            return "file";
        }
        return clean.length() <= 80 ? clean : clean.substring(0, 80);
    }

    private String safeOriginalFilename(String value) {
        return value == null || value.isBlank() ? "archive.zip" : value;
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (IOException ex) {
            return Map.of();
        }
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Invalid metadata.", ex);
        }
    }

    private void validateVector(String vector) {
        if (vector == null || vector.isBlank()) {
            throw new IllegalArgumentException("Import archive contains an empty embedding.");
        }
        int dimensions = vectorDimensions(vector);
        if (dimensions != properties.getEmbedding().getDimensions()) {
            throw new IllegalArgumentException("Import archive contains an embedding with invalid dimensions.");
        }
    }

    private int vectorDimensions(String vector) {
        String clean = vector.trim();
        if (!clean.startsWith("[") || !clean.endsWith("]")) {
            return -1;
        }
        String body = clean.substring(1, clean.length() - 1).trim();
        if (body.isEmpty()) {
            return 0;
        }
        int dimensions = 1;
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) == ',') {
                dimensions++;
            }
        }
        return dimensions;
    }

    private SpaceTransferCounts sourceCounts(SourceArchive source) {
        int documents = source.documents() == null ? 0 : source.documents().size();
        int chunks = source.documents() == null ? 0 : source.documents().stream()
                .mapToInt(document -> document.chunks() == null ? 0 : document.chunks().size())
                .sum();
        return new SpaceTransferCounts(documents, chunks, source.object() == null ? 0 : 1, 0, 0, 0);
    }

    private SpaceTransferCounts repositoryCounts(CodeRepositoryArchive repository) {
        int files = repository.files() == null ? 0 : repository.files().size();
        int chunks = repository.files() == null ? 0 : repository.files().stream()
                .mapToInt(file -> file.chunks() == null ? 0 : file.chunks().size())
                .sum();
        return new SpaceTransferCounts(0, 0, 0, 1, files, chunks);
    }

    private static class ImportAccumulator {
        private SpaceTransferCounts imported = SpaceTransferCounts.empty();
        private SpaceTransferCounts skipped = SpaceTransferCounts.empty();

        void imported(SpaceTransferCounts counts) {
            imported = imported.plus(counts);
        }

        void skip(SpaceTransferCounts counts) {
            skipped = skipped.plus(counts);
        }
    }

    private record SpaceInfo(UUID id, String name, String description) {
    }

    private record SourceRow(UUID id, String type, String name, String location) {
    }

    private record ObjectExportPayload(String entry, StoredObject object) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ManifestArchive(
            String archiveVersion,
            OffsetDateTime exportedAt,
            SpaceArchiveInfo space,
            EmbeddingArchive embedding,
            SpaceTransferCounts counts
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SpaceArchiveInfo(String id, String name, String description) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingArchive(String model, int dimensions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ObjectArchive(String entry, String originalFilename, String contentType, long sizeBytes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SourceArchive(
            String sourceId,
            String type,
            String name,
            String location,
            ObjectArchive object,
            List<DocumentArchive> documents
    ) {
        public List<DocumentArchive> documents() {
            return documents == null ? List.of() : documents;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DocumentArchive(
            String documentId,
            String title,
            String sourceUri,
            String contentType,
            Map<String, Object> metadata,
            List<DocumentChunkArchive> chunks
    ) {
        public List<DocumentChunkArchive> chunks() {
            return chunks == null ? List.of() : chunks;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DocumentChunkArchive(
            int chunkIndex,
            String content,
            Map<String, Object> metadata,
            String embedding
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CodeRepositoryArchive(
            String repositoryId,
            String name,
            String gitUrl,
            String branch,
            String lastIndexedCommit,
            List<CodeFileArchive> files
    ) {
        public List<CodeFileArchive> files() {
            return files == null ? List.of() : files;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CodeFileArchive(
            String fileId,
            String filePath,
            String language,
            String contentHash,
            List<CodeChunkArchive> chunks
    ) {
        public List<CodeChunkArchive> chunks() {
            return chunks == null ? List.of() : chunks;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CodeChunkArchive(
            int chunkIndex,
            String chunkType,
            String symbolName,
            String className,
            String methodName,
            String namespaceName,
            String controlName,
            String eventName,
            int lineStart,
            int lineEnd,
            String content,
            Map<String, Object> metadata,
            String embedding
    ) {
    }
}
