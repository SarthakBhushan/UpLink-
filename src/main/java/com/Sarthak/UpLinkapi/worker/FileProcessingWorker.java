package com.Sarthak.UpLinkapi.worker;

import com.Sarthak.UpLinkapi.ai.OllamaClient;
import com.Sarthak.UpLinkapi.documents.DocumentChunk;
import com.Sarthak.UpLinkapi.documents.FileMetadataDocument;
import com.Sarthak.UpLinkapi.event.FileUploadedEvent;
import com.Sarthak.UpLinkapi.repository.DocumentChunkRepository;
import com.Sarthak.UpLinkapi.repository.FileMetadataRepository;
import com.Sarthak.UpLinkapi.service.S3StorageService;
import com.Sarthak.UpLinkapi.util.DocumentProcessingUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class FileProcessingWorker {

    private final FileMetadataRepository fileMetadataRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final S3StorageService s3StorageService;
    private final OllamaClient ollamaClient;

    private static final long MAX_RAG_INDEX_SIZE_BYTES = 15L * 1024 * 1024; // 15MB cap for text extraction

    /**
     * Listens for FileUploadedEvent asynchronously.
     * Decouples heavy file processing (integrity hashing, parsing, embedding) from the HTTP thread pool.
     */
    @Async
    @EventListener
    public void handleFileUploadedEvent(FileUploadedEvent event) {
        log.info("Processing asynchronous background tasks for fileId: {}", event.getFileId());

        FileMetadataDocument document = fileMetadataRepository.findById(event.getFileId()).orElse(null);
        if (document == null) {
            log.warn("File record not found for background processing: {}", event.getFileId());
            return;
        }

        try {
            document.setStatus("PROCESSING");
            fileMetadataRepository.save(document);

            // 1. Calculate SHA-256 Checksum via streaming to avoid heap spikes
            String sha256 = streamComputeSha256(document);
            if (sha256 != null) {
                document.setChecksumSha256(sha256);
            }

            // 2. Check if file is indexable for RAG (PDF, TXT, MD, CSV, JSON)
            boolean isDocument = isIndexableDocument(document.getName(), document.getType());
            if (!isDocument) {
                document.setRagStatus("SKIPPED");
                document.setRagIndexed(false);
                document.setStatus("READY");
                fileMetadataRepository.save(document);
                return;
            }

            // Check file size cap
            if (document.getSize() != null && document.getSize() > MAX_RAG_INDEX_SIZE_BYTES) {
                log.info("File {} exceeds 15MB RAG limit ({} bytes). Skipping vectorization.", document.getName(), document.getSize());
                document.setRagStatus("SKIPPED_SIZE");
                document.setRagIndexed(false);
                document.setStatus("READY");
                fileMetadataRepository.save(document);
                return;
            }

            // Check if Ollama is reachable before parsing/chunking
            if (!ollamaClient.isReachable()) {
                log.warn("Ollama is not reachable at configured endpoint. Skipping vectorization for file: {}", document.getName());
                document.setRagStatus("INDEX_FAILED");
                document.setRagIndexed(false);
                document.setStatus("READY");
                fileMetadataRepository.save(document);
                return;
            }

            log.info("Starting RAG text extraction and vector embedding for file: {}", document.getName());

            // Clear any prior chunks if reprocessing
            documentChunkRepository.deleteByFileId(document.getId());

            String extractedText = "";
            try (InputStream is = openInputStream(document)) {
                if (is != null) {
                    extractedText = DocumentProcessingUtil.extractText(is, document.getName(), document.getType());
                }
            }

            if (extractedText == null || extractedText.isBlank()) {
                log.info("No readable text found in document: {}", document.getName());
                document.setRagStatus("NO_TEXT");
                document.setRagIndexed(false);
                document.setStatus("READY");
                fileMetadataRepository.save(document);
                return;
            }

            List<String> textChunks = DocumentProcessingUtil.chunkText(extractedText);
            List<DocumentChunk> documentChunks = new ArrayList<>();
            boolean embeddingFailed = false;

            int index = 0;
            for (String chunkText : textChunks) {
                try {
                    List<Double> embedding = ollamaClient.generateEmbedding(chunkText);
                    DocumentChunk chunk = DocumentChunk.builder()
                            .fileId(document.getId())
                            .clerkId(document.getClerkId())
                            .chunkIndex(index++)
                            .text(chunkText)
                            .embedding(embedding)
                            .createdAt(LocalDateTime.now())
                            .build();
                    documentChunks.add(chunk);
                } catch (Exception e) {
                    log.warn("Ollama embedding failed for file {}. Aborting remaining chunks.", document.getName());
                    embeddingFailed = true;
                    break;
                }
            }

            if (!embeddingFailed && !documentChunks.isEmpty()) {
                documentChunkRepository.saveAll(documentChunks);
                document.setRagIndexed(true);
                document.setRagStatus("INDEXED");
                document.setRagChunkCount(documentChunks.size());
                log.info("Successfully vectorized and saved {} chunks for file: {}", documentChunks.size(), document.getName());
            } else {
                document.setRagIndexed(false);
                document.setRagStatus("INDEX_FAILED");
                document.setRagChunkCount(0);
            }

            document.setStatus("READY");
            fileMetadataRepository.save(document);
            log.info("Asynchronous background processing completed for file: {}", document.getId());

        } catch (Exception e) {
            log.error("Error during background file processing for file {}: {}", event.getFileId(), e.getMessage());
            document.setStatus("READY");
            document.setRagStatus("INDEX_FAILED");
            fileMetadataRepository.save(document);
        }
    }

    private String streamComputeSha256(FileMetadataDocument document) {
        try (InputStream is = openInputStream(document)) {
            if (is == null) return null;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            log.warn("Failed to stream-compute SHA-256 for file: {}", document.getId());
            return null;
        }
    }

    private InputStream openInputStream(FileMetadataDocument document) {
        try {
            if (document.getS3Key() != null) {
                return s3StorageService.getObjectInputStream(document.getS3Key());
            } else if (document.getFileLocation() != null) {
                return Files.newInputStream(Paths.get(document.getFileLocation()));
            }
        } catch (Exception e) {
            log.warn("Could not open input stream for document {}: {}", document.getId(), e.getMessage());
        }
        return null;
    }

    private boolean isIndexableDocument(String fileName, String contentType) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".pdf") ||
                lower.endsWith(".txt") ||
                lower.endsWith(".md") ||
                lower.endsWith(".csv") ||
                lower.endsWith(".json") ||
                lower.endsWith(".docx") ||
                lower.endsWith(".doc") ||
                lower.endsWith(".ipynb") ||
                lower.endsWith(".py") ||
                lower.endsWith(".js") ||
                lower.endsWith(".ts") ||
                lower.endsWith(".java") ||
                lower.endsWith(".html") ||
                lower.endsWith(".xml") ||
                lower.endsWith(".yaml") ||
                lower.endsWith(".yml") ||
                (contentType != null && (contentType.contains("pdf") || contentType.contains("text") ||
                        contentType.contains("json") || contentType.contains("xml")));
    }
}
