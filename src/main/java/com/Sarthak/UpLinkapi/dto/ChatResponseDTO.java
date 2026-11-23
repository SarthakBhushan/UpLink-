package com.Sarthak.UpLinkapi.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatResponseDTO {
    private String fileId;
    private String answer;
    private List<ChatSourceDTO> sources;
    private Long latencyMs;
}
