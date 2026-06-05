package com.lumisight.api.memory.dto;

public record MemoryCreateRequest(
        String repoRoot,
        String userId,
        String name,
        String description,
        String type,
        String body
) {
}
