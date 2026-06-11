package com.lumisight.core.support;

import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.ToolDecision;
import com.lumisight.core.model.TodoTask;
import com.lumisight.core.support.context.AgentContextSession;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AgentConversationManager implements AgentSessionContextStore {

    private static final Map<ConversationStatus, EnumSet<ConversationStatus>> ALLOWED_TRANSITIONS = Map.of(
            ConversationStatus.RUNNING, EnumSet.of(ConversationStatus.WAITING_USER, ConversationStatus.WAITING_GATE, ConversationStatus.INTERRUPTED, ConversationStatus.COMPLETED),
            ConversationStatus.WAITING_USER, EnumSet.of(ConversationStatus.RUNNING, ConversationStatus.INTERRUPTED, ConversationStatus.COMPLETED),
            ConversationStatus.WAITING_GATE, EnumSet.of(ConversationStatus.RUNNING, ConversationStatus.INTERRUPTED, ConversationStatus.COMPLETED),
            ConversationStatus.INTERRUPTED, EnumSet.of(ConversationStatus.RUNNING, ConversationStatus.COMPLETED),
            ConversationStatus.COMPLETED, EnumSet.noneOf(ConversationStatus.class)
    );

    private final Map<String, ConversationState> states = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> epochs = new ConcurrentHashMap<>();
    private final Map<String, TodoState> todoStates = new ConcurrentHashMap<>();
    private final AgentConversationProperties conversationProperties;

    public AgentConversationManager(AgentConversationProperties conversationProperties) {
        this.conversationProperties = conversationProperties;
    }

    public ConversationState get(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        ConversationState state = states.get(sessionId);
        if (state == null) {
            return null;
        }
        if (isExpired(state, System.currentTimeMillis())) {
            clear(sessionId);
            return null;
        }
        return state;
    }

    public void saveWaiting(String sessionId, String baseQuestion, List<AgentContextItem> contexts, AgentContextSession contextSession, int nextRound) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        putState(sessionId, ConversationState.waitingUser(baseQuestion, new ArrayList<>(contexts), contextSession, nextRound, null));
    }

    public void saveWaitingForGate(String sessionId, String baseQuestion, List<AgentContextItem> contexts, AgentContextSession contextSession, int nextRound, ToolDecision pendingDecision) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        putState(sessionId, ConversationState.waitingGate(baseQuestion, new ArrayList<>(contexts), contextSession, nextRound, pendingDecision));
    }

    public void saveRunning(String sessionId, String baseQuestion, List<AgentContextItem> contexts, AgentContextSession contextSession, int nextRound) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        putState(sessionId, ConversationState.running(baseQuestion, new ArrayList<>(contexts), contextSession, nextRound));
    }

    public void interrupt(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        ConversationState old = states.get(sessionId);
        if (old == null) {
            return;
        }
        putState(sessionId, old.withInterrupted(true));
    }

    public void clear(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        ConversationState old = states.get(sessionId);
        if (old != null) {
            putState(sessionId, old.withStatus(ConversationStatus.COMPLETED).withInterrupted(false).withoutPendingDecision());
        }
        states.remove(sessionId);
        epochs.remove(sessionId);
        todoStates.remove(sessionId);
    }

    public void updateTodos(String sessionId, List<TodoTask> todos, int round) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        List<TodoTask> normalized = todos == null ? List.of() : todos.stream().map(todo -> new TodoTask(todo.content(), todo.status())).toList();
        todoStates.put(sessionId, new TodoState(new ArrayList<>(normalized), round, round, System.currentTimeMillis()));
    }

    /**
     * todo 这里有问题啊，应该把实际的提醒项和已完成项都列出来
     * @param sessionId
     * @param round
     * @return
     */
    public Optional<AgentContextItem> todoReminderContext(String sessionId, int round) {
        if (!StringUtils.hasText(sessionId)) {
            return Optional.empty();
        }
        TodoState state = todoStates.compute(sessionId, (key, old) -> {
            if (old == null) {
                return new TodoState(new ArrayList<>(), 0, 0, System.currentTimeMillis());
            }
            return old;
        });
        if (state == null) {
            return Optional.empty();
        }
        // 距离上一次 todo活动或 reminder提醒还不够久，就先别再提醒。
        int lastActivityRound = Math.max(state.lastTodoRound, state.lastReminderRound);
        if (round - lastActivityRound < 3) {
            return Optional.empty();
        }
        TodoState updated = new TodoState(state.todos, state.lastTodoRound, round, System.currentTimeMillis());
        todoStates.put(sessionId, updated);
        return Optional.of(new AgentContextItem(
                "todo_reminder",
                "todo_write",
                "<reminder>Update your todos.</reminder>",
                Map.of(
                        "sessionId", sessionId,
                        "round", round,
                        "todoCount", updated.todos.size(),
                        "lastTodoRound", updated.lastTodoRound,
                        "lastReminderRound", updated.lastReminderRound
                )
        ));
    }

    private void putState(String sessionId, ConversationState next) {
        ConversationState old = states.get(sessionId);
        if (!isTransitionAllowed(old == null ? null : old.status, next.status)) {
            throw new IllegalStateException("Illegal conversation state transition: "
                    + (old == null ? "null" : old.status.name()) + " -> " + next.status.name()
                    + ", sessionId=" + sessionId);
        }
        states.put(sessionId, next.withLastUpdatedAt(System.currentTimeMillis()));
    }

    private boolean isTransitionAllowed(ConversationStatus from, ConversationStatus to) {
        if (from == null) {
            return to == ConversationStatus.RUNNING || to == ConversationStatus.WAITING_USER || to == ConversationStatus.WAITING_GATE;
        }
        if (from == to) {
            return true;
        }
        return ALLOWED_TRANSITIONS.getOrDefault(from, EnumSet.noneOf(ConversationStatus.class)).contains(to);
    }

    public long nextEpoch(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return 0L;
        }
        return epochs.computeIfAbsent(sessionId, k -> new AtomicLong(0)).incrementAndGet();
    }

    public long currentEpoch(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return 0L;
        }
        AtomicLong epoch = epochs.get(sessionId);
        return epoch == null ? 0L : epoch.get();
    }

    public boolean isActiveEpoch(String sessionId, long epoch) {
        return currentEpoch(sessionId) == epoch;
    }

    public int purgeExpiredSessions() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (String sessionId : states.keySet()) {
            ConversationState state = states.get(sessionId);
            if (state != null && isExpired(state, now)) {
                clear(sessionId);
                removed++;
            }
        }
        return removed;
    }

    private boolean isExpired(ConversationState state, long now) {
        if (state.status != ConversationStatus.WAITING_USER && state.status != ConversationStatus.WAITING_GATE) {
            return false;
        }
        long ttlSeconds = state.status == ConversationStatus.WAITING_GATE
                ? conversationProperties.getWaitingGateTtlSeconds()
                : conversationProperties.getWaitingUserTtlSeconds();
        if (ttlSeconds <= 0) {
            return false;
        }
        return now - state.lastUpdatedAt > ttlSeconds * 1000L;
    }

    public record ConversationState(
            ConversationStatus status,
            String baseQuestion,
            List<AgentContextItem> contexts,
            AgentContextSession contextSession,
            int nextRound,
            boolean interrupted,
            ToolDecision pendingDecision,
            long lastUpdatedAt
    ) {
        static ConversationState waitingUser(String baseQuestion, List<AgentContextItem> contexts, AgentContextSession contextSession, int nextRound, ToolDecision pendingDecision) {
            return new ConversationState(ConversationStatus.WAITING_USER, baseQuestion, contexts, contextSession, nextRound, false, pendingDecision, System.currentTimeMillis());
        }

        static ConversationState waitingGate(String baseQuestion, List<AgentContextItem> contexts, AgentContextSession contextSession, int nextRound, ToolDecision pendingDecision) {
            return new ConversationState(ConversationStatus.WAITING_GATE, baseQuestion, contexts, contextSession, nextRound, false, pendingDecision, System.currentTimeMillis());
        }

        static ConversationState running(String baseQuestion, List<AgentContextItem> contexts, AgentContextSession contextSession, int nextRound) {
            return new ConversationState(ConversationStatus.RUNNING, baseQuestion, contexts, contextSession, nextRound, false, null, System.currentTimeMillis());
        }

        ConversationState withInterrupted(boolean interrupted) {
            return new ConversationState(interrupted ? ConversationStatus.INTERRUPTED : status, baseQuestion, contexts, contextSession, nextRound, interrupted, pendingDecision, lastUpdatedAt);
        }

        ConversationState withoutPendingDecision() {
            return new ConversationState(status, baseQuestion, contexts, contextSession, nextRound, interrupted, null, lastUpdatedAt);
        }

        ConversationState withStatus(ConversationStatus nextStatus) {
            return new ConversationState(nextStatus, baseQuestion, contexts, contextSession, nextRound, interrupted, pendingDecision, lastUpdatedAt);
        }

        ConversationState withLastUpdatedAt(long timestamp) {
            return new ConversationState(status, baseQuestion, contexts, contextSession, nextRound, interrupted, pendingDecision, timestamp);
        }
    }

    public enum ConversationStatus {
        RUNNING,
        WAITING_USER,
        WAITING_GATE,
        INTERRUPTED,
        COMPLETED
    }

    private record TodoState(List<TodoTask> todos, int lastTodoRound, int lastReminderRound, long lastUpdatedAt) {
    }
}
