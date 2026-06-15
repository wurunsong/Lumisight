package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.context.ambient.OrchestrationContext;

import java.util.List;

record SubAgentWavePlan(
        OrchestrationContext context,
        String coordinationId,
        int waveNumber,
        int parallelism,
        List<SubAgentWaveWorkItem> workItems
) {
}
