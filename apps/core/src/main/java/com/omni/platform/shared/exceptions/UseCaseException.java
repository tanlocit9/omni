package com.omni.platform.shared.exceptions;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class UseCaseException extends RuntimeException {
    private final String reason;

    private final HttpStatus status;

    private final String useCaseCode;
}
