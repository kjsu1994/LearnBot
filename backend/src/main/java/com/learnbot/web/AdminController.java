package com.learnbot.web;

import com.learnbot.dto.AuditLogSummary;
import com.learnbot.dto.AdminUserSummary;
import com.learnbot.dto.AdminSettingsResponse;
import com.learnbot.dto.AdminSettingsUpdateRequest;
import com.learnbot.dto.AdminTuningResponse;
import com.learnbot.dto.AdminTuningMetricsResponse;
import com.learnbot.dto.AdminTuningRecommendationResponse;
import com.learnbot.dto.AdminTuningUpdateRequest;
import com.learnbot.dto.DocumentSchemaProfileCreateRequest;
import com.learnbot.dto.DocumentSchemaProfileResponse;
import com.learnbot.dto.DocumentSchemaProfileUpdateRequest;
import com.learnbot.dto.InviteUserRequest;
import com.learnbot.dto.LlmSettingsTestRequest;
import com.learnbot.dto.LlmSettingsTestResponse;
import com.learnbot.dto.SpaceCreateRequest;
import com.learnbot.dto.SpaceExportResponse;
import com.learnbot.dto.SpaceImportResponse;
import com.learnbot.dto.SpaceMemberRequest;
import com.learnbot.dto.SpaceRoleUpdateRequest;
import com.learnbot.dto.SpaceSummary;
import com.learnbot.dto.SpaceUpdateRequest;
import com.learnbot.dto.StorageRetentionPreview;
import com.learnbot.dto.StorageRetentionRunRequest;
import com.learnbot.dto.StorageRetentionRunResponse;
import com.learnbot.dto.TrashItemSummary;
import com.learnbot.dto.UserPasswordResetRequest;
import com.learnbot.dto.UserSummary;
import com.learnbot.dto.UserUpdateRequest;
import com.learnbot.security.CurrentUserProvider;
import com.learnbot.service.AdminSettingsService;
import com.learnbot.service.AppUser;
import com.learnbot.service.AuditService;
import com.learnbot.service.AuthService;
import com.learnbot.service.DocumentSchemaProfileService;
import com.learnbot.service.RuntimeTuningService;
import com.learnbot.service.RagMetricsService;
import com.learnbot.service.SpaceTransferService;
import com.learnbot.service.StorageRetentionService;
import com.learnbot.service.TrashService;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AuthService authService;
    private final AuditService auditService;
    private final AdminSettingsService adminSettingsService;
    private final RuntimeTuningService runtimeTuningService;
    private final RagMetricsService ragMetricsService;
    private final DocumentSchemaProfileService documentSchemaProfileService;
    private final CurrentUserProvider currentUserProvider;
    private final SpaceTransferService spaceTransferService;
    private final StorageRetentionService storageRetentionService;
    private final TrashService trashService;

    public AdminController(
            AuthService authService,
            AuditService auditService,
            AdminSettingsService adminSettingsService,
            RuntimeTuningService runtimeTuningService,
            RagMetricsService ragMetricsService,
            DocumentSchemaProfileService documentSchemaProfileService,
            CurrentUserProvider currentUserProvider,
            SpaceTransferService spaceTransferService,
            StorageRetentionService storageRetentionService,
            TrashService trashService
    ) {
        this.authService = authService;
        this.auditService = auditService;
        this.adminSettingsService = adminSettingsService;
        this.runtimeTuningService = runtimeTuningService;
        this.ragMetricsService = ragMetricsService;
        this.documentSchemaProfileService = documentSchemaProfileService;
        this.currentUserProvider = currentUserProvider;
        this.spaceTransferService = spaceTransferService;
        this.storageRetentionService = storageRetentionService;
        this.trashService = trashService;
    }

    @GetMapping("/users")
    List<AdminUserSummary> users() {
        return authService.listAdminUsers(currentUserProvider.currentUser());
    }

    @PostMapping("/users")
    UserSummary invite(@Valid @RequestBody InviteUserRequest request) {
        AppUser actor = currentUserProvider.currentUser();
        AppUser user = authService.inviteUser(
                actor,
                request.identifier(),
                request.displayName(),
                request.initialPassword(),
                request.role(),
                request.spaceId(),
                request.spaceRole()
        );
        return authService.toSummary(user);
    }

    @DeleteMapping("/users/{userId}")
    void deleteUser(@PathVariable UUID userId) {
        authService.deleteUser(currentUserProvider.currentUser(), userId);
    }

    @PatchMapping("/users/{userId}")
    UserSummary updateUser(@PathVariable UUID userId, @Valid @RequestBody UserUpdateRequest request) {
        return authService.toSummary(authService.updateUser(currentUserProvider.currentUser(), userId, request.loginId(), request.displayName(), request.role()));
    }

    @PostMapping("/users/{userId}/password")
    void resetUserPassword(@PathVariable UUID userId, @Valid @RequestBody UserPasswordResetRequest request) {
        authService.resetUserPassword(currentUserProvider.currentUser(), userId, request.newPassword());
    }

    @PutMapping("/users/{userId}/spaces/{spaceId}")
    void updateUserSpaceRole(@PathVariable UUID userId, @PathVariable UUID spaceId, @RequestBody SpaceRoleUpdateRequest request) {
        authService.updateUserSpaceRole(currentUserProvider.currentUser(), userId, spaceId, request.role());
    }

    @DeleteMapping("/users/{userId}/spaces/{spaceId}")
    void removeUserSpaceRole(@PathVariable UUID userId, @PathVariable UUID spaceId) {
        authService.removeSpaceMember(currentUserProvider.currentUser(), spaceId, userId);
    }

    @GetMapping("/spaces")
    List<SpaceSummary> spaces() {
        AppUser user = currentUserProvider.currentUser();
        authService.requireAdmin(user);
        return authService.listAllSpaces(user);
    }

    @PostMapping("/spaces")
    Map<String, UUID> createSpace(@Valid @RequestBody SpaceCreateRequest request) {
        UUID id = authService.createSpace(currentUserProvider.currentUser(), request.name(), request.description());
        return Map.of("id", id);
    }

    @PatchMapping("/spaces/{spaceId}")
    void updateSpace(@PathVariable UUID spaceId, @Valid @RequestBody SpaceUpdateRequest request) {
        authService.updateSpace(currentUserProvider.currentUser(), spaceId, request.name(), request.description());
    }

    @DeleteMapping("/spaces/{spaceId}")
    void deleteSpace(@PathVariable UUID spaceId) {
        authService.deleteSpace(currentUserProvider.currentUser(), spaceId);
    }

    @PostMapping("/spaces/{spaceId}/rag-export")
    SpaceExportResponse exportSpace(@PathVariable UUID spaceId) {
        return spaceTransferService.exportSpace(currentUserProvider.currentUser(), spaceId);
    }

    @GetMapping("/spaces/{spaceId}/rag-export/files/{fileName:.+}")
    ResponseEntity<Resource> downloadExport(@PathVariable UUID spaceId, @PathVariable String fileName) throws IOException {
        AppUser user = currentUserProvider.currentUser();
        authService.requireAdminSpace(user, spaceId);
        Resource resource = spaceTransferService.exportFile(fileName);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(resource.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resource);
    }

    @PostMapping("/spaces/{spaceId}/rag-import")
    SpaceImportResponse importSpace(@PathVariable UUID spaceId, @RequestParam("file") MultipartFile file) {
        return spaceTransferService.importSpace(currentUserProvider.currentUser(), spaceId, file);
    }

    @PostMapping("/spaces/{spaceId}/members")
    void addMember(@PathVariable UUID spaceId, @Valid @RequestBody SpaceMemberRequest request) {
        authService.addSpaceMember(currentUserProvider.currentUser(), spaceId, request.userId(), request.role());
    }

    @GetMapping("/audit-logs")
    List<AuditLogSummary> auditLogs(@RequestParam(required = false) Integer limit) {
        authService.requireMaster(currentUserProvider.currentUser());
        return auditService.list(limit);
    }

    @GetMapping("/storage/retention/preview")
    StorageRetentionPreview storageRetentionPreview() {
        authService.requireMaster(currentUserProvider.currentUser());
        return storageRetentionService.preview();
    }

    @PostMapping("/storage/retention/run")
    StorageRetentionRunResponse runStorageRetention(@RequestBody(required = false) StorageRetentionRunRequest request) {
        authService.requireMaster(currentUserProvider.currentUser());
        boolean dryRun = request == null || request.dryRun() == null || request.dryRun();
        return storageRetentionService.run(dryRun);
    }

    @GetMapping("/trash")
    List<TrashItemSummary> trash(@RequestParam(required = false) String type, @RequestParam(required = false) UUID spaceId) {
        AppUser user = currentUserProvider.currentUser();
        authService.requireMaster(user);
        return trashService.list(user, type, spaceId);
    }

    @PostMapping("/trash/{type}/{id}/restore")
    void restoreTrash(@PathVariable String type, @PathVariable UUID id) {
        AppUser user = currentUserProvider.currentUser();
        authService.requireMaster(user);
        trashService.restore(user, type, id);
    }

    @GetMapping("/settings")
    AdminSettingsResponse settings() {
        authService.requireMaster(currentUserProvider.currentUser());
        return adminSettingsService.current();
    }

    @PatchMapping("/settings")
    AdminSettingsResponse updateSettings(@Valid @RequestBody AdminSettingsUpdateRequest request) {
        AppUser user = currentUserProvider.currentUser();
        authService.requireMaster(user);
        return adminSettingsService.update(
                user,
                request.respectRobotsTxt(),
                request.allowedDomains(),
                request.ollamaBaseUrl(),
                request.chatModel(),
                request.primaryChatModel(),
                request.auxiliaryChatModel()
        );
    }

    @PostMapping("/settings/llm/test")
    LlmSettingsTestResponse testLlmSettings(@RequestBody LlmSettingsTestRequest request) {
        authService.requireMaster(currentUserProvider.currentUser());
        return adminSettingsService.testLlmSettings(
                request.ollamaBaseUrl(),
                request.chatModel(),
                request.primaryChatModel(),
                request.auxiliaryChatModel()
        );
    }

    @GetMapping("/tuning")
    AdminTuningResponse tuning() {
        authService.requireMaster(currentUserProvider.currentUser());
        return runtimeTuningService.current();
    }

    @PatchMapping("/tuning")
    AdminTuningResponse updateTuning(@RequestBody AdminTuningUpdateRequest request) {
        AppUser user = currentUserProvider.currentUser();
        authService.requireMaster(user);
        return runtimeTuningService.update(user, request);
    }

    @PostMapping("/tuning/llm/test")
    LlmSettingsTestResponse testTuningLlmSettings(@RequestBody LlmSettingsTestRequest request) {
        authService.requireMaster(currentUserProvider.currentUser());
        return adminSettingsService.testLlmSettings(
                request.ollamaBaseUrl(),
                request.chatModel(),
                request.primaryChatModel(),
                request.auxiliaryChatModel()
        );
    }

    @GetMapping("/tuning/metrics")
    AdminTuningMetricsResponse tuningMetrics() {
        authService.requireMaster(currentUserProvider.currentUser());
        return ragMetricsService.current();
    }

    @PostMapping("/tuning/metrics/reset")
    AdminTuningMetricsResponse resetTuningMetrics() {
        authService.requireMaster(currentUserProvider.currentUser());
        ragMetricsService.reset();
        return ragMetricsService.current();
    }

    @GetMapping("/tuning/recommendations")
    AdminTuningRecommendationResponse tuningRecommendations() {
        authService.requireMaster(currentUserProvider.currentUser());
        return ragMetricsService.recommendations();
    }

    @GetMapping("/document-graph/schema-profiles")
    List<DocumentSchemaProfileResponse> documentSchemaProfiles() {
        authService.requireMaster(currentUserProvider.currentUser());
        return documentSchemaProfileService.listProfiles();
    }

    @PostMapping("/document-graph/schema-profiles")
    DocumentSchemaProfileResponse createDocumentSchemaProfile(@RequestBody DocumentSchemaProfileCreateRequest request) {
        authService.requireMaster(currentUserProvider.currentUser());
        return documentSchemaProfileService.createProfile(request);
    }

    @PatchMapping("/document-graph/schema-profiles/{schemaName}")
    DocumentSchemaProfileResponse updateDocumentSchemaProfile(
            @PathVariable String schemaName,
            @RequestBody DocumentSchemaProfileUpdateRequest request
    ) {
        authService.requireMaster(currentUserProvider.currentUser());
        return documentSchemaProfileService.updateProfile(schemaName, request);
    }
}
