package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import com.learnbot.dto.StorageRetentionArea;
import com.learnbot.dto.StorageRetentionPreview;
import com.learnbot.dto.StorageRetentionRunResponse;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class StorageRetentionService {
    private static final Logger log = LoggerFactory.getLogger(StorageRetentionService.class);
    private static final List<String> TERMINAL_JOB_STATUSES = List.of("SUCCEEDED", "FAILED", "SKIPPED", "CANCELLED");

    private final NamedParameterJdbcTemplate jdbc;
    private final LearnBotProperties properties;
    private final ObjectStorageService objectStorageService;

    public StorageRetentionService(
            NamedParameterJdbcTemplate jdbc,
            LearnBotProperties properties,
            ObjectStorageService objectStorageService
    ) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.objectStorageService = objectStorageService;
    }

    @PostConstruct
    void previewOnStartup() {
        if (!properties.getRetention().isEnabled()) {
            return;
        }
        StorageRetentionPreview preview = preview();
        log.info("storage_retention_preview candidates={} estimatedBytes={}",
                preview.totalCandidates(), preview.totalEstimatedBytes());
    }

    @Scheduled(cron = "0 40 3 * * *", zone = "Asia/Seoul")
    public void runScheduled() {
        if (!properties.getRetention().isEnabled()) {
            return;
        }
        run(properties.getRetention().isDryRun());
    }

    public StorageRetentionPreview preview() {
        List<StorageRetentionArea> areas = collect(false, true);
        return new StorageRetentionPreview(
                OffsetDateTime.now(),
                properties.getRetention().isDryRun(),
                areas,
                areas.stream().mapToLong(StorageRetentionArea::candidates).sum(),
                areas.stream().mapToLong(StorageRetentionArea::estimatedBytes).sum()
        );
    }

    public StorageRetentionRunResponse run(boolean dryRun) {
        List<StorageRetentionArea> areas = collect(!dryRun, false);
        long deleted = areas.stream().mapToLong(StorageRetentionArea::deleted).sum();
        long estimatedBytes = areas.stream().mapToLong(StorageRetentionArea::estimatedBytes).sum();
        log.info("storage_retention_run dryRun={} deleted={} estimatedBytes={}", dryRun, deleted, estimatedBytes);
        return new StorageRetentionRunResponse(OffsetDateTime.now(), dryRun, areas, deleted, estimatedBytes);
    }

    private List<StorageRetentionArea> collect(boolean delete, boolean preview) {
        List<StorageRetentionArea> areas = new ArrayList<>();
        addArea(areas, () -> operationLogs(delete), "operation-logs");
        addArea(areas, () -> auditLogs(delete), "audit-logs");
        addArea(areas, () -> exportFiles(delete), "export-files");
        addArea(areas, () -> deletedSourceObjects(delete), "deleted-source-objects");
        addArea(areas, () -> orphanObjects(delete, preview), "orphan-objects");
        addArea(areas, () -> dependencyCache(delete), "dependency-cache");
        addArea(areas, () -> codeIndexArtifacts(delete), "code-index-artifacts");
        addArea(areas, () -> failedDocumentSources(delete), "failed-document-sources");
        addArea(areas, () -> softDeletedDocumentSources(delete), "soft-deleted-document-sources");
        addArea(areas, () -> softDeletedCodeRepositories(delete), "soft-deleted-code-repositories");
        addArea(areas, () -> orphanCodeWorkspaces(delete), "orphan-code-workspaces");
        if (delete) {
            vacuumAnalyzeBestEffort();
        }
        return areas;
    }

    private void addArea(List<StorageRetentionArea> areas, AreaSupplier supplier, String key) {
        try {
            areas.add(supplier.get());
        } catch (RuntimeException ex) {
            log.warn("storage_retention_area_failed key={} reason={}", key, rootMessage(ex));
            areas.add(new StorageRetentionArea(key, key, "정리 중 오류가 발생했습니다: " + rootMessage(ex),
                    0, 0, 0, 0));
        }
    }

    private StorageRetentionArea operationLogs(boolean delete) {
        int days = properties.getRetention().getOperationLogDays();
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(days);
        List<DbCleanupSpec> specs = List.of(
                new DbCleanupSpec("crawl_audit_logs", "started_at", null),
                new DbCleanupSpec("document_processing_diagnostics", "created_at", null),
                new DbCleanupSpec("code_analysis_diagnostics", "created_at", null),
                new DbCleanupSpec("indexing_job_failures", "created_at", null),
                new DbCleanupSpec("document_enrichment_jobs", "created_at", "status IN (:terminalStatuses)"),
                new DbCleanupSpec("document_graph_jobs", "created_at", "status IN (:terminalStatuses)"),
                new DbCleanupSpec("code_graph_enrichment_jobs", "created_at", "status IN (:terminalStatuses)")
        );
        long candidates = 0;
        long deleted = 0;
        long estimatedBytes = 0;
        for (DbCleanupSpec spec : specs) {
            candidates += countRows(spec, cutoff);
            estimatedBytes += tableBytes(spec.table());
            if (delete) {
                deleted += deleteRows(spec, cutoff);
            }
        }
        return new StorageRetentionArea("operation-logs", "운영/크롤/진단 로그",
                "오래된 진단 화면 이력만 사라지고 검색 품질에는 영향이 없습니다.",
                days, candidates, deleted, estimatedBytes);
    }

    private StorageRetentionArea auditLogs(boolean delete) {
        int days = properties.getRetention().getAuditLogDays();
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(days);
        DbCleanupSpec spec = new DbCleanupSpec("audit_logs", "created_at", null);
        long candidates = countRows(spec, cutoff);
        long deleted = delete ? deleteRows(spec, cutoff) : 0;
        return new StorageRetentionArea("audit-logs", "관리자 감사 로그",
                "보안/관리자 이력입니다. 기본값은 운영 로그보다 길게 보관합니다.",
                days, candidates, deleted, tableBytes(spec.table()));
    }

    private StorageRetentionArea exportFiles(boolean delete) {
        int days = properties.getRetention().getExportDays();
        Instant cutoff = Instant.now().minusSeconds(days * 86_400L);
        Path exportDir = Path.of(properties.getTransfer().getExportDir()).toAbsolutePath().normalize();
        FileStats stats = oldFiles(exportDir, cutoff, delete, "*.zip");
        return new StorageRetentionArea("export-files", "RAG export ZIP",
                "기존 ZIP 다운로드만 불가하며 DB와 원본 데이터에는 영향이 없습니다.",
                days, stats.count(), stats.deleted(), stats.bytes());
    }

    private StorageRetentionArea deletedSourceObjects(boolean delete) {
        int days = properties.getRetention().getOrphanGraceDays();
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(days);
        List<StoredObject> objects = jdbc.query("""
                SELECT o.bucket, o.object_key, o.original_filename, o.content_type, o.size_bytes
                FROM source_objects o
                JOIN data_sources s ON s.id = o.source_id
                WHERE s.deleted_at IS NOT NULL
                  AND s.deleted_at < :cutoff
                """, new MapSqlParameterSource().addValue("cutoff", cutoff), (rs, rowNum) -> new StoredObject(
                rs.getString("bucket"),
                rs.getString("object_key"),
                rs.getString("original_filename"),
                rs.getString("content_type"),
                rs.getLong("size_bytes")
        ));
        long deleted = 0;
        if (delete) {
            for (StoredObject object : objects) {
                objectStorageService.delete(object);
                deleted++;
            }
            jdbc.update("""
                    DELETE FROM source_objects o
                    USING data_sources s
                    WHERE s.id = o.source_id
                      AND s.deleted_at IS NOT NULL
                      AND s.deleted_at < :cutoff
                    """, new MapSqlParameterSource().addValue("cutoff", cutoff));
        }
        long bytes = objects.stream().mapToLong(StoredObject::sizeBytes).sum();
        return new StorageRetentionArea("deleted-source-objects", "삭제된 문서 원본 객체",
                "이미 삭제된 문서 소스의 원본만 제거합니다. 활성 문서 미리보기와 재인덱싱은 유지됩니다.",
                days, objects.size(), deleted, bytes);
    }

    private StorageRetentionArea orphanObjects(boolean delete, boolean preview) {
        int days = properties.getRetention().getOrphanGraceDays();
        Instant cutoff = Instant.now().minusSeconds(days * 86_400L);
        Set<String> referenced = new HashSet<>(jdbc.queryForList("""
                SELECT object_key
                FROM source_objects
                WHERE bucket = :bucket
                """, new MapSqlParameterSource().addValue("bucket", properties.getStorage().getBucket()), String.class));
        List<ObjectStorageService.ObjectSummary> objects;
        try {
            objects = objectStorageService.listObjects("sources/");
        } catch (RuntimeException ex) {
            if (preview) {
                return new StorageRetentionArea("orphan-objects", "참조 없는 MinIO 객체",
                        "MinIO 목록을 가져오지 못했습니다: " + rootMessage(ex),
                        days, 0, 0, 0);
            }
            throw ex;
        }
        long candidates = 0;
        long deleted = 0;
        long bytes = 0;
        for (ObjectStorageService.ObjectSummary object : objects) {
            if (referenced.contains(object.objectKey())) {
                continue;
            }
            if (object.lastModified() != null && object.lastModified().isAfter(cutoff)) {
                continue;
            }
            candidates++;
            bytes += object.sizeBytes();
            if (delete) {
                objectStorageService.delete(object.bucket(), object.objectKey());
                deleted++;
            }
        }
        return new StorageRetentionArea("orphan-objects", "참조 없는 MinIO 객체",
                "DB에서 참조하지 않는 객체만 grace period 이후 삭제합니다.",
                days, candidates, deleted, bytes);
    }

    private StorageRetentionArea dependencyCache(boolean delete) {
        int days = properties.getRetention().getDependencyCacheDays();
        Instant cutoff = Instant.now().minusSeconds(days * 86_400L);
        Path workspace = Path.of(properties.getCode().getWorkspacePath()).toAbsolutePath().normalize();
        Path cache = workspace.resolve(".dependency-cache").normalize();
        if (!cache.startsWith(workspace)) {
            return new StorageRetentionArea("dependency-cache", "코드 의존성 캐시",
                    "캐시 경로가 workspace 밖으로 벗어나 정리하지 않았습니다.",
                    days, 0, 0, 0);
        }
        FileStats stats = oldFilesRecursive(cache, cutoff, delete);
        return new StorageRetentionArea("dependency-cache", "코드 의존성 캐시",
                "다음 Java 그래프 보강에서 일부 의존성을 다시 받을 수 있지만 검색 데이터는 유지됩니다.",
                days, stats.count(), stats.deleted(), stats.bytes());
    }

    private StorageRetentionArea codeIndexArtifacts(boolean delete) {
        int days = properties.getRetention().getIndexArtifactDays();
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(days);
        MapSqlParameterSource params = params(cutoff);
        String eligibleJobs = """
                SELECT j.id
                FROM indexing_jobs j
                WHERE j.created_at < :cutoff
                  AND j.status IN (:terminalStatuses)
                  AND NOT EXISTS (
                      SELECT 1
                      FROM code_files f
                      WHERE f.index_version = j.id
                        AND f.active
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM code_chunks c
                      WHERE c.index_version = j.id
                        AND c.active
                  )
                """;
        Long candidates = jdbc.queryForObject("SELECT COUNT(*) FROM (" + eligibleJobs + ") eligible",
                params, Long.class);
        Long estimatedBytes = jdbc.queryForObject("""
                SELECT COALESCE(SUM(bytes), 0)
                FROM (
                    SELECT pg_column_size(j.*)::bigint AS bytes
                    FROM indexing_jobs j
                    WHERE j.id IN (""" + eligibleJobs + """
                    )
                    UNION ALL
                    SELECT pg_column_size(f.*)::bigint AS bytes
                    FROM code_files f
                    WHERE f.index_version IN (""" + eligibleJobs + """
                    )
                    UNION ALL
                    SELECT pg_column_size(c.*)::bigint AS bytes
                    FROM code_chunks c
                    WHERE c.index_version IN (""" + eligibleJobs + """
                    )
                    UNION ALL
                    SELECT pg_column_size(n.*)::bigint AS bytes
                    FROM code_graph_nodes n
                    WHERE n.index_version IN (""" + eligibleJobs + """
                    )
                    UNION ALL
                    SELECT pg_column_size(e.*)::bigint AS bytes
                    FROM code_graph_edges e
                    WHERE e.index_version IN (""" + eligibleJobs + """
                    )
                ) artifact_bytes
                """, params, Long.class);
        long deleted = 0;
        if (delete) {
            deleted = jdbc.update("DELETE FROM indexing_jobs j WHERE j.id IN (" + eligibleJobs + ")", params);
        }
        return new StorageRetentionArea("code-index-artifacts", "Inactive code index artifacts",
                "Deletes only old failed, cancelled, or superseded code chunks and graph rows that are not active search data.",
                days, candidates == null ? 0 : candidates, deleted, estimatedBytes == null ? 0 : estimatedBytes);
    }

    private StorageRetentionArea failedDocumentSources(boolean delete) {
        int days = properties.getRetention().getFailedSourceDays();
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(days);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("cutoff", cutoff);
        String eligibleSources = """
                SELECT s.id
                FROM data_sources s
                WHERE s.status = 'FAILED'
                  AND s.deleted_at IS NULL
                  AND s.updated_at < :cutoff
                  AND NOT EXISTS (
                      SELECT 1
                      FROM documents d
                      WHERE d.source_id = s.id
                  )
                """;
        List<StoredObject> objects = jdbc.query("""
                SELECT o.bucket, o.object_key, o.original_filename, o.content_type, o.size_bytes
                FROM source_objects o
                WHERE o.source_id IN (""" + eligibleSources + """
                )
                """, params, (rs, rowNum) -> new StoredObject(
                rs.getString("bucket"),
                rs.getString("object_key"),
                rs.getString("original_filename"),
                rs.getString("content_type"),
                rs.getLong("size_bytes")
        ));
        Long candidates = jdbc.queryForObject("SELECT COUNT(*) FROM (" + eligibleSources + ") eligible",
                params, Long.class);
        long deleted = 0;
        if (delete) {
            for (StoredObject object : objects) {
                try {
                    objectStorageService.delete(object);
                } catch (RuntimeException ex) {
                    log.warn("storage_retention_failed_source_object_delete_failed bucket={} key={} reason={}",
                            object.bucket(), object.objectKey(), rootMessage(ex));
                }
            }
            deleted = jdbc.update("DELETE FROM data_sources s WHERE s.id IN (" + eligibleSources + ")", params);
        }
        long bytes = objects.stream().mapToLong(StoredObject::sizeBytes).sum();
        return new StorageRetentionArea("failed-document-sources", "Failed document indexing sources",
                "Deletes only failed document sources that never produced searchable documents.",
                days, candidates == null ? 0 : candidates, deleted, bytes);
    }

    private StorageRetentionArea softDeletedDocumentSources(boolean delete) {
        int days = properties.getRetention().getOrphanGraceDays();
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(days);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("cutoff", cutoff);
        Long candidates = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM data_sources
                WHERE deleted_at IS NOT NULL
                  AND deleted_at < :cutoff
                """, params, Long.class);
        Long estimatedBytes = jdbc.queryForObject("""
                SELECT COALESCE(SUM(bytes), 0)
                FROM (
                    SELECT pg_column_size(s.*)::bigint AS bytes
                    FROM data_sources s
                    WHERE s.deleted_at IS NOT NULL
                      AND s.deleted_at < :cutoff
                    UNION ALL
                    SELECT pg_column_size(d.*)::bigint AS bytes
                    FROM documents d
                    JOIN data_sources s ON s.id = d.source_id
                    WHERE s.deleted_at IS NOT NULL
                      AND s.deleted_at < :cutoff
                    UNION ALL
                    SELECT pg_column_size(c.*)::bigint AS bytes
                    FROM document_chunks c
                    JOIN documents d ON d.id = c.document_id
                    JOIN data_sources s ON s.id = d.source_id
                    WHERE s.deleted_at IS NOT NULL
                      AND s.deleted_at < :cutoff
                ) source_bytes
                """, params, Long.class);
        long deleted = 0;
        if (delete) {
            deleted = jdbc.update("""
                    DELETE FROM data_sources
                    WHERE deleted_at IS NOT NULL
                      AND deleted_at < :cutoff
                    """, params);
        }
        return new StorageRetentionArea("soft-deleted-document-sources", "Soft-deleted document sources",
                "Permanently deletes document sources after the restore window has expired.",
                days, candidates == null ? 0 : candidates, deleted, estimatedBytes == null ? 0 : estimatedBytes);
    }

    private StorageRetentionArea softDeletedCodeRepositories(boolean delete) {
        int days = properties.getRetention().getOrphanGraceDays();
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(days);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("cutoff", cutoff);
        List<DeletedRepositoryPath> repositories = jdbc.query("""
                SELECT id, local_path
                FROM code_repositories
                WHERE deleted_at IS NOT NULL
                  AND deleted_at < :cutoff
                """, params, (rs, rowNum) -> new DeletedRepositoryPath(
                rs.getObject("id", java.util.UUID.class),
                rs.getString("local_path")
        ));
        long bytes = 0;
        for (DeletedRepositoryPath repository : repositories) {
            Path path = safeWorkspacePath(repository.localPath());
            if (path != null && Files.isDirectory(path)) {
                bytes += directoryBytes(path);
            }
        }
        long deleted = 0;
        if (delete) {
            for (DeletedRepositoryPath repository : repositories) {
                Path path = safeWorkspacePath(repository.localPath());
                if (path != null && Files.isDirectory(path)) {
                    deleteDirectoryRecursively(path);
                }
            }
            deleted = jdbc.update("""
                    DELETE FROM code_repositories
                    WHERE deleted_at IS NOT NULL
                      AND deleted_at < :cutoff
                    """, params);
        }
        return new StorageRetentionArea("soft-deleted-code-repositories", "Soft-deleted code repositories",
                "Permanently deletes code repositories and local workspaces after the restore window has expired.",
                days, repositories.size(), deleted, bytes);
    }

    private StorageRetentionArea orphanCodeWorkspaces(boolean delete) {
        int days = properties.getRetention().getOrphanWorkspaceDays();
        Instant cutoff = Instant.now().minusSeconds(days * 86_400L);
        Path workspace = Path.of(properties.getCode().getWorkspacePath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(workspace)) {
            return new StorageRetentionArea("orphan-code-workspaces", "Orphan code workspaces",
                    "Code workspace directory does not exist.",
                    days, 0, 0, 0);
        }
        Set<Path> referenced = new HashSet<>();
        for (String localPath : jdbc.queryForList("""
                SELECT local_path
                FROM code_repositories
                WHERE local_path IS NOT NULL
                """, new MapSqlParameterSource(), String.class)) {
            try {
                referenced.add(Path.of(localPath).toAbsolutePath().normalize());
            } catch (RuntimeException ignored) {
                // Invalid historic paths are ignored so cleanup can continue safely.
            }
        }
        DirectoryStats stats = oldUnreferencedDirectories(workspace, referenced, cutoff, delete);
        return new StorageRetentionArea("orphan-code-workspaces", "Orphan code workspaces",
                "Deletes old local repository directories that are no longer referenced by code repository records.",
                days, stats.count(), stats.deleted(), stats.bytes());
    }

    private long countRows(DbCleanupSpec spec, OffsetDateTime cutoff) {
        String sql = "SELECT count(*) FROM " + spec.table() + " WHERE " + spec.timestampColumn() + " < :cutoff"
                + whereSuffix(spec);
        Long count = jdbc.queryForObject(sql, params(cutoff), Long.class);
        return count == null ? 0 : count;
    }

    private long deleteRows(DbCleanupSpec spec, OffsetDateTime cutoff) {
        String sql = "DELETE FROM " + spec.table() + " WHERE " + spec.timestampColumn() + " < :cutoff"
                + whereSuffix(spec);
        return jdbc.update(sql, params(cutoff));
    }

    private MapSqlParameterSource params(OffsetDateTime cutoff) {
        return new MapSqlParameterSource()
                .addValue("cutoff", cutoff)
                .addValue("terminalStatuses", TERMINAL_JOB_STATUSES);
    }

    private String whereSuffix(DbCleanupSpec spec) {
        return spec.extraWhere() == null || spec.extraWhere().isBlank() ? "" : " AND " + spec.extraWhere();
    }

    private long tableBytes(String table) {
        Long bytes = jdbc.queryForObject("SELECT COALESCE(pg_total_relation_size(to_regclass(:tableName)), 0)",
                new MapSqlParameterSource().addValue("tableName", table), Long.class);
        return bytes == null ? 0 : bytes;
    }

    private FileStats oldFiles(Path directory, Instant cutoff, boolean delete, String glob) {
        if (!Files.isDirectory(directory)) {
            return new FileStats(0, 0, 0);
        }
        long count = 0;
        long deleted = 0;
        long bytes = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, glob)) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                Instant modified = Files.getLastModifiedTime(path).toInstant();
                if (modified.isAfter(cutoff)) {
                    continue;
                }
                count++;
                bytes += Files.size(path);
                if (delete) {
                    Files.deleteIfExists(path);
                    deleted++;
                }
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not inspect cleanup directory " + directory, ex);
        }
        return new FileStats(count, deleted, bytes);
    }

    private FileStats oldFilesRecursive(Path directory, Instant cutoff, boolean delete) {
        if (!Files.isDirectory(directory)) {
            return new FileStats(0, 0, 0);
        }
        long count = 0;
        long deleted = 0;
        long bytes = 0;
        try (Stream<Path> paths = Files.walk(directory)) {
            List<Path> files = paths
                    .filter(Files::isRegularFile)
                    .toList();
            for (Path path : files) {
                Instant modified = Files.getLastModifiedTime(path).toInstant();
                if (modified.isAfter(cutoff)) {
                    continue;
                }
                count++;
                bytes += Files.size(path);
                if (delete) {
                    Files.deleteIfExists(path);
                    deleted++;
                }
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not inspect cleanup directory " + directory, ex);
        }
        if (delete) {
            deleteEmptyDirectories(directory);
        }
        return new FileStats(count, deleted, bytes);
    }

    private DirectoryStats oldUnreferencedDirectories(Path root, Set<Path> referenced, Instant cutoff, boolean delete) {
        long count = 0;
        long deleted = 0;
        long bytes = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path path : stream) {
                if (!Files.isDirectory(path)) {
                    continue;
                }
                if (".dependency-cache".equals(path.getFileName().toString())) {
                    continue;
                }
                Path candidate = path.toAbsolutePath().normalize();
                if (!candidate.startsWith(root) || isReferencedPath(candidate, referenced)) {
                    continue;
                }
                Instant modified = Files.getLastModifiedTime(candidate).toInstant();
                if (modified.isAfter(cutoff)) {
                    continue;
                }
                count++;
                bytes += directoryBytes(candidate);
                if (delete) {
                    deleteDirectoryRecursively(candidate);
                    deleted++;
                }
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not inspect cleanup directory " + root, ex);
        }
        return new DirectoryStats(count, deleted, bytes);
    }

    private boolean isReferencedPath(Path candidate, Set<Path> referenced) {
        for (Path path : referenced) {
            if (path.equals(candidate) || path.startsWith(candidate) || candidate.startsWith(path)) {
                return true;
            }
        }
        return false;
    }

    private Path safeWorkspacePath(String localPath) {
        if (localPath == null || localPath.isBlank() || localPath.contains("://")) {
            return null;
        }
        Path workspace = Path.of(properties.getCode().getWorkspacePath()).toAbsolutePath().normalize();
        Path target;
        try {
            target = Path.of(localPath).toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            return null;
        }
        if (!target.startsWith(workspace) || target.equals(workspace)) {
            return null;
        }
        return target;
    }

    private long directoryBytes(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException ignored) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not inspect cleanup directory " + directory, ex);
        }
    }

    private void deleteDirectoryRecursively(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            List<Path> all = paths
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path path : all) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not delete cleanup directory " + directory, ex);
        }
    }

    private void deleteEmptyDirectories(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> directories = paths
                    .filter(Files::isDirectory)
                    .filter(path -> !path.equals(root))
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path directory : directories) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                    if (!stream.iterator().hasNext()) {
                        Files.deleteIfExists(directory);
                    }
                }
            }
        } catch (IOException ex) {
            log.warn("storage_retention_empty_dir_cleanup_failed root={} reason={}", root, rootMessage(ex));
        }
    }

    private void vacuumAnalyzeBestEffort() {
        for (String table : List.of(
                "crawl_audit_logs",
                "document_processing_diagnostics",
                "code_analysis_diagnostics",
                "indexing_job_failures",
                "document_enrichment_jobs",
                "document_graph_jobs",
                "code_graph_enrichment_jobs",
                "indexing_jobs",
                "code_files",
                "code_chunks",
                "code_graph_nodes",
                "code_graph_edges",
                "data_sources",
                "source_objects",
                "documents",
                "document_chunks",
                "audit_logs"
        )) {
            try {
                jdbc.getJdbcTemplate().execute("VACUUM (ANALYZE) " + table);
            } catch (RuntimeException ex) {
                log.warn("storage_retention_vacuum_failed table={} reason={}", table, rootMessage(ex));
            }
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record DbCleanupSpec(String table, String timestampColumn, String extraWhere) {
    }

    private record FileStats(long count, long deleted, long bytes) {
    }

    private record DirectoryStats(long count, long deleted, long bytes) {
    }

    private record DeletedRepositoryPath(java.util.UUID id, String localPath) {
    }

    @FunctionalInterface
    private interface AreaSupplier {
        StorageRetentionArea get();
    }
}
