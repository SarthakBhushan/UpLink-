package com.Sarthak.UpLinkapi.documents;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "files")
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Data
public class FileMetadataDocument {

    @Id
    private String id;
    private String name;
    private String type;
    private Long size;
    private String clerkId;
    private Boolean isPublic;
    private String fileLocation;
    private String s3Key;
    private String status; // PENDING_UPLOAD, UPLOADED, PROCESSING, READY, FAILED
    private String checksumSha256;
    private Boolean ragIndexed;
    private String ragStatus; // PENDING, INDEXED, INDEX_FAILED, SKIPPED
    private Integer ragChunkCount;
    private LocalDateTime uploadedAt;

}
