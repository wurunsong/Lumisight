package com.lumisight.core.model;

import java.util.Map;

public record AgentContextItem(
        String sourceType,
        String sourceId,
        String content,
        Map<String, Object> metadata
) {
}
