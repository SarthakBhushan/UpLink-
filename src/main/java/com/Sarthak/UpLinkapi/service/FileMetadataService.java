package com.Sarthak.UpLinkapi.service;

import com.Sarthak.UpLinkapi.documents.FileMetadataDocument;
import com.Sarthak.UpLinkapi.documents.ProfileDocument;
import com.Sarthak.UpLinkapi.documents.UserCredits;
import com.Sarthak.UpLinkapi.dto.FileMetadataDTO;
import com.Sarthak.UpLinkapi.dto.UploadRequestDTO;
import com.Sarthak.UpLinkapi.dto.UploadResponseDTO;
import com.Sarthak.UpLinkapi.event.FileEventPublisher;
import com.Sarthak.UpLinkapi.event.FileUploadedEvent;
import com.Sarthak.UpLinkapi.exceptions.InsufficientCreditsException;
import com.Sarthak.UpLinkapi.exceptions.ResourceNotFoundException;
import com.Sarthak.UpLinkapi.exceptions.UnauthorizedAccessException;
import com.Sarthak.UpLinkapi.repository.FileMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileMetadataService {

    private final ProfileService profileService;
    private final UserCreditsService userCreditsService;
    private final FileMetadataRepository fileMetadataRepository;
    private final S3StorageService s3StorageService;
    private final FileEventPublisher fileEventPublisher;

    private static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024; // 50MB

    /**
     * Request pre-signed S3 URL for direct client-to-storage upload.
     * Validates quota and file parameters without deducting credit upfront.
     * Credit is deducted only upon verified confirmation in confirmUpload.
     */
    public UploadResponseDTO requestPresignedUpload(UploadRequestDTO request) {
        if (request == null || request.getFileName() == null || request.getFileName().isBlank()) {
            throw new IllegalArgumentException("File name is required.");
        }
        if (request.getFileSize() == null || request.getFileSize() <= 0) {
            throw new IllegalArgumentException("File size must be greater than zero.");
        }
        if (request.getFileSize() > MAX_FILE_SIZE_BYTES) {
            throw new MaxUploadSizeExceededException(MAX_FILE_SIZE_BYTES);
        }

        ProfileDocument currentProfile = profileService.getCurrentProfile();
        String clerkId = currentProfile.getClerkId();

        // Check if user has sufficient credits before issuing upload token
        if (!userCreditsService.hasEnoughCredits(1)) {
            throw new InsufficientCreditsException("Insufficient credits to upload files. Please purchase more credits.");
        }

        // Generate isolated object key in S3
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        String cleanFileName = StringUtils.cleanPath(request.getFileName()).replaceAll("\\s+", "_");
        String s3Key = "uploads/" + clerkId + "/" + uniqueSuffix + "-" + cleanFileName;

        // Generate pre-signed URL valid for 15 minutes
        Duration validity = Duration.ofMinutes(15);
        String presignedUrl = s3StorageService.generatePresignedUploadUrl(s3Key, request.getContentType(), validity);

        // Save initial file metadata in PENDING_UPLOAD status
        FileMetadataDocument document = FileMetadataDocument.builder()
                .name(request.getFileName())
                .size(request.getFileSize())
                .type(request.getContentType())
                .clerkId(clerkId)
                .s3Key(s3Key)
                .status("PENDING_UPLOAD")
                .isPublic(false)
                .uploadedAt(LocalDateTime.now())
                .build();

        FileMetadataDocument saved = fileMetadataRepository.save(document);
        UserCredits currentCredits = userCreditsService.getUserCredits(clerkId);

        return UploadResponseDTO.builder()
                .fileId(saved.getId())
                .uploadUrl(presignedUrl)
                .s3Key(s3Key)
                .remainingCredits(currentCredits.getCredits())
                .expiresInSeconds(validity.toSeconds())
                .message("Pre-signed upload URL generated successfully. Stream payload directly to S3.")
                .build();
    }

    /**
     * Confirms that the file was successfully written to S3 by the client.
     * Deducts credit atomically only after object existence is verified in storage.
     */
    public FileMetadataDTO confirmUpload(String fileId) {
        ProfileDocument currentProfile = profileService.getCurrentProfile();
        FileMetadataDocument document = fileMetadataRepository.findById(fileId)
                .orElseThrow(() -> new ResourceNotFoundException("File record not found with ID: " + fileId));

        if (!document.getClerkId().equals(currentProfile.getClerkId())) {
            throw new UnauthorizedAccessException("Unauthorized: File does not belong to the current user.");
        }

        // Verify object existence in S3
        boolean existsInS3 = document.getS3Key() != null && s3StorageService.doesObjectExist(document.getS3Key());
        boolean existsLocally = document.getFileLocation() != null && Files.exists(Paths.get(document.getFileLocation()));

        if (!existsInS3 && !existsLocally) {
            document.setStatus("FAILED");
            fileMetadataRepository.save(document);
            log.warn("Upload confirmation failed for file ID: {} - Object not found in storage", fileId);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Upload verification failed: File not found in storage.");
        }

        // Deduct credit atomically upon confirmed upload
        userCreditsService.consumeCreditAtomic(currentProfile.getClerkId(), 1);

        document.setStatus("READY");
        FileMetadataDocument updated = fileMetadataRepository.save(document);

        // Publish event for async background processing (SHA-256 and RAG vector indexing)
        fileEventPublisher.publishFileUploadedEvent(FileUploadedEvent.builder()
                .fileId(updated.getId())
                .clerkId(updated.getClerkId())
                .fileName(updated.getName())
                .fileType(updated.getType())
                .s3Key(updated.getS3Key())
                .localPath(updated.getFileLocation())
                .timestamp(LocalDateTime.now())
                .build());

        return mapToDTO(updated);
    }

    /**
     * Legacy upload method supporting direct multipart streams.
     * Concurrency hardened with atomic credit deduction.
     */
    public List<FileMetadataDTO> uploadFiles(MultipartFile files[]) throws IOException {
        ProfileDocument currentProfile = profileService.getCurrentProfile();
        List<FileMetadataDocument> savedFiles = new ArrayList<>();

        // Atomically consume credits for all files
        userCreditsService.consumeCreditAtomic(currentProfile.getClerkId(), files.length);

        Path uploadPath = Paths.get("uploads").toAbsolutePath().normalize();
        Files.createDirectories(uploadPath);

        for (MultipartFile file : files) {
            String ext = StringUtils.getFilenameExtension(file.getOriginalFilename());
            String fileName = UUID.randomUUID() + (ext != null && !ext.isBlank() ? "." + ext : "");
            Path targetLocation = uploadPath.resolve(fileName);
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);

            FileMetadataDocument fileMetadata = FileMetadataDocument.builder()
                    .fileLocation(targetLocation.toString())
                    .name(file.getOriginalFilename())
                    .size(file.getSize())
                    .type(file.getContentType())
                    .clerkId(currentProfile.getClerkId())
                    .isPublic(false)
                    .status("READY")
                    .uploadedAt(LocalDateTime.now())
                    .build();

            FileMetadataDocument savedDoc = fileMetadataRepository.save(fileMetadata);
            savedFiles.add(savedDoc);

            fileEventPublisher.publishFileUploadedEvent(FileUploadedEvent.builder()
                    .fileId(savedDoc.getId())
                    .clerkId(savedDoc.getClerkId())
                    .fileName(savedDoc.getName())
                    .fileType(savedDoc.getType())
                    .s3Key(savedDoc.getS3Key())
                    .localPath(savedDoc.getFileLocation())
                    .timestamp(LocalDateTime.now())
                    .build());
        }
        return savedFiles.stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    /**
     * Returns all active files for the authenticated user (filters out FAILED and PENDING_UPLOAD).
     */
    public List<FileMetadataDTO> getFiles() {
        ProfileDocument currentProfile = profileService.getCurrentProfile();
        List<FileMetadataDocument> files = fileMetadataRepository.findByClerkId(currentProfile.getClerkId());
        return files.stream()
                .filter(f -> !"FAILED".equals(f.getStatus()) && !"PENDING_UPLOAD".equals(f.getStatus()))
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    public FileMetadataDTO getPublicFile(String id) {
        FileMetadataDocument document = fileMetadataRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("File not found"));

        if (!Boolean.TRUE.equals(document.getIsPublic())) {
            throw new UnauthorizedAccessException("This file is private.");
        }

        return mapToDTO(document);
    }

    /**
     * Retrieves downloadable file verifying ownership or public visibility.
     */
    public FileMetadataDTO getDownloadableFile(String id) {
        FileMetadataDocument file = fileMetadataRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("File not found with ID: " + id));

        boolean isOwner = false;
        try {
            ProfileDocument currentProfile = profileService.getCurrentProfile();
            isOwner = file.getClerkId().equals(currentProfile.getClerkId());
        } catch (Exception ignored) {
            // Unauthenticated or different principal
        }

        if (!Boolean.TRUE.equals(file.getIsPublic()) && !isOwner) {
            throw new UnauthorizedAccessException("You do not have permission to download this private file.");
        }

        return mapToDTO(file);
    }

    public void deleteFile(String id) {
        ProfileDocument currentProfile = profileService.getCurrentProfile();
        FileMetadataDocument file = fileMetadataRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("File not found with ID: " + id));

        if (!file.getClerkId().equals(currentProfile.getClerkId())) {
            throw new UnauthorizedAccessException("You do not have permission to delete this file.");
        }

        // Delete from S3 if s3Key exists
        if (file.getS3Key() != null) {
            s3StorageService.deleteObject(file.getS3Key());
        }

        // Delete from local disk if fileLocation exists
        if (file.getFileLocation() != null) {
            try {
                Path filePath = Paths.get(file.getFileLocation());
                Files.deleteIfExists(filePath);
            } catch (Exception e) {
                log.warn("Failed to delete local file: {}", file.getFileLocation(), e);
            }
        }

        fileMetadataRepository.deleteById(id);
    }

    public FileMetadataDTO togglePublic(String id) {
        ProfileDocument currentProfile = profileService.getCurrentProfile();
        FileMetadataDocument file = fileMetadataRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("File not found with ID: " + id));

        if (!file.getClerkId().equals(currentProfile.getClerkId())) {
            throw new UnauthorizedAccessException("You do not have permission to modify this file's sharing settings.");
        }

        file.setIsPublic(!Boolean.TRUE.equals(file.getIsPublic()));
        fileMetadataRepository.save(file);
        return mapToDTO(file);
    }

    private FileMetadataDTO mapToDTO(FileMetadataDocument doc) {
        String downloadUrl = null;
        if (doc.getS3Key() != null) {
            // Generate a secure 1-hour pre-signed download link
            downloadUrl = s3StorageService.generatePresignedDownloadUrl(doc.getS3Key(), doc.getName(), Duration.ofHours(1));
        }

        return FileMetadataDTO.builder()
                .id(doc.getId())
                .fileLocation(doc.getFileLocation())
                .s3Key(doc.getS3Key())
                .name(doc.getName())
                .size(doc.getSize())
                .type(doc.getType())
                .clerkId(doc.getClerkId())
                .isPublic(doc.getIsPublic())
                .status(doc.getStatus() != null ? doc.getStatus() : "READY")
                .checksumSha256(doc.getChecksumSha256())
                .ragIndexed(doc.getRagIndexed() != null ? doc.getRagIndexed() : false)
                .ragStatus(doc.getRagStatus() != null ? doc.getRagStatus() : "PENDING")
                .ragChunkCount(doc.getRagChunkCount() != null ? doc.getRagChunkCount() : 0)
                .downloadUrl(downloadUrl)
                .uploadedAt(doc.getUploadedAt())
                .build();
    }
}
