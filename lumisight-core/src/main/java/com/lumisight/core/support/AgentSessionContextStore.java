package com.lumisight.core.support;

import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.TodoTask;
import com.lumisight.core.model.ToolDecision;

import java.util.List;
import java.util.Optional;

public interface AgentSessionContextStore {

    AgentConversationManager.ConversationState get(String sessionId);

    void saveWaiting(String sessionId, String baseQuestion, List<AgentContextItem> contexts, int nextRound);

    void saveWaitingForGate(String sessionId, String baseQuestion, List<AgentContextItem> contexts, int nextRound, ToolDecision pendingDecision);

    void saveRunning(String sessionId, String baseQuestion, List<AgentContextItem> contexts, int nextRound);

    void interrupt(String sessionId);

    void clear(String sessionId);

    void updateTodos(String sessionId, List<TodoTask> todos, int round);

    Optional<AgentContextItem> todoReminderContext(String sessionId, int round);

    long nextEpoch(String sessionId);

    long currentEpoch(String sessionId);

    boolean isActiveEpoch(String sessionId, long epoch);

    int purgeExpiredSessions();
}
