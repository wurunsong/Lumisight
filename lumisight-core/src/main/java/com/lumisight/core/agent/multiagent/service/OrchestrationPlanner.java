package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.agent.multiagent.model.OrchestrationPlan;
import com.lumisight.core.context.ambient.OrchestrationContext;

public interface OrchestrationPlanner {

    OrchestrationPlan createPlan(OrchestrationContext context);
}
