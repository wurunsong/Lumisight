package com.lumisight.core.agent.multiagent.port;

import com.lumisight.core.agent.multiagent.model.OrchestrationContext;
import com.lumisight.core.agent.multiagent.model.SubAgentCapability;
import com.lumisight.core.agent.multiagent.model.SubAgentResult;
import com.lumisight.core.agent.multiagent.model.SubAgentTask;

import java.util.Set;

public interface SubAgent {

    String agentName();

    Set<SubAgentCapability> capabilities();

    default boolean supports(SubAgentTask task) {
        return capabilities().contains(task.capability());
    }

    SubAgentResult execute(SubAgentTask task, OrchestrationContext context);
}
