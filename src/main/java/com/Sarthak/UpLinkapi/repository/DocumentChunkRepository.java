package com.Sarthak.UpLinkapi.repository;

import com.Sarthak.UpLinkapi.documents.DocumentChunk;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentChunkRepository extends MongoRepository<DocumentChunk, String> {
    List<DocumentChunk> findByFileIdOrderByChunkIndexAsc(String fileId);
    void deleteByFileId(String fileId);
}
