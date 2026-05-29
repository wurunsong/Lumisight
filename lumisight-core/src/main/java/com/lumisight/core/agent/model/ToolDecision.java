package com.lumisight.core.agent.model;

import java.util.Map;

public record ToolDecision(
        String action,
        String toolName,
        Map<String, Object> args,
        String finalAnswer,
        String reason,
        String askUserQuestion
) {
}
