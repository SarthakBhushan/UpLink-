package com.Sarthak.UpLinkapi.controller;

import com.Sarthak.UpLinkapi.documents.UserCredits;
import com.Sarthak.UpLinkapi.dto.*;
import com.Sarthak.UpLinkapi.service.FileMetadataService;
import com.Sarthak.UpLinkapi.service.RagService;
import com.Sarthak.UpLinkapi.service.UserCreditsService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/files")
public class FileController {

    private final FileMetadataService fileMetadataService;
    private final UserCreditsService userCreditsService;
    private final RagService ragService;

    /**
     * Step 1 of Pre-signed S3 Ingestion:
     * Validates atomic credits, reserves pending file record, and generates pre-signed PUT URL.
     */
    @PostMapping("/upload-request")
    public ResponseEntity<UploadResponseDTO> requestPresignedUpload(@RequestBody UploadRequestDTO request) {
        UploadResponseDTO response = fileMetadataService.requestPresignedUpload(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Step 2 of Pre-signed S3 Ingestion:
     * Confirms that client completed streaming bytes directly to S3.
     */
    @PostMapping("/upload-confirm/{fileId}")
    public ResponseEntity<FileMetadataDTO> confirmUpload(@PathVariable String fileId) {
        FileMetadataDTO file = fileMetadataService.confirmUpload(fileId);
        return ResponseEntity.ok(file);
    }

    /**
     * Legacy direct upload endpoint (retained for backward compatibility).
     */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadFiles(@RequestPart("files") MultipartFile files[]) throws IOException {
        Map<String, Object> response = new HashMap<>();
        List<FileMetadataDTO> list = fileMetadataService.uploadFiles(files);
        UserCredits finalCredits = userCreditsService.getUserCredits();

        response.put("files", list);
        response.put("remainingCredits", finalCredits.getCredits());
        response.put("remainigCredits", finalCredits.getCredits()); // backward compatibility
        return ResponseEntity.ok(response);
    }

    @GetMapping("/my")
    public ResponseEntity<?> getFilesForCurrentUser() {
        List<FileMetadataDTO> files = fileMetadataService.getFiles();
        return ResponseEntity.ok(files);
    }

    @GetMapping("/public/{id}")
    public ResponseEntity<?> getPublicFile(@PathVariable String id) {
        FileMetadataDTO file = fileMetadataService.getPublicFile(id);
        return ResponseEntity.ok(file);
    }

    /**
     * High-scale file download endpoint:
     * If file is stored in S3, redirects client directly to the pre-signed S3 download URL (HTTP 302).
     * If stored locally, falls back to streaming local disk resource.
     */
    @GetMapping("/download/{id}")
    public ResponseEntity<?> download(@PathVariable String id) throws IOException {
        FileMetadataDTO downloadableFile = fileMetadataService.getDownloadableFile(id);

        if (downloadableFile.getDownloadUrl() != null) {
            // High-throughput offloading: HTTP 302 redirect to pre-signed S3 download URL
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(downloadableFile.getDownloadUrl()))
                    .build();
        }

        if (downloadableFile.getFileLocation() != null) {
            Path path = Paths.get(downloadableFile.getFileLocation());
            Resource resource = new UrlResource(path.toUri());

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadableFile.getName() + "\"")
                    .body(resource);
        }

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("File resource not found");
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteFile(@PathVariable String id) {
        fileMetadataService.deleteFile(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/toggle-public")
    public ResponseEntity<?> togglePublic(@PathVariable String id) {
        FileMetadataDTO file = fileMetadataService.togglePublic(id);
        return ResponseEntity.ok(file);
    }

    /**
     * AI Document Intelligence (RAG):
     * Query any uploaded document (PDF/TXT) using semantic search and local Qwen LLM.
     */
    @PostMapping("/{id}/chat")
    public ResponseEntity<ChatResponseDTO> chatWithDocument(@PathVariable String id, @RequestBody ChatRequestDTO request) {
        ChatResponseDTO response = ragService.chatWithDocument(id, request);
        return ResponseEntity.ok(response);
    }
}
