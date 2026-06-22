package com.omni.platform.modules.storages.dtos;

public record FileUploadResult(
        String filename,
        String fileId,
        String contentType,
        boolean success,
        String message
) {
}