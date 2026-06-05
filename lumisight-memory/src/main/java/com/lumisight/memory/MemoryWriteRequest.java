package com.lumisight.memory;

public record MemoryWriteRequest(
        String name,
        String description,
        MemoryType type,
        String body
) {
}
