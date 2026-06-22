package com.omni.platform.modules.thumbnails.listeners;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.thumbnails.services.ThumbnailService;
import com.omni.platform.shared.constants.ImageConstant;
import com.omni.platform.shared.events.FileUploadedEvent;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@AllArgsConstructor
public class FileUploadListener {
    private final ThumbnailService thumbnailService;

    @Async
    @ApplicationModuleListener
    public void onFileUploaded(FileUploadedEvent event) {
        log.info("Received FileUploadedEvent - FileId: {}, ContentType: {}",
                event.fileId(), event.contentType());

        if (isImageFile(event.contentType())) {
            log.info("Creating thumbnail for image file: {}", event.fileId());
            thumbnailService.createThumbnail(event);
        } else {
            log.debug("Skipping non-image file: {} (type: {})",
                    event.fileId(), event.contentType());
        }
    }

    private boolean isImageFile(String contentType) {
        return contentType != null &&
                (ImageConstant.SUPPORTED_IMAGE_TYPES.contains(contentType.toLowerCase()) ||
                        contentType.toLowerCase().startsWith("image/"));
    }
}