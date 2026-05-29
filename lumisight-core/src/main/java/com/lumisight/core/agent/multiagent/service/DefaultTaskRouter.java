package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.agent.multiagent.model.SubAgentTask;
import com.lumisight.core.agent.multiagent.port.SubAgent;
import com.lumisight.core.agent.multiagent.port.TaskRouter;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DefaultTaskRouter implements TaskRouter {

    private final List<SubAgent> subAgents;

    public DefaultTaskRouter(List<SubAgent> subAgents) {
        this.subAgents = subAgents;
    }

    @Override
    public SubAgent route(SubAgentTask task) {
        return subAgents.stream()
                .filter(agent -> agent.supports(task))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("没有可处理任务的子代理: " + task.capability()));
    }
}
