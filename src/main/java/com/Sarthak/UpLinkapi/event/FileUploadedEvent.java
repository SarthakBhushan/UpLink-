package com.Sarthak.UpLinkapi.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FileUploadedEvent {
    private String fileId;
    private String clerkId;
    private String fileName;
    private String fileType;
    private String s3Key;
    private String localPath;
    private LocalDateTime timestamp;
}
