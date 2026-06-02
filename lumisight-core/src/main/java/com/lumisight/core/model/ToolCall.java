package com.lumisight.core.model;

import java.util.Map;

public record ToolCall(
        String toolName,
        Map<String, Object> args
) {
}
