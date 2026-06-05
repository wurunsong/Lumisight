package com.lumisight.api.memory.dto;

import java.util.List;

public record MemoryRelevantResponse(
        String entrypointContent,
        List<MemoryEntryResponse> selectedEntries,
        String remindersBlock
) {
}
