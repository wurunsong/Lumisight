package com.lumisight.core.agent;

import com.lumisight.core.context.AgentExecutionState;
import com.lumisight.core.context.ambient.MultiAgentExecutionScope;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.memory.dto.RelevantMemoryBundle;
import com.lumisight.skills.dto.SkillPlan;

import java.util.Set;

/**
 * application 层完成所有策略判决后，交给 kernel 的纯 loop 输入。
 */
record PreparedLoopExecution(
        AgentRequestContext requestContext,
        AgentExecutionState executionState,
        SkillPlan skillPlan,
        RelevantMemoryBundle relevantMemoryBundle,
        Set<AgentToolPermission> enabledPermissions,
        MultiAgentExecutionScope.Context executionScope
) {
}
