package com.Sarthak.UpLinkapi.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FileMetadataDTO {

    private String id;
    private String name;
    private String type;
    private Long size;
    private String clerkId;
    private Boolean isPublic;
    private String fileLocation;
    private String s3Key;
    private String status;
    private String checksumSha256;
    private Boolean ragIndexed;
    private String ragStatus;
    private Integer ragChunkCount;
    private String downloadUrl;
    private LocalDateTime uploadedAt;

}
