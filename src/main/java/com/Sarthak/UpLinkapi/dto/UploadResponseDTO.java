package com.Sarthak.UpLinkapi.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UploadResponseDTO {
    private String fileId;
    private String uploadUrl;
    private String s3Key;
    private Integer remainingCredits;
    private Long expiresInSeconds;
    private String message;
}
