package com.omni.platform.core.exceptions.file;

import com.omni.platform.core.exceptions.UseCaseException;
import org.springframework.http.HttpStatus;

public class FileDownloadFailedException extends UseCaseException {
    public FileDownloadFailedException(String reason) {
        super(reason, HttpStatus.NOT_FOUND, "FILE_DOWNLOAD_FAILED");
    }
}
