package com.omni.platform.shared.exceptions.file;

import org.springframework.http.HttpStatus;

import com.omni.platform.shared.exceptions.UseCaseException;

public class FileDownloadFailedException extends UseCaseException {
    public FileDownloadFailedException(String reason) {
        super(reason, HttpStatus.NOT_FOUND, "FILE_DOWNLOAD_FAILED");
    }
}
