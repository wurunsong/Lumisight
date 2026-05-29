package com.lumisight.core.agent.multiagent.model;

import java.util.Map;

public record SubAgentTask(
        String taskId,
        String title,
        String instruction,
        SubAgentCapability capability,
        Map<String, Object> metadata
) {
}
