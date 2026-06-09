package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.context.ambient.OrchestrationContext;
import com.lumisight.core.agent.multiagent.model.SubAgentCapability;
import com.lumisight.core.agent.multiagent.model.SubAgentResult;
import com.lumisight.core.agent.multiagent.model.TaskContextEnvelope;
import com.lumisight.core.agent.multiagent.model.SubAgentTask;
import com.lumisight.core.agent.multiagent.port.SubAgent;
import com.lumisight.core.model.AgentContextItem;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@Order(0)
public class ExecutingSubAgent implements SubAgent {

    private final SubAgentExecutionService subAgentExecutionService;

    public ExecutingSubAgent(SubAgentExecutionService subAgentExecutionService) {
        this.subAgentExecutionService = subAgentExecutionService;
    }

    @Override
    public String agentName() {
        return "executing-sub-agent";
    }

    @Override
    public Set<SubAgentCapability> capabilities() {
        return EnumSet.allOf(SubAgentCapability.class);
    }

    @Override
    public SubAgentResult execute(SubAgentTask task, OrchestrationContext context) {
        return subAgentExecutionService.execute(buildEnvelope(task), context.request().sessionId());
    }

    public SubAgentResult executeAsTeamAgent(SubAgentTask task, OrchestrationContext context, String teamId, String agentId) {
        return subAgentExecutionService.executeAsTeamAgent(buildEnvelope(task), context.request().sessionId(), teamId, agentId);
    }

    private TaskContextEnvelope buildEnvelope(SubAgentTask task) {
        List<AgentContextItem> evidence = task.inputs() == null ? List.of() : task.inputs().entrySet().stream()
                .map(entry -> new AgentContextItem(
                        "multi_agent_input",
                        task.taskId(),
                        String.valueOf(entry.getValue()),
                        Map.of("key", entry.getKey())
                ))
                .toList();
        return new TaskContextEnvelope(
                task.taskId(),
                task.title(),
                task.instruction(),
                task.capability(),
                String.valueOf(task.metadata() == null ? "" : task.metadata().getOrDefault("repoRoot", "")),
                evidence,
                Map.of("dependsOn", task.dependsOn(), "parallelGroup", task.parallelGroup()),
                task.expectedOutput(),
                task.budget() == null ? Map.of() : task.budget()
        );
    }
}
