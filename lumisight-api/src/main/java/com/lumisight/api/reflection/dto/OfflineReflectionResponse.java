package com.lumisight.api.reflection.dto;

import com.lumisight.api.memory.dto.MemoryEntryResponse;
import com.lumisight.api.memory.dto.MemoryHeaderResponse;

import java.util.List;

public record OfflineReflectionResponse(
        long generatedAt,
        String summary,
        String reflectionBody,
        boolean persisted,
        MemoryEntryResponse memoryEntry,
        OfflineReflectionTaskBoardResponse taskBoard,
        List<OfflineReflectionCronRunResponse> recentCronRuns,
        List<MemoryHeaderResponse> recentMemories,
        List<String> suggestedActions,
        List<OfflineReflectionTaskResponse> createdTasks
) {
}
