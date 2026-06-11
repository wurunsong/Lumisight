package com.lumisight.api.memory.dto;

import com.lumisight.memory.dto.MemoryEntry;

public record MemoryEntryResponse(
        String filename,
        String name,
        String description,
        String type,
        String body,
        long mtimeMs
) {
    public static MemoryEntryResponse from(MemoryEntry entry) {
        return new MemoryEntryResponse(
                entry.filename(),
                entry.name(),
                entry.description(),
                entry.type().wireValue(),
                entry.body(),
                entry.mtimeMs()
        );
    }
}
