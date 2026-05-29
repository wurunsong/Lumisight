package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.agent.multiagent.model.OrchestrationContext;
import com.lumisight.core.agent.multiagent.model.SubAgentCapability;
import com.lumisight.core.agent.multiagent.model.SubAgentResult;
import com.lumisight.core.agent.multiagent.model.SubAgentTask;
import com.lumisight.core.agent.multiagent.port.SubAgent;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Component
public class NoopSubAgent implements SubAgent {

    @Override
    public String agentName() {
        return "noop-sub-agent";
    }

    @Override
    public Set<SubAgentCapability> capabilities() {
        return EnumSet.allOf(SubAgentCapability.class);
    }

    @Override
    public SubAgentResult execute(SubAgentTask task, OrchestrationContext context) {
        return new SubAgentResult(
                task.taskId(),
                agentName(),
                true,
                "占位执行完成，后续可替换为真实子代理实现",
                Map.of(
                        "taskTitle", task.title(),
                        "capability", task.capability().name()
                )
        );
    }
}
