package com.lumisight.core.agent.multiagent.model;

import java.util.Map;

public record SubAgentTask(
        String taskId,
        String title,
        String instruction,
        SubAgentCapability capability,
        Map<String, Object> inputs,
        String expectedOutput,
        java.util.List<String> dependsOn,
        String parallelGroup,
        Map<String, Object> budget,
        int priority,
        Map<String, Object> metadata
) {
}
