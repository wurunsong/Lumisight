package com.lumisight.core.model;

import java.util.Map;
import java.util.List;

public record ToolDecision(
        String action,
        String toolName,
        Map<String, Object> args,
        List<ToolCall> toolCalls,
        String finalAnswer,
        String reason,
        String askUserQuestion
) {
}
