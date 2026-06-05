package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.agent.multiagent.model.MultiAgentExecutionState;
import com.lumisight.core.agent.multiagent.model.OrchestrationContext;
import com.lumisight.core.agent.multiagent.model.OrchestrationPlan;
import com.lumisight.core.agent.multiagent.model.SubAgentResult;
import com.lumisight.core.agent.multiagent.model.TopologyType;
import com.lumisight.core.agent.multiagent.port.OrchestratorAgent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class MultiAgentCoordinator {

    private final OrchestratorAgent orchestratorAgent;
    private final TeamAgentExecutionService teamAgentExecutionService;
    private final MultiAgentProperties properties;

    public MultiAgentCoordinator(
            OrchestratorAgent orchestratorAgent,
            TeamAgentExecutionService teamAgentExecutionService,
            MultiAgentProperties properties
    ) {
        this.orchestratorAgent = orchestratorAgent;
        this.teamAgentExecutionService = teamAgentExecutionService;
        this.properties = properties;
    }

    public CoordinationResult coordinate(AgentRequest request) {
        return coordinate(request, Map.of());
    }

    public CoordinationResult coordinate(AgentRequest request, Map<String, Object> runtimeAttributes) {
        Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        if (runtimeAttributes != null && !runtimeAttributes.isEmpty()) {
            attributes.putAll(runtimeAttributes);
        }
        attributes.put("source", "multi-agent-coordinator");
        OrchestrationContext context = new OrchestrationContext(
                UUID.randomUUID().toString(),
                request,
                Map.copyOf(attributes)
        );
        OrchestrationPlan plan = orchestratorAgent.createPlan(context);
        boolean useTeamAgent = shouldUseTeamAgent(plan);
        List<SubAgentResult> results;
        List<String> lifecycleEvents;
        MultiAgentExecutionState executionState;
        if (useTeamAgent) {
            TeamAgentExecutionService.ExecutionResult executionResult = teamAgentExecutionService.executePlan(plan, context);
            plan = executionResult.executionState().plan();
            results = executionResult.results();
            lifecycleEvents = executionResult.lifecycleEvents();
            executionState = executionResult.executionState();
        } else {
            results = orchestratorAgent.executePlan(plan, context);
            lifecycleEvents = List.of();
            executionState = new MultiAgentExecutionState(
                    plan.planId(),
                    "SUB_AGENT",
                    plan.topology(),
                    plan,
                    plan.tasks() == null ? Map.of() : plan.tasks().stream().collect(java.util.stream.Collectors.toMap(
                            task -> task.taskId(),
                            task -> results.stream().anyMatch(result -> result.taskId().equals(task.taskId()) && result.success())
                                    ? com.lumisight.core.agent.multiagent.model.TaskExecutionStatus.SUCCEEDED
                                    : com.lumisight.core.agent.multiagent.model.TaskExecutionStatus.FAILED,
                            (a, b) -> a,
                            java.util.LinkedHashMap::new
                    )),
                    results,
                    Map.of(),
                    1,
                    Map.of("lifecycleEvents", lifecycleEvents)
            );
        }
        String summary = orchestratorAgent.summarize(plan, results, context);
        return new CoordinationResult(context, plan, results, summary, useTeamAgent ? "TEAM_AGENT" : "SUB_AGENT", lifecycleEvents, executionState);
    }

    private boolean shouldUseTeamAgent(OrchestrationPlan plan) {
        if (!properties.isAllowTeamAgent() || plan == null || plan.tasks() == null) {
            return false;
        }
        return plan.tasks().size() > 1 || plan.topology() == TopologyType.HYBRID || plan.topology() == TopologyType.FAN_OUT_FAN_IN;
    }

    public record CoordinationResult(
            OrchestrationContext context,
            OrchestrationPlan plan,
            List<SubAgentResult> results,
            String summary,
            String executionMode,
            List<String> lifecycleEvents,
            MultiAgentExecutionState executionState
    ) {
    }
}
