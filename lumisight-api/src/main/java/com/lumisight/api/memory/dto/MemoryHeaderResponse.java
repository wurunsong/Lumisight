package com.lumisight.api.memory.dto;

import com.lumisight.memory.MemoryHeader;

public record MemoryHeaderResponse(
        String filename,
        String name,
        String description,
        String type,
        long mtimeMs
) {
    public static MemoryHeaderResponse from(MemoryHeader header) {
        return new MemoryHeaderResponse(
                header.filename(),
                header.name(),
                header.description(),
                header.type().wireValue(),
                header.mtimeMs()
        );
    }
}
