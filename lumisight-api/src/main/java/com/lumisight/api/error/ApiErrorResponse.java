package com.lumisight.api.error;

public record ApiErrorResponse(
        int status,
        String code,
        String message,
        String path,
        String timestamp
) {
}
