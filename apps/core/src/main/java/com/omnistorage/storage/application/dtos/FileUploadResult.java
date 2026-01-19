package com.omnistorage.storage.application.dtos;

public record FileUploadResult(String fileName, boolean success, String message) {}