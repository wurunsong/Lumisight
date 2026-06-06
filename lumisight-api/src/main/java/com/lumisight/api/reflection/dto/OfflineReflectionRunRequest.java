package com.lumisight.api.reflection.dto;

public record OfflineReflectionRunRequest(
        String repoRoot,
        String userId,
        Boolean persistMemory,
        Boolean createFollowUpTasks,
        Integer maxRecentMemories,
        Integer maxRecentCronRuns,
        Integer maxFollowUpTasks
) {
}
