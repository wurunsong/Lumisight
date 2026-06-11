package com.lumisight.memory.dto;

import com.lumisight.memory.enums.MemoryType;

public record MemoryWriteRequest(
        String name,
        String description,
        MemoryType type,
        String body
) {
}
