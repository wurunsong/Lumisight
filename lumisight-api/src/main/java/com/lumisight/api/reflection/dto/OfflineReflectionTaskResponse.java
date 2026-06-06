package com.lumisight.api.reflection.dto;

public record OfflineReflectionTaskResponse(
        String taskId,
        String subject,
        String status,
        long createdAt
) {
}
