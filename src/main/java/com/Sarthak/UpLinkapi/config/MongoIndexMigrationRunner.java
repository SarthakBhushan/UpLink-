package com.Sarthak.UpLinkapi.config;

import com.Sarthak.UpLinkapi.documents.DocumentChunk;
import com.Sarthak.UpLinkapi.documents.FileMetadataDocument;
import com.Sarthak.UpLinkapi.documents.ProfileDocument;
import com.Sarthak.UpLinkapi.documents.UserCredits;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MongoIndexMigrationRunner implements ApplicationRunner {

    private final MongoTemplate mongoTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            log.info("Verifying and migrating MongoDB database indexes...");

            IndexOperations profileIndexes = mongoTemplate.indexOps(ProfileDocument.class);

            // Safely drop old conflicting non-sparse email index if present
            try {
                profileIndexes.getIndexInfo().forEach(idx -> {
                    if ("email".equals(idx.getName()) && !idx.isSparse()) {
                        log.info("Dropping legacy non-sparse 'email' index from profiles collection...");
                        profileIndexes.dropIndex("email");
                    }
                });
            } catch (Exception e) {
                log.debug("Index check: {}", e.getMessage());
            }

            // Ensure unique sparse email index (ignores null emails)
            try {
                profileIndexes.ensureIndex(new Index().on("email", Sort.Direction.ASC).unique().sparse());
            } catch (Exception e) {
                log.debug("Sparse email index creation: {}", e.getMessage());
            }

            // Ensure unique index on clerkId
            try {
                profileIndexes.ensureIndex(new Index().on("clerkId", Sort.Direction.ASC).unique());
            } catch (Exception e) {
                log.debug("ClerkId index creation: {}", e.getMessage());
            }

            // Ensure index on user_credits
            try {
                IndexOperations creditIndexes = mongoTemplate.indexOps(UserCredits.class);
                creditIndexes.ensureIndex(new Index().on("clerkId", Sort.Direction.ASC).unique());
            } catch (Exception e) {
                log.debug("UserCredits index creation: {}", e.getMessage());
            }

            // Ensure index on files
            try {
                IndexOperations fileIndexes = mongoTemplate.indexOps(FileMetadataDocument.class);
                fileIndexes.ensureIndex(new Index().on("clerkId", Sort.Direction.ASC));
            } catch (Exception e) {
                log.debug("Files index creation: {}", e.getMessage());
            }

            // Ensure index on document chunks
            try {
                IndexOperations chunkIndexes = mongoTemplate.indexOps(DocumentChunk.class);
                chunkIndexes.ensureIndex(new Index().on("fileId", Sort.Direction.ASC));
                chunkIndexes.ensureIndex(new Index().on("clerkId", Sort.Direction.ASC));
            } catch (Exception e) {
                log.debug("DocumentChunk index creation: {}", e.getMessage());
            }

            log.info("MongoDB index verification and migration completed successfully.");
        } catch (Exception e) {
            log.warn("Database index check finished with warning: {}", e.getMessage());
        }
    }
}
