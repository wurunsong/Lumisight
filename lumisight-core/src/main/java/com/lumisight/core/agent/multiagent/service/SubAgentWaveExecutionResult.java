package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.agent.multiagent.model.SubAgentResult;

import java.util.List;

record SubAgentWaveExecutionResult(
        List<SubAgentResult> results,
        List<String> lifecycleEvents
) {
}
