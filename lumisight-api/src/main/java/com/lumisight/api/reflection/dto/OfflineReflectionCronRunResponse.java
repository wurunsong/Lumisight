package com.lumisight.api.reflection.dto;

public record OfflineReflectionCronRunResponse(
        String jobId,
        String jobName,
        long triggeredAt,
        String status,
        String finalMessage,
        String errorMessage
) {
}
