package com.omnistorage.storage.application.dtos;

public record FileDeleteResult(String fileName, boolean success, String message) {}