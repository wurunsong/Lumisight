package com.lumisight.core.model;

public record ToolArgumentSpec(
        String name,
        String type,
        boolean required,
        String description
) {
}
