package com.omni.platform.shared.exceptions;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.omni.platform.modules.scheduler.services.JobOperationException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(JobOperationException.class)
    public ProblemDetail handleJobOperationException(JobOperationException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        pd.setTitle("Job operation rejected");
        pd.setProperty("code", ex.getCode());
        return pd;
    }

    @ExceptionHandler(UseCaseException.class)
    public ProblemDetail handleBusinessException(UseCaseException ex) {
        String errorId = UUID.randomUUID().toString();
        log.error("Use case Violation [ID: {}]: {}", errorId, ex.getMessage(), ex);

        // Automatically uses the status and message defined in the specific exception
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        pd.setTitle("Use case Violation");
        pd.setProperty("requestId", UUID.randomUUID().toString());
        pd.setProperty("errorCode", ex.getUseCaseCode());
        pd.setProperty("timestamp", Instant.now());

        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleAll(Exception ex) {
        String errorId = UUID.randomUUID().toString();
        log.error("Unhandled Exception [ID: {}]: {}", errorId, ex.getMessage(), ex);

        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setProperty("error_id", errorId); // Client can give this ID to support
        return pd;
    }
}
