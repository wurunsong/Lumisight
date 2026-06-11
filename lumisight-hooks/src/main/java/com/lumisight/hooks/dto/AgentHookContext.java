package com.lumisight.hooks.dto;

import java.util.Map;

public record AgentHookContext(
        String sessionId,
        int round,
        String question,
        String toolName,
        Map<String, Object> metadata
) {
}
