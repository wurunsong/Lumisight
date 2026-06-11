package com.lumisight.memory.enums;

import java.util.Arrays;
import java.util.Locale;

public enum MemoryType {
    USER,
    FEEDBACK,
    PROJECT,
    REFERENCE;

    public static MemoryType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("memory type is required");
        }
        return Arrays.stream(values())
                .filter(type -> type.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unsupported memory type: " + value + ", only user/feedback/project/reference are allowed"
                ));
    }

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
