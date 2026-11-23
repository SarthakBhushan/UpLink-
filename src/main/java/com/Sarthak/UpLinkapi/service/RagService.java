package com.Sarthak.UpLinkapi.service;

import com.Sarthak.UpLinkapi.ai.GroqClient;
import com.Sarthak.UpLinkapi.ai.OllamaClient;
import com.Sarthak.UpLinkapi.documents.DocumentChunk;
import com.Sarthak.UpLinkapi.documents.FileMetadataDocument;
import com.Sarthak.UpLinkapi.documents.ProfileDocument;
import com.Sarthak.UpLinkapi.dto.ChatRequestDTO;
import com.Sarthak.UpLinkapi.dto.ChatResponseDTO;
import com.Sarthak.UpLinkapi.dto.ChatSourceDTO;
import com.Sarthak.UpLinkapi.repository.DocumentChunkRepository;
import com.Sarthak.UpLinkapi.repository.FileMetadataRepository;
import com.Sarthak.UpLinkapi.util.DocumentProcessingUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final FileMetadataRepository fileMetadataRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final ProfileService profileService;
    private final OllamaClient ollamaClient;
    private final GroqClient groqClient;

    // Minimum cosine similarity to include a chunk in context
    private static final double MIN_RELEVANCE_SCORE = 0.30;
    // Max chunks to send to the LLM
    private static final int TOP_K = 5;

    /**
     * Retrieval-Augmented Generation over an uploaded document.
     *
     * Completion engine priority:
     *   1. Groq API  — if GROQ_API_KEY is set (cloud, fast, works on Render)
     *   2. Ollama    — fallback for fully local dev environments
     *
     * Embedding (query vectorisation):
     *   - Uses local Ollama nomic-embed-text.
     *   - If Ollama is offline (e.g. on Render), falls back to no-context mode:
     *     Groq answers the question without retrieved document chunks.
     */
    public ChatResponseDTO chatWithDocument(String fileId, ChatRequestDTO request) {
        long startTime = System.currentTimeMillis();
        ProfileDocument currentProfile = profileService.getCurrentProfile();

        FileMetadataDocument document = fileMetadataRepository.findById(fileId)
                .orElseThrow(() -> new RuntimeException("Document not found with ID: " + fileId));

        if (!document.getClerkId().equals(currentProfile.getClerkId())
                && !Boolean.TRUE.equals(document.getIsPublic())) {
            throw new RuntimeException("Unauthorized: You do not have permission to query this document.");
        }

        List<DocumentChunk> chunks = documentChunkRepository.findByFileIdOrderByChunkIndexAsc(fileId);

        // ------------------------------------------------------------------
        // 1. Attempt vector retrieval (requires Ollama for query embedding)
        // ------------------------------------------------------------------
        boolean ollamaAvailable = ollamaClient.isReachable();
        List<ScoredChunk> topChunks = new ArrayList<>();

        if (!chunks.isEmpty() && ollamaAvailable) {
            List<Double> queryEmbedding = ollamaClient.generateEmbedding(request.getPrompt());

            topChunks = chunks.stream()
                    .map(c -> new ScoredChunk(c,
                            DocumentProcessingUtil.cosineSimilarity(queryEmbedding, c.getEmbedding())))
                    .filter(sc -> sc.score >= MIN_RELEVANCE_SCORE)
                    .sorted((a, b) -> Double.compare(b.score, a.score))
                    .limit(TOP_K)
                    .collect(Collectors.toList());

            // Graceful degradation: below threshold → take best 3 anyway
            if (topChunks.isEmpty()) {
                topChunks = chunks.stream()
                        .map(c -> new ScoredChunk(c,
                                DocumentProcessingUtil.cosineSimilarity(queryEmbedding, c.getEmbedding())))
                        .sorted((a, b) -> Double.compare(b.score, a.score))
                        .limit(3)
                        .collect(Collectors.toList());
            }

            log.info("RAG [full-context] file='{}', chunks={}, top_score={}",
                    document.getName(), topChunks.size(),
                    topChunks.isEmpty() ? 0 : String.format("%.3f", topChunks.get(0).score));

        } else if (!ollamaAvailable) {
            log.info("RAG [no-context] Ollama offline — Groq will answer without vector context for file: {}",
                    document.getName());
        }

        // ------------------------------------------------------------------
        // 2. If document not indexed yet, return early with a helpful message
        // ------------------------------------------------------------------
        if (chunks.isEmpty()) {
            return ChatResponseDTO.builder()
                    .fileId(fileId)
                    .answer("This document has not been indexed for AI analysis yet. " +
                            "It may still be processing — please wait a moment and try again, " +
                            "or ensure the document contains readable text.")
                    .sources(Collections.emptyList())
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .build();
        }

        // ------------------------------------------------------------------
        // 3. Build system prompt (RAG context or no-context fallback)
        // ------------------------------------------------------------------
        String systemPrompt;

        if (!topChunks.isEmpty()) {
            // Full RAG mode — inject retrieved excerpts
            StringBuilder ctx = new StringBuilder();
            for (int i = 0; i < topChunks.size(); i++) {
                ScoredChunk sc = topChunks.get(i);
                ctx.append("--- Excerpt ").append(i + 1)
                        .append(" (Chunk #").append(sc.chunk.getChunkIndex() + 1)
                        .append(", relevance ").append(String.format("%.1f", sc.score * 100)).append("%) ---\n")
                        .append(sc.chunk.getText().trim())
                        .append("\n\n");
            }
            systemPrompt =
                    "You are an expert document analysis assistant for UpLink.\n" +
                    "Answer the user's question accurately and concisely based ONLY on the provided excerpts.\n\n" +
                    "Rules:\n" +
                    "- Cite excerpt numbers (e.g. \"According to Excerpt 2...\") when relevant.\n" +
                    "- If the excerpts do not contain enough information, say: " +
                    "\"The document doesn't appear to mention this directly.\"\n" +
                    "- Do NOT fabricate facts or details not present in the excerpts.\n" +
                    "- Use bullet points or numbered lists when listing multiple items.\n" +
                    "- If asked for a summary, synthesise all excerpts into a coherent paragraph.\n\n" +
                    "=== DOCUMENT: " + document.getName() + " ===\n\n" + ctx;
        } else {
            // No-context mode — Ollama offline, Groq answers from general knowledge
            systemPrompt =
                    "You are an expert document assistant for UpLink. " +
                    "The vector search index is temporarily unavailable (embedding service offline). " +
                    "Answer the user's question as helpfully as possible using your general knowledge, " +
                    "and note that document-specific context could not be retrieved.\n\n" +
                    "Document name: " + document.getName();
        }

        // ------------------------------------------------------------------
        // 4. Generate completion — Groq first, Ollama as fallback
        // ------------------------------------------------------------------
        String answer;
        String engine;

        if (groqClient.isConfigured()) {
            answer = groqClient.generateCompletion(systemPrompt, request.getPrompt());
            engine = "groq";
        } else if (ollamaAvailable) {
            answer = ollamaClient.generateCompletion(systemPrompt, request.getPrompt());
            engine = "ollama";
        } else {
            return ChatResponseDTO.builder()
                    .fileId(fileId)
                    .answer("No AI engine is available. " +
                            "Please set the GROQ_API_KEY environment variable or start Ollama locally.")
                    .sources(Collections.emptyList())
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .build();
        }

        // ------------------------------------------------------------------
        // 5. Build and return response
        // ------------------------------------------------------------------
        List<ChatSourceDTO> sources = topChunks.stream()
                .map(sc -> ChatSourceDTO.builder()
                        .text(sc.chunk.getText())
                        .score(Math.round(sc.score * 1000.0) / 1000.0)
                        .chunkIndex(sc.chunk.getChunkIndex())
                        .build())
                .collect(Collectors.toList());

        long latency = System.currentTimeMillis() - startTime;
        log.info("RAG done: engine={}, latency={}ms, sources={}", engine, latency, sources.size());

        return ChatResponseDTO.builder()
                .fileId(fileId)
                .answer(answer.trim())
                .sources(sources)
                .latencyMs(latency)
                .build();
    }

    private static class ScoredChunk {
        final DocumentChunk chunk;
        final double score;

        ScoredChunk(DocumentChunk chunk, double score) {
            this.chunk = chunk;
            this.score = score;
        }
    }
}
