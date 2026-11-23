package com.Sarthak.UpLinkapi.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UploadRequestDTO {
    private String fileName;
    private Long fileSize;
    private String contentType;
}
