package com.lumisight.api.reflection.dto;

public record OfflineReflectionTaskBoardResponse(
        int total,
        int pending,
        int inProgress,
        int completed,
        int ready,
        int blocked,
        boolean allCompleted
) {
}
