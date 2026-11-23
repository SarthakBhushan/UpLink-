package com.Sarthak.UpLinkapi.service;

import com.Sarthak.UpLinkapi.documents.UserCredits;
import com.Sarthak.UpLinkapi.exceptions.InsufficientCreditsException;
import com.Sarthak.UpLinkapi.repository.UserCreditsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserCreditsService {

    private final UserCreditsRepository userCreditsRepository;
    private final ProfileService profileService;
    private final MongoTemplate mongoTemplate;

    public UserCredits createInitialCredits(String clerkId) {
        UserCredits userCredits = UserCredits.builder()
                .clerkId(clerkId)
                .credits(5)
                .plan("BASIC")
                .build();
        return userCreditsRepository.save(userCredits);
    }

    public UserCredits getUserCredits(String clerkId) {
        return userCreditsRepository.findByClerkId(clerkId)
                .orElseGet(() -> createInitialCredits(clerkId));
    }

    public UserCredits getUserCredits() {
        String clerkId = profileService.getCurrentProfile().getClerkId();
        return getUserCredits(clerkId);
    }

    public Boolean hasEnoughCredits(int requiredCredits) {
        UserCredits userCredits = getUserCredits();
        return userCredits.getCredits() >= requiredCredits;
    }

    /**
     * Atomically consumes credits to prevent race conditions and double-spending.
     * Uses MongoDB's atomic findAndModify at document-level lock.
     */
    public UserCredits consumeCreditAtomic(String clerkId, int amount) {
        // Ensure user record exists first
        getUserCredits(clerkId);

        Query query = new Query(Criteria.where("clerkId").is(clerkId).and("credits").gte(amount));
        Update update = new Update().inc("credits", -amount);
        FindAndModifyOptions options = FindAndModifyOptions.options().returnNew(true);

        UserCredits updated = mongoTemplate.findAndModify(query, update, options, UserCredits.class);
        if (updated == null) {
            throw new InsufficientCreditsException("Insufficient credits. Required: " + amount + ". Please upgrade your plan.");
        }
        return updated;
    }

    public UserCredits consumeCredit() {
        String clerkId = profileService.getCurrentProfile().getClerkId();
        return consumeCreditAtomic(clerkId, 1);
    }

    public UserCredits addCredits(String clerkId, Integer creditsToAdd, String plan) {
        // Ensure user credits record exists
        getUserCredits(clerkId);

        Query query = new Query(Criteria.where("clerkId").is(clerkId));
        Update update = new Update().inc("credits", creditsToAdd).set("plan", plan);
        FindAndModifyOptions options = FindAndModifyOptions.options().returnNew(true);

        return mongoTemplate.findAndModify(query, update, options, UserCredits.class);
    }
}
