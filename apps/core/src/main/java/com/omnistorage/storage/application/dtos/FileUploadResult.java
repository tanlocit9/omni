package com.omnistorage.storage.application.dtos;

public record FileUploadResult(
        String filename,
        String fileId,
        String contentType,
        boolean success,
        String message
) {
}