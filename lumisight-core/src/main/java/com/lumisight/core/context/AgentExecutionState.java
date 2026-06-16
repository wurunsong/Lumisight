package com.lumisight.core.context;

import com.lumisight.core.support.AgentConversationManager;
import com.lumisight.core.support.context.AgentContextSession;

/**
 * @author wurunsong <wurunsong@kuaishou.com>
 * Created on 2026-06-09
 * agent运行时的核心状态信息
 */
public record AgentExecutionState(
        String sessionId,
        long runEpoch,
        AgentConversationManager.ConversationState sessionState,
        String effectiveQuestion,
        String resolvedRepoRoot,
        int limit,
        AgentContextSession contextSession,
        int startRound
) {
    public AgentExecutionState withContextSession(AgentContextSession nextContextSession) {
        return new AgentExecutionState(sessionId, runEpoch, sessionState, effectiveQuestion, resolvedRepoRoot, limit, nextContextSession, startRound);
    }

    public AgentExecutionState withStartRound(int nextStartRound) {
        return new AgentExecutionState(sessionId, runEpoch, sessionState, effectiveQuestion, resolvedRepoRoot, limit, contextSession, nextStartRound);
    }
}
