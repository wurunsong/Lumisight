package com.lumisight.memory.dto;

import com.lumisight.memory.enums.MemoryType;

public record MemoryEntry(
        String filename,
        String name,
        String description,
        MemoryType type,
        String body,
        long mtimeMs
) {
}
