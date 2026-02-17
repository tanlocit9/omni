package com.omni.platform.application.dtos;

public record FileUploadResult(
        String filename,
        String fileId,
        String contentType,
        boolean success,
        String message
) {
}