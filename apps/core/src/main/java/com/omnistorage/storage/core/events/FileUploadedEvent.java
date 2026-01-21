package com.omnistorage.storage.core.events;

import com.omnistorage.storage.core.enums.StorageProvider;

public record FileUploadedEvent(
        String fileId,
        String bucket,
        String contentType,
        StorageProvider provider
) {}