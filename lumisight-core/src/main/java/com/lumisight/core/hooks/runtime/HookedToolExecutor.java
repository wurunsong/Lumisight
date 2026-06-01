package com.lumisight.core.hooks.runtime;

import com.lumisight.core.model.AgentToolExecutionResult;
import com.lumisight.core.model.ToolDecision;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.runtime.AgentToolExecutionService;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class HookedToolExecutor {

    private final AgentToolExecutionService agentToolExecutionService;

    public HookedToolExecutor(AgentToolExecutionService agentToolExecutionService) {
        this.agentToolExecutionService = agentToolExecutionService;
    }

    public AgentToolExecutionResult execute(
            ToolDecision decision,
            Set<AgentToolPermission> enabledPermissions,
            int limit,
            String sessionId,
            int round,
            String question
    ) {
        AgentToolHookContextHolder.set(new ToolHookContext(sessionId, round, question));
        try {
            return agentToolExecutionService.execute(decision, enabledPermissions, limit);
        } finally {
            AgentToolHookContextHolder.clear();
        }
    }
}

