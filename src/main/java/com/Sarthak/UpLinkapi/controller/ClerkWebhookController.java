package com.Sarthak.UpLinkapi.controller;


import com.Sarthak.UpLinkapi.dto.ProfileDTO;
import com.Sarthak.UpLinkapi.service.ProfileService;
import com.Sarthak.UpLinkapi.service.UserCreditsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Slf4j
@RestController
@RequestMapping("/webhooks")
@RequiredArgsConstructor
public class ClerkWebhookController {

    @Value("${clerk.webhook.secret}")
    private String webhookSecret;

    private final ProfileService profileService;
    private final UserCreditsService userCreditsService;

    @PostMapping("/clerk")
    public ResponseEntity<?> handleClerkWebhook(@RequestHeader("svix-id") String svixId,
                                                @RequestHeader("svix-timestamp") String svixTimestamp,
                                                @RequestHeader("svix-signature") String svixSignature,
                                                @RequestBody String payload) {
        try {
            boolean isValid = verifyWebhookSignature(svixId, svixTimestamp, svixSignature, payload);
            if (!isValid) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid webhook signature");
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(payload);

            String eventType = rootNode.path("type").asText();

            switch (eventType) {
                case "user.created":
                    handleUserCreated(rootNode.path("data"));
                    break;
                case "user.updated":
                    handleUserUpdated(rootNode.path("data"));
                    break;
                case "user.deleted":
                    handleUserDeleted(rootNode.path("data"));
                    break;
            }
            return ResponseEntity.ok().build();
        }catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    private void handleUserDeleted(JsonNode data) {
        String clerkId = data.path("id").asText();
        profileService.deleteProfile(clerkId);
    }

    private void handleUserUpdated(JsonNode data) {
        String clerkId = data.path("id").asText();

        // Extract user information
        String email = "";
        JsonNode emailAddresses = data.path("email_addresses");
        if (emailAddresses.isArray() && emailAddresses.size() > 0) {
            email = emailAddresses.get(0).path("email_address").asText();
        }

        String firstName = data.path("first_name").asText("");
        String lastName = data.path("last_name").asText("");
        String photoUrl = data.path("image_url").asText("");

        ProfileDTO updatedProfile = ProfileDTO.builder()
                .clerkId(clerkId)
                .firstName(firstName)
                .lastName(lastName)
                .email(email)
                .photoUrl(photoUrl)
                .build();

        updatedProfile = profileService.updateProfile(updatedProfile);

        if (updatedProfile == null) {
            handleUserCreated(data);
        }

    }

    private void handleUserCreated(JsonNode data) {
        String clerkId = data.path("id").asText();

        String email = "";
        JsonNode emailAddresses = data.path("email_addresses");
        if (emailAddresses.isArray() && emailAddresses.size() > 0) {
            email = emailAddresses.get(0).path("email_address").asText();
        }

        String firstName = data.path("first_name").asText("");
        String lastName = data.path("last_name").asText("");
        String photoUrl = data.path("image_url").asText("");

        ProfileDTO newProfile = ProfileDTO.builder()
                .clerkId(clerkId)
                .firstName(firstName)
                .lastName(lastName)
                .email(email)
                .photoUrl(photoUrl)
                .build();

        profileService.createProfile(newProfile);
        userCreditsService.createInitialCredits(clerkId);
    }

    /**
     * Svix webhook signature verification (HMAC-SHA256).
     *
     * Svix signs webhooks by computing:
     *   HMAC-SHA256(key=base64_decode(secret_after_prefix), msg="{svix-id}.{svix-timestamp}.{payload}")
     * and base64-encoding the result. The svix-signature header contains one or more
     * comma-separated "v1,<base64>" values — any one matching is sufficient.
     *
     * Docs: https://docs.svix.com/receiving/verifying-payloads/how
     */
    private boolean verifyWebhookSignature(String svixId, String svixTimestamp, String svixSignature, String payload) {
        try {
            // Svix secrets are prefixed with "whsec_" — strip it before base64-decoding
            String secret = webhookSecret.startsWith("whsec_")
                    ? webhookSecret.substring("whsec_".length())
                    : webhookSecret;
            byte[] keyBytes = Base64.getDecoder().decode(secret);

            // Message to sign: "{svix-id}.{svix-timestamp}.{raw-body}"
            String message = svixId + "." + svixTimestamp + "." + payload;
            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            String computedSignature = Base64.getEncoder().encodeToString(mac.doFinal(messageBytes));

            // svix-signature header: "v1,<base64sig1> v1,<base64sig2> ..."  (space or comma separated)
            for (String part : svixSignature.split("[ ,]+")) {
                if (part.startsWith("v1,")) {
                    String incomingSig = part.substring("v1,".length());
                    if (computedSignature.equals(incomingSig)) {
                        return true;
                    }
                }
            }

            log.warn("Webhook signature mismatch — request rejected");
            return false;

        } catch (Exception e) {
            log.error("Webhook signature verification error: {}", e.getMessage());
            return false;
        }
    }
}
