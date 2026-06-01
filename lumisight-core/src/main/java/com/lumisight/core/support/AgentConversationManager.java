package com.lumisight.core.support;

import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.ToolDecision;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class AgentConversationManager {

    private static final Map<ConversationStatus, EnumSet<ConversationStatus>> ALLOWED_TRANSITIONS = Map.of(
            ConversationStatus.RUNNING, EnumSet.of(ConversationStatus.WAITING_USER, ConversationStatus.WAITING_GATE, ConversationStatus.INTERRUPTED, ConversationStatus.COMPLETED),
            ConversationStatus.WAITING_USER, EnumSet.of(ConversationStatus.RUNNING, ConversationStatus.INTERRUPTED, ConversationStatus.COMPLETED),
            ConversationStatus.WAITING_GATE, EnumSet.of(ConversationStatus.RUNNING, ConversationStatus.INTERRUPTED, ConversationStatus.COMPLETED),
            ConversationStatus.INTERRUPTED, EnumSet.of(ConversationStatus.RUNNING, ConversationStatus.COMPLETED),
            ConversationStatus.COMPLETED, EnumSet.noneOf(ConversationStatus.class)
    );

    private final Map<String, ConversationState> states = new ConcurrentHashMap<>();
    private final Map<String, List<String>> queuedQuestions = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> epochs = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<String>> inFlightTraceBySession = new ConcurrentHashMap<>();
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

    public boolean tryEnterSession(String sessionId, String traceId) {
        if (!StringUtils.hasText(sessionId)) {
            return true;
        }
        AtomicReference<String> holder = inFlightTraceBySession.computeIfAbsent(sessionId, k -> new AtomicReference<>());
        String mark = StringUtils.hasText(traceId) ? traceId : "UNKNOWN";
        return holder.compareAndSet(null, mark);
    }

    public void leaveSession(String sessionId, String traceId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        AtomicReference<String> holder = inFlightTraceBySession.get(sessionId);
        if (holder == null) {
            return;
        }
        if (StringUtils.hasText(traceId)) {
            holder.compareAndSet(traceId, null);
        } else {
            holder.set(null);
        }
        if (holder.get() == null) {
            inFlightTraceBySession.remove(sessionId, holder);
        }
    }

    public void saveWaiting(String sessionId, String baseQuestion, List<AgentContextItem> contexts, int nextRound) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        putState(sessionId, ConversationState.waitingUser(baseQuestion, new ArrayList<>(contexts), nextRound, null));
    }

    public void saveWaitingForGate(String sessionId, String baseQuestion, List<AgentContextItem> contexts, int nextRound, ToolDecision pendingDecision) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        putState(sessionId, ConversationState.waitingGate(baseQuestion, new ArrayList<>(contexts), nextRound, pendingDecision));
    }

    public void saveRunning(String sessionId, String baseQuestion, List<AgentContextItem> contexts, int nextRound) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        putState(sessionId, ConversationState.running(baseQuestion, new ArrayList<>(contexts), nextRound));
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
        queuedQuestions.remove(sessionId);
        epochs.remove(sessionId);
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

    public boolean hasQueuedQuestion(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return false;
        }
        List<String> queue = queuedQuestions.get(sessionId);
        return queue != null && !queue.isEmpty();
    }

    public void enqueueFollowQuestion(String sessionId, String question) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(question)) {
            return;
        }
        queuedQuestions.compute(sessionId, (k, queue) -> {
            List<String> result = queue == null ? new ArrayList<>() : new ArrayList<>(queue);
            result.add(question);
            return result;
        });
    }

    public void mergeCollectQuestion(String sessionId, String question) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(question)) {
            return;
        }
        queuedQuestions.compute(sessionId, (k, queue) -> {
            List<String> result = queue == null ? new ArrayList<>() : new ArrayList<>(queue);
            if (result.isEmpty()) {
                result.add(question);
            } else {
                String merged = result.get(0) + "\n用户追加问题: " + question;
                result.set(0, merged);
            }
            return result;
        });
    }

    public String pollQueuedQuestion(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        List<String> queue = queuedQuestions.get(sessionId);
        if (queue == null || queue.isEmpty()) {
            return null;
        }
        String next = queue.remove(0);
        if (queue.isEmpty()) {
            queuedQuestions.remove(sessionId);
        } else {
            queuedQuestions.put(sessionId, queue);
        }
        return next;
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
            int nextRound,
            boolean interrupted,
            ToolDecision pendingDecision,
            long lastUpdatedAt
    ) {
        static ConversationState waitingUser(String baseQuestion, List<AgentContextItem> contexts, int nextRound, ToolDecision pendingDecision) {
            return new ConversationState(ConversationStatus.WAITING_USER, baseQuestion, contexts, nextRound, false, pendingDecision, System.currentTimeMillis());
        }

        static ConversationState waitingGate(String baseQuestion, List<AgentContextItem> contexts, int nextRound, ToolDecision pendingDecision) {
            return new ConversationState(ConversationStatus.WAITING_GATE, baseQuestion, contexts, nextRound, false, pendingDecision, System.currentTimeMillis());
        }

        static ConversationState running(String baseQuestion, List<AgentContextItem> contexts, int nextRound) {
            return new ConversationState(ConversationStatus.RUNNING, baseQuestion, contexts, nextRound, false, null, System.currentTimeMillis());
        }

        ConversationState withInterrupted(boolean interrupted) {
            return new ConversationState(interrupted ? ConversationStatus.INTERRUPTED : status, baseQuestion, contexts, nextRound, interrupted, pendingDecision, lastUpdatedAt);
        }

        ConversationState withoutPendingDecision() {
            return new ConversationState(status, baseQuestion, contexts, nextRound, interrupted, null, lastUpdatedAt);
        }

        ConversationState withStatus(ConversationStatus nextStatus) {
            return new ConversationState(nextStatus, baseQuestion, contexts, nextRound, interrupted, pendingDecision, lastUpdatedAt);
        }

        ConversationState withLastUpdatedAt(long timestamp) {
            return new ConversationState(status, baseQuestion, contexts, nextRound, interrupted, pendingDecision, timestamp);
        }
    }

    public enum ConversationStatus {
        RUNNING,
        WAITING_USER,
        WAITING_GATE,
        INTERRUPTED,
        COMPLETED
    }
}
