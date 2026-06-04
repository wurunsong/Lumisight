package com.lumisight.api.error;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsableException(
            AsyncRequestNotUsableException ex,
            HttpServletRequest request
    ) {
        log.warn("Async response already closed, path={}, message={}", request.getRequestURI(), ex.getMessage());
    }

    @ExceptionHandler(IOException.class)
    public void handleIoException(
            IOException ex,
            HttpServletRequest request
    ) throws IOException {
        if (isClientDisconnect(ex)) {
            log.warn("Client disconnected, path={}, message={}", request.getRequestURI(), ex.getMessage());
            return;
        }
        log.error("IO error, path={}, message={}", request.getRequestURI(), ex.getMessage(), ex);
        throw ex;
    }

    @ExceptionHandler(NonTransientAiException.class)
    public ResponseEntity<ApiErrorResponse> handleNonTransientAiException(
            NonTransientAiException ex,
            HttpServletRequest request
    ) {
        HttpStatus status = ex.getMessage() != null && ex.getMessage().contains("HTTP 401")
                ? HttpStatus.UNAUTHORIZED
                : HttpStatus.BAD_GATEWAY;
        log.error("AI upstream error, status={}, path={}, message={}", status.value(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                status.value(),
                "AI_UPSTREAM_ERROR",
                ex.getMessage() == null ? "AI upstream error" : ex.getMessage(),
                request.getRequestURI(),
                Instant.now().toString()
        ));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResourceFoundException(
            NoResourceFoundException ex,
            HttpServletRequest request
    ) {
        log.error("Resource not found, path={}, message={}", request.getRequestURI(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                "NOT_FOUND",
                "Resource not found",
                request.getRequestURI(),
                Instant.now().toString()
        ));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatusException(
            ResponseStatusException ex,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        log.error("Request error, status={}, path={}, message={}", status.value(), request.getRequestURI(), ex.getReason());
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                status.value(),
                "REQUEST_ERROR",
                ex.getReason() == null ? status.getReasonPhrase() : ex.getReason(),
                request.getRequestURI(),
                Instant.now().toString()
        ));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalStateException(
            IllegalStateException ex,
            HttpServletRequest request
    ) {
        log.error("Build error, path={}, message={}", request.getRequestURI(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "BUILD_ERROR",
                ex.getMessage(),
                request.getRequestURI(),
                Instant.now().toString()
        ));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiErrorResponse> handleRuntimeException(
            RuntimeException ex,
            HttpServletRequest request
    ) {
        log.error("Runtime error, path={}, message={}", request.getRequestURI(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "RUNTIME_ERROR",
                ex.getMessage() == null ? "Runtime error" : ex.getMessage(),
                request.getRequestURI(),
                Instant.now().toString()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleException(
            Exception ex,
            HttpServletRequest request
    ) {
        log.error("Unhandled error, path={}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "INTERNAL_ERROR",
                "Internal server error",
                request.getRequestURI(),
                Instant.now().toString()
        ));
    }

    private boolean isClientDisconnect(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String name = current.getClass().getName();
            String message = current.getMessage();
            if (name.contains("ClientAbortException")) {
                return true;
            }
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("broken pipe") || lower.contains("connection reset by peer")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
