package com.lumisight.core.model;

import java.util.List;

public record AgentResponse(
        String answer,
        List<AgentContextItem> contexts
) {
}
