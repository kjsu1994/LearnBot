package com.learnbot.service;

import com.learnbot.dto.DocumentChunkDetail;
import com.learnbot.dto.DocumentPreviewResponse;
import com.learnbot.dto.DocumentSummary;
import com.learnbot.repository.DocumentRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentPreviewServiceTest {
    @Test
    void excelPreviewRendersSheetsFromStoredOriginal() throws Exception {
        DocumentRepository repository = mock(DocumentRepository.class);
        ObjectStorageService objectStorageService = mock(ObjectStorageService.class);
        AuthService authService = mock(AuthService.class);
        DocumentPreviewService service = new DocumentPreviewService(repository, objectStorageService, authService);
        AppUser user = user();
        UUID documentId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        DocumentSummary summary = summary(documentId, sourceId, spaceId, "FILE", "people.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        StoredObject object = new StoredObject("bucket", "key", "people.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 128);

        when(authService.accessibleSpaceIds(user)).thenReturn(List.of(spaceId));
        when(repository.findDocument(eq(documentId), anyList())).thenReturn(Optional.of(summary));
        when(repository.findSourceObject(sourceId)).thenReturn(Optional.of(object));
        when(objectStorageService.load(object)).thenReturn(new StoredFile("people.xlsx", object.contentType(), workbookBytes()));

        DocumentPreviewResponse response = service.preview(user, documentId);

        assertThat(response.previewType()).isEqualTo("excel");
        assertThat(response.sheets()).hasSize(1);
        assertThat(response.sheets().get(0).rows()).contains(List.of("name", "role"), List.of("Kim", "Admin"));
    }

    @Test
    void webPreviewUsesIndexedChunks() {
        DocumentRepository repository = mock(DocumentRepository.class);
        ObjectStorageService objectStorageService = mock(ObjectStorageService.class);
        AuthService authService = mock(AuthService.class);
        DocumentPreviewService service = new DocumentPreviewService(repository, objectStorageService, authService);
        AppUser user = user();
        UUID documentId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        DocumentSummary summary = summary(documentId, sourceId, spaceId, "WEB", "https://example.com/docs", "text/html");

        when(authService.accessibleSpaceIds(user)).thenReturn(List.of(spaceId));
        when(repository.findDocument(eq(documentId), anyList())).thenReturn(Optional.of(summary));
        when(repository.listDocumentChunks(documentId)).thenReturn(List.of(
                chunk(0, "First indexed paragraph."),
                chunk(1, "Second indexed paragraph.")
        ));

        DocumentPreviewResponse response = service.preview(user, documentId);

        assertThat(response.previewType()).isEqualTo("web");
        assertThat(response.text()).contains("First indexed paragraph.", "Second indexed paragraph.");
        assertThat(response.originalAvailable()).isFalse();
    }

    @Test
    void webPreviewUsesStructuredMetadataWhenAvailable() {
        DocumentRepository repository = mock(DocumentRepository.class);
        ObjectStorageService objectStorageService = mock(ObjectStorageService.class);
        AuthService authService = mock(AuthService.class);
        DocumentPreviewService service = new DocumentPreviewService(repository, objectStorageService, authService);
        AppUser user = user();
        UUID documentId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        DocumentSummary summary = summary(documentId, sourceId, spaceId, "WEB", "https://example.com/docs", "text/html");

        when(authService.accessibleSpaceIds(user)).thenReturn(List.of(spaceId));
        when(repository.findDocument(eq(documentId), anyList())).thenReturn(Optional.of(summary));
        when(repository.documentMetadata(documentId)).thenReturn(Map.of(
                "webPreviewBlocks", List.of(
                        Map.of("type", "heading", "level", 1, "text", "Guide"),
                        Map.of("type", "list", "items", List.of("First", "Second")),
                        Map.of("type", "table", "rows", List.of(List.of("Name", "Role"), List.of("Kim", "Admin")))
                )
        ));

        DocumentPreviewResponse response = service.preview(user, documentId);

        assertThat(response.previewType()).isEqualTo("web");
        assertThat(response.blocks()).hasSize(3);
        assertThat(response.text()).contains("Guide", "- First", "Name | Role");
        assertThat(response.originalAvailable()).isFalse();
    }

    private AppUser user() {
        return new AppUser(UUID.randomUUID(), "user", "User", "USER", "ACTIVE");
    }

    private DocumentSummary summary(UUID documentId, UUID sourceId, UUID spaceId, String sourceType, String title, String contentType) {
        return new DocumentSummary(
                documentId,
                sourceId,
                spaceId,
                sourceType,
                "INDEXED",
                title,
                sourceType.equals("WEB") ? title : "file://" + title,
                contentType,
                OffsetDateTime.now()
        );
    }

    private DocumentChunkDetail chunk(int index, String content) {
        return new DocumentChunkDetail(UUID.randomUUID(), index, content, OffsetDateTime.now());
    }

    private byte[] workbookBytes() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Users");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("name");
            header.createCell(1).setCellValue("role");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("Kim");
            row.createCell(1).setCellValue("Admin");
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
