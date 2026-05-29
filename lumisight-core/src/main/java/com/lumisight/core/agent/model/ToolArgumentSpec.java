package com.lumisight.core.agent.model;

public record ToolArgumentSpec(
        String name,
        String type,
        boolean required,
        String description
) {
}
