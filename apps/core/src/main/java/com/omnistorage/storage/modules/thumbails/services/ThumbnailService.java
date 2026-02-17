package com.omnistorage.storage.modules.thumbails.services;

import com.omnistorage.storage.core.configs.StorageProviderRegistry;
import com.omnistorage.storage.core.events.FileUploadedEvent;
import com.omnistorage.storage.core.ports.ReadablePort;
import com.omnistorage.storage.core.ports.WritablePort;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

@Slf4j
@Service
@AllArgsConstructor
public class ThumbnailService {
    private final StorageProviderRegistry registry;

    public void createThumbnail(FileUploadedEvent event) {
        ReadablePort readablePort = registry.getPort(event.provider(), ReadablePort.class);
        WritablePort writablePort = registry.getPort(event.provider(), WritablePort.class);

        log.info("Start creating thumbnail for fileId: {}", event.fileId());
        try (InputStream is = readablePort.read(event.bucket(), event.fileId())) {
            ByteArrayOutputStream os = new ByteArrayOutputStream();

            // Use JPEG for photos, PNG for images with transparency
            String outputFormat = determineOutputFormat(event.contentType());

            Thumbnails.of(is)
                    .size(200, 200)
                    .outputQuality(0.8) // 80% quality - good balance
                    .outputFormat(outputFormat)
                    .toOutputStream(os);

            byte[] thumbBytes = os.toByteArray();

            // Store thumbnail
            try (InputStream thumbIs = new ByteArrayInputStream(thumbBytes)) {
                String thumbId = "thumb_" + event.fileId();
                String thumbContentType = "image/" + outputFormat;

                writablePort.write(event.bucket(), thumbId, thumbIs, thumbContentType);

                log.info("Thumbnail created: {} (format: {}, size: {} bytes)",
                        thumbId, outputFormat, thumbBytes.length);
            }
        } catch (IOException e) {
            log.error("Error creating thumbnail for fileId: {}", event.fileId(), e);
        }
    }

    private String determineOutputFormat(String contentType) {
        // Use PNG for images that might have transparency
        if (contentType != null &&
                (contentType.contains("png") || contentType.contains("gif"))) {
            return "png";
        }
        // Default to JPEG for photos (smaller file size)
        return "jpg";
    }
}