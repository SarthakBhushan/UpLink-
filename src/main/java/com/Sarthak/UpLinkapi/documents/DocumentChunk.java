package com.Sarthak.UpLinkapi.documents;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Document(collection = "document_chunks")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DocumentChunk {

    @Id
    private String id;

    @Indexed
    private String fileId;

    @Indexed
    private String clerkId;

    private Integer chunkIndex;

    private String text;

    private List<Double> embedding;

    private LocalDateTime createdAt;
}
