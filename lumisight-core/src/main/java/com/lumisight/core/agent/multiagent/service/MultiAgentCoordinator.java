package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.agent.multiagent.model.OrchestrationContext;
import com.lumisight.core.agent.multiagent.model.OrchestrationPlan;
import com.lumisight.core.agent.multiagent.model.SubAgentResult;
import com.lumisight.core.agent.multiagent.port.OrchestratorAgent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class MultiAgentCoordinator {

    private final OrchestratorAgent orchestratorAgent;

    public MultiAgentCoordinator(OrchestratorAgent orchestratorAgent) {
        this.orchestratorAgent = orchestratorAgent;
    }

    public CoordinationResult coordinate(AgentRequest request) {
        OrchestrationContext context = new OrchestrationContext(
                UUID.randomUUID().toString(),
                request,
                Map.of("source", "multi-agent-coordinator")
        );
        OrchestrationPlan plan = orchestratorAgent.createPlan(context);
        List<SubAgentResult> results = orchestratorAgent.executePlan(plan, context);
        String summary = orchestratorAgent.summarize(plan, results, context);
        return new CoordinationResult(context, plan, results, summary);
    }

    public record CoordinationResult(
            OrchestrationContext context,
            OrchestrationPlan plan,
            List<SubAgentResult> results,
            String summary
    ) {
    }
}
