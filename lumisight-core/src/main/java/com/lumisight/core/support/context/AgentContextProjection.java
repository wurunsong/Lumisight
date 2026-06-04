package com.lumisight.core.support.context;

import com.lumisight.core.model.AgentContextItem;

import java.util.List;

public record AgentContextProjection(
        AgentContextSession session,
        List<AgentContextItem> contexts,
        int estimatedTokens,
        boolean collapsed,
        boolean autoCompacted
) {
}
