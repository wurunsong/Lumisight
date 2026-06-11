package com.lumisight.memory;

public record MemoryEntry(
        String filename,
        String name,
        String description,
        MemoryType type,
        String body,
        long mtimeMs
) {
}
