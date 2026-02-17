package com.omni.platform.core.events;

import com.omni.platform.core.enums.StorageProvider;

public record FileUploadedEvent(
        String fileId,
        String bucket,
        String contentType,
        StorageProvider provider
) {}