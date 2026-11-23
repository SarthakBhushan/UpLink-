package com.Sarthak.UpLinkapi.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class OllamaClient {

    @Value("${ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${ollama.model.chat:qwen2.5:1.5b}")
    private String chatModel;

    @Value("${ollama.model.embedding:nomic-embed-text}")
    private String embeddingModel;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Fast non-blocking health check to see if Ollama server is reachable.
     */
    public boolean isReachable() {
        try {
            restTemplate.getForObject(baseUrl + "/api/tags", String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Generates a 768-dimensional dense vector embedding using nomic-embed-text via Ollama.
     */
    public List<Double> generateEmbedding(String text) {
        try {
            String url = baseUrl + "/api/embeddings";
            Map<String, Object> request = new HashMap<>();
            request.put("model", embeddingModel);
            request.put("prompt", text);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode embeddingNode = root.path("embedding");

            List<Double> embedding = new ArrayList<>();
            if (embeddingNode.isArray()) {
                for (JsonNode val : embeddingNode) {
                    embedding.add(val.asDouble());
                }
            }
            return embedding;
        } catch (Exception e) {
            log.warn("Ollama embedding failed for text snippet: {}", e.getMessage());
            throw new RuntimeException("Embedding generation failed: " + e.getMessage());
        }
    }

    /**
     * Generates text reasoning completion using Qwen via Ollama.
     * Tuned inference parameters for accurate, focused, document-grounded answers:
     * - temperature 0.2: low randomness → factual, deterministic responses
     * - top_p 0.85: nucleus sampling to avoid hallucinated tokens
     * - top_k 40: limits vocabulary to most probable tokens
     * - num_predict 512: caps max output at ~400 words
     * - repeat_penalty 1.1: reduces repetitive phrasing
     */
    public String generateCompletion(String systemPrompt, String userPrompt) {
        try {
            String url = baseUrl + "/api/generate";

            // Inference parameters — optimised for RAG grounding quality
            Map<String, Object> options = new HashMap<>();
            options.put("temperature", 0.2);    // Low → factual, grounded output
            options.put("top_p", 0.85);          // Nucleus sampling
            options.put("top_k", 40);            // Token probability cutoff
            options.put("num_predict", 512);     // Max ~400 word response
            options.put("repeat_penalty", 1.1);  // Reduce repetition

            Map<String, Object> request = new HashMap<>();
            request.put("model", chatModel);
            request.put("system", systemPrompt);
            request.put("prompt", userPrompt);
            request.put("stream", false);
            request.put("options", options);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            // Log token usage for metrics tracking
            if (root.has("eval_count")) {
                log.info("Qwen inference: {} tokens generated, {} tokens/sec eval rate",
                        root.path("eval_count").asInt(),
                        root.has("eval_duration") ?
                                String.format("%.1f", root.path("eval_count").asDouble() /
                                        (root.path("eval_duration").asLong() / 1_000_000_000.0)) : "N/A");
            }

            return root.path("response").asText();
        } catch (Exception e) {
            log.warn("Ollama completion generation failed: {}", e.getMessage());
            throw new RuntimeException("LLM completion failed: " + e.getMessage());
        }
    }
}
