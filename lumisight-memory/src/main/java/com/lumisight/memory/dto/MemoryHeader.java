package com.lumisight.memory.dto;

import com.lumisight.memory.enums.MemoryType;

public record MemoryHeader(
        String filename,
        String name,
        String description,
        MemoryType type,
        long mtimeMs
) {
}
