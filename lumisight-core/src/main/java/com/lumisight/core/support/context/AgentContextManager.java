package com.lumisight.core.support.context;

import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.model.AgentToolExecutionResult;
import com.lumisight.core.support.AgentConversationManager;
import com.lumisight.skills.runtime.SkillPlan;

import java.util.List;

public interface AgentContextManager {

    AgentContextSession restore(String sessionId, AgentConversationManager.ConversationState state);

    AgentContextSession append(String sessionId, AgentContextSession session, AgentContextItem item, AgentContextAppendOptions options);

    ToolAppendResult appendToolResult(String sessionId, AgentContextSession session, int round, AgentToolExecutionResult result);

    AgentContextProjection projectForDecision(String sessionId, AgentContextSession session, AgentRequest request, SkillPlan skillPlan);

    AgentContextProjection projectForFinal(String sessionId, AgentContextSession session, AgentRequest request, SkillPlan skillPlan);

    AgentContextProjection projectForVerification(String sessionId, AgentContextSession session, AgentRequest request);

    List<AgentContextItem> snapshotContexts(AgentContextSession session);

    record ToolAppendResult(AgentContextSession session, boolean producedContext) {
    }
}
