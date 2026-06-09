package com.lumisight.core.agent.multiagent.port;

import com.lumisight.core.context.ambient.OrchestrationContext;
import com.lumisight.core.agent.multiagent.model.OrchestrationPlan;
import com.lumisight.core.agent.multiagent.model.SubAgentResult;

import java.util.List;

public interface OrchestratorAgent {

    OrchestrationPlan createPlan(OrchestrationContext context);

    List<SubAgentResult> executePlan(OrchestrationPlan plan, OrchestrationContext context);

    String summarize(OrchestrationPlan plan, List<SubAgentResult> results, OrchestrationContext context);
}
