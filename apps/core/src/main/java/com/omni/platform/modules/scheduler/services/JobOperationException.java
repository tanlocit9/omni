package com.omni.platform.modules.scheduler.services;

import org.springframework.http.HttpStatus;

import lombok.Getter;

@Getter
public class JobOperationException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public JobOperationException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
