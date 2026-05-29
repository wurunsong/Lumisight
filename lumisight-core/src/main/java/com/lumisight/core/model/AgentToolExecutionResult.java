package com.lumisight.core.model;

import java.util.List;
import java.util.Map;

public record AgentToolExecutionResult(
        String toolName,
        String status,
        String message,
        List<AgentContextItem> items,
        Map<String, Object> metrics
) {
}
