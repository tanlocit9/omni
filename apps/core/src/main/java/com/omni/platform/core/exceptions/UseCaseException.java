package com.omni.platform.core.exceptions;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.springframework.http.HttpStatus;

@Getter
@Setter
@AllArgsConstructor
public class UseCaseException extends RuntimeException {
    private final String reason;

    private final HttpStatus status;

    private final String useCaseCode;
}
