package com.lumisight.core.context;
import com.lumisight.core.support.AgentConversationManager;
import com.lumisight.core.support.context.AgentContextSession;

/**
 * @author wurunsong <wurunsong@kuaishou.com>
 * Created on 2026-06-09
 * agent运行时的核心上下文信息
 */
public record ExecutionContext(
        String sessionId,
        long runEpoch,
        AgentConversationManager.ConversationState resumeState,
        String effectiveQuestion,
        String resolvedRepoRoot,
        int limit,
        AgentContextSession contextSession,
        int startRound
) {
    public ExecutionContext withContextSession(AgentContextSession nextContextSession) {
        return new ExecutionContext(sessionId, runEpoch, resumeState, effectiveQuestion, resolvedRepoRoot, limit, nextContextSession, startRound);
    }

    public ExecutionContext withStartRound(int nextStartRound) {
        return new ExecutionContext(sessionId, runEpoch, resumeState, effectiveQuestion, resolvedRepoRoot, limit, contextSession, nextStartRound);
    }
}