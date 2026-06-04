package com.lumisight.core.support;

import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.TodoTask;
import com.lumisight.core.model.ToolDecision;
import com.lumisight.core.support.context.AgentContextSession;

import java.util.List;
import java.util.Optional;

public interface AgentSessionContextStore {

    AgentConversationManager.ConversationState get(String sessionId);

    default void saveWaiting(String sessionId, String baseQuestion, List<AgentContextItem> contexts, int nextRound) {
        saveWaiting(sessionId, baseQuestion, contexts, null, nextRound);
    }

    void saveWaiting(String sessionId, String baseQuestion, List<AgentContextItem> contexts, AgentContextSession contextSession, int nextRound);

    default void saveWaitingForGate(String sessionId, String baseQuestion, List<AgentContextItem> contexts, int nextRound, ToolDecision pendingDecision) {
        saveWaitingForGate(sessionId, baseQuestion, contexts, null, nextRound, pendingDecision);
    }

    void saveWaitingForGate(String sessionId, String baseQuestion, List<AgentContextItem> contexts, AgentContextSession contextSession, int nextRound, ToolDecision pendingDecision);

    default void saveRunning(String sessionId, String baseQuestion, List<AgentContextItem> contexts, int nextRound) {
        saveRunning(sessionId, baseQuestion, contexts, null, nextRound);
    }

    void saveRunning(String sessionId, String baseQuestion, List<AgentContextItem> contexts, AgentContextSession contextSession, int nextRound);

    void interrupt(String sessionId);

    void clear(String sessionId);

    void updateTodos(String sessionId, List<TodoTask> todos, int round);

    Optional<AgentContextItem> todoReminderContext(String sessionId, int round);

    long nextEpoch(String sessionId);

    long currentEpoch(String sessionId);

    boolean isActiveEpoch(String sessionId, long epoch);

    int purgeExpiredSessions();
}
