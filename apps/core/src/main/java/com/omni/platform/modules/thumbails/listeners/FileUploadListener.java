package com.omni.platform.modules.thumbails.listeners;

import com.omni.platform.core.constants.ImageConstant;
import com.omni.platform.core.events.FileUploadedEvent;
import com.omni.platform.modules.thumbails.services.ThumbnailService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

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