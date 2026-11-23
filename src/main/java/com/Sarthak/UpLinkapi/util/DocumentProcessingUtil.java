package com.Sarthak.UpLinkapi.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
public class DocumentProcessingUtil {

    private static final int CHUNK_SIZE = 600; // characters per chunk
    private static final int CHUNK_OVERLAP = 100; // character overlap

    /**
     * Extracts plain text from an InputStream based on content type or filename.
     */
    public static String extractText(InputStream inputStream, String fileName, String contentType) {
        try {
            String lower = fileName != null ? fileName.toLowerCase() : "";

            boolean isPdf = lower.endsWith(".pdf") ||
                    (contentType != null && contentType.toLowerCase().contains("pdf"));

            if (isPdf) {
                byte[] bytes = inputStream.readAllBytes();
                try (PDDocument document = Loader.loadPDF(bytes)) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    return stripper.getText(document);
                }
            } else if (lower.endsWith(".docx")) {
                // Extract text from DOCX (Office Open XML): read word/document.xml inside the zip
                return extractDocxText(inputStream);
            } else if (lower.endsWith(".ipynb")) {
                // Jupyter notebooks are JSON — extract source cells
                String raw = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                return extractIpynbText(raw);
            } else {
                // Plain text, markdown, csv, json, code files
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.error("Failed to extract text from file: {}", fileName, e);
            return "";
        }
    }

    /**
     * Extracts plain text from a .docx file by reading word/document.xml inside the zip.
     * Avoids requiring Apache POI — uses standard Java ZipInputStream.
     */
    private static String extractDocxText(InputStream inputStream) {
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(inputStream)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    String xmlContent = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                    // Strip all XML tags, keep only text content
                    return xmlContent.replaceAll("<[^>]+>", " ")
                            .replaceAll("\\s+", " ")
                            .trim();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract text from DOCX: {}", e.getMessage());
        }
        return "";
    }

    /**
     * Extracts code and markdown source cells from a Jupyter Notebook (.ipynb) JSON.
     */
    private static String extractIpynbText(String json) {
        try {
            // Simple regex extraction of "source" arrays — avoids full JSON library dependency
            StringBuilder sb = new StringBuilder();
            Pattern sourcePattern = Pattern.compile("\"source\":\\s*\\[(.*?)]", Pattern.DOTALL);
            java.util.regex.Matcher m = sourcePattern.matcher(json);
            while (m.find()) {
                String sourceBlock = m.group(1);
                // Remove JSON string formatting: quotes, escape chars, commas between lines
                String cleaned = sourceBlock
                        .replaceAll("\"\\s*,\\s*\"", "")
                        .replaceAll("\"\\s*\\]", "")
                        .replaceAll("\\[\\s*\"", "")
                        .replace("\\n", "\n")
                        .replace("\\t", "\t")
                        .replace("\\\"", "\"")
                        .replaceAll("^\"|\"$", "")
                        .trim();
                if (!cleaned.isBlank()) {
                    sb.append(cleaned).append("\n\n");
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("Failed to parse .ipynb notebook: {}", e.getMessage());
            return json; // fall back to raw JSON as text
        }
    }

    /**
     * Splits raw text into semantic overlapping chunks.
     */
    public static List<String> chunkText(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        String cleanedText = text.replaceAll("\\r\\n", "\n").replaceAll("\\s+", " ").trim();
        int length = cleanedText.length();

        if (length <= CHUNK_SIZE) {
            chunks.add(cleanedText);
            return chunks;
        }

        int start = 0;
        while (start < length) {
            int end = Math.min(start + CHUNK_SIZE, length);

            // Attempt to break at nearest sentence or space if not at end of text
            if (end < length) {
                int lastPeriod = cleanedText.lastIndexOf('.', end);
                int lastSpace = cleanedText.lastIndexOf(' ', end);

                if (lastPeriod > start + (CHUNK_SIZE / 2)) {
                    end = lastPeriod + 1;
                } else if (lastSpace > start + (CHUNK_SIZE / 2)) {
                    end = lastSpace;
                }
            }

            String chunk = cleanedText.substring(start, end).trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }

            if (end >= length) {
                break;
            }

            start = Math.max(start + 1, end - CHUNK_OVERLAP);
        }

        return chunks;
    }

    /**
     * Computes the Cosine Similarity between two N-dimensional embedding vectors.
     */
    public static double cosineSimilarity(List<Double> vecA, List<Double> vecB) {
        if (vecA == null || vecB == null || vecA.size() != vecB.size() || vecA.isEmpty()) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < vecA.size(); i++) {
            double a = vecA.get(i);
            double b = vecB.get(i);
            dotProduct += a * b;
            normA += a * a;
            normB += b * b;
        }

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
