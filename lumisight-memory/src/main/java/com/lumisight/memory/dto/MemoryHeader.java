package com.lumisight.memory;

public record MemoryHeader(
        String filename,
        String name,
        String description,
        MemoryType type,
        long mtimeMs
) {
}
