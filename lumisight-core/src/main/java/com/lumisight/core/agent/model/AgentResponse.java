package com.lumisight.core.agent.model;

import java.util.List;

public record AgentResponse(
        String answer,
        List<AgentContextItem> contexts
) {
}
