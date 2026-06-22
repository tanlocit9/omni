package com.omni.platform.shared.events;

import com.omni.platform.shared.enums.StorageProvider;

public record FileUploadedEvent(
        String fileId,
        String bucket,
        String contentType,
        StorageProvider provider
) {}