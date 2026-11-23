package com.Sarthak.UpLinkapi.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FileEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * Publishes a file uploaded event asynchronously to decoupled workers.
     */
    public void publishFileUploadedEvent(FileUploadedEvent event) {
        log.info("Publishing FileUploadedEvent for fileId: {}, name: {}", event.getFileId(), event.getFileName());
        applicationEventPublisher.publishEvent(event);
    }
}
