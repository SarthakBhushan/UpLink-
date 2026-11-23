package com.Sarthak.UpLinkapi.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Client for Groq's OpenAI-compatible Chat Completions API.
 * Used as the primary LLM backend when deployed to cloud (Render, etc.)
 * where local Ollama is not available.
 *
 * Models available (free tier):
 *   - llama-3.1-8b-instant   : Best speed/quality balance for RAG
 *   - llama3-70b-8192         : Highest quality, slower
 *   - mixtral-8x7b-32768     : Large context window (32K tokens)
 *
 * Free tier limits: 14,400 req/day, 6,000 tokens/min
 * Docs: https://console.groq.com/docs/openai
 */
@Slf4j
@Component
public class GroqClient {

    @Value("${groq.api-key:}")
    private String apiKey;

    @Value("${groq.model:llama-3.1-8b-instant}")
    private String model;

    private static final String BASE_URL = "https://api.groq.com/openai/v1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Returns true if a Groq API key is configured.
     * Used to decide whether to use Groq or fall back to local Ollama.
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Generates a grounded text completion via Groq Chat Completions API.
     * Tuned for RAG accuracy:
     * - temperature 0.2: low randomness → factual, document-grounded answers
     * - max_tokens 512: caps response at ~400 words
     * - top_p 0.85: nucleus sampling to reduce hallucination probability
     *
     * @param systemPrompt  The RAG context + rules injected as system message
     * @param userPrompt    The user's natural-language question
     * @return              The LLM-generated answer string
     */
    public String generateCompletion(String systemPrompt, String userPrompt) {
        if (!isConfigured()) {
            throw new RuntimeException(
                "Groq API key not configured. Set GROQ_API_KEY environment variable."
            );
        }

        try {
            String url = BASE_URL + "/chat/completions";

            // Build OpenAI-compatible messages array
            Map<String, Object> systemMsg = new LinkedHashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemPrompt);

            Map<String, Object> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);

            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", model);
            request.put("messages", List.of(systemMsg, userMsg));
            request.put("temperature", 0.2);
            request.put("max_tokens", 512);
            request.put("top_p", 0.85);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());

            // Log token usage for metrics/cost tracking
            if (root.has("usage")) {
                JsonNode usage = root.path("usage");
                log.info("Groq [{}]: {}+{} tokens (prompt+completion), model={}",
                        root.path("id").asText(""),
                        usage.path("prompt_tokens").asInt(),
                        usage.path("completion_tokens").asInt(),
                        model);
            }

            return root.path("choices").get(0).path("message").path("content").asText();

        } catch (Exception e) {
            log.error("Groq completion failed: {}", e.getMessage());
            throw new RuntimeException("Groq LLM completion failed: " + e.getMessage());
        }
    }
}
