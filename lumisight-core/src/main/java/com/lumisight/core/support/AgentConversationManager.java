package com.lumisight.core.support;

import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.ToolDecision;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AgentConversationManager {

    private final Map<String, ConversationState> states = new ConcurrentHashMap<>();
    private final Map<String, List<String>> queuedQuestions = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> epochs = new ConcurrentHashMap<>();

    public ConversationState get(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        return states.get(sessionId);
    }

    public void saveWaiting(String sessionId, String baseQuestion, List<AgentContextItem> contexts, int nextRound) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        states.put(sessionId, ConversationState.waiting(baseQuestion, new ArrayList<>(contexts), nextRound, null));
    }

    public void saveWaitingForGate(String sessionId, String baseQuestion, List<AgentContextItem> contexts, int nextRound, ToolDecision pendingDecision) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        states.put(sessionId, ConversationState.waiting(baseQuestion, new ArrayList<>(contexts), nextRound, pendingDecision));
    }

    public void saveRunning(String sessionId, String baseQuestion, List<AgentContextItem> contexts, int nextRound) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        states.put(sessionId, ConversationState.running(baseQuestion, new ArrayList<>(contexts), nextRound));
    }

    public void interrupt(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        ConversationState old = states.get(sessionId);
        if (old == null) {
            return;
        }
        states.put(sessionId, old.withInterrupted(true));
    }

    public void clear(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        states.remove(sessionId);
        queuedQuestions.remove(sessionId);
        epochs.remove(sessionId);
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

    public record ConversationState(
            String status,
            String baseQuestion,
            List<AgentContextItem> contexts,
            int nextRound,
            boolean interrupted,
            ToolDecision pendingDecision
    ) {
        static ConversationState waiting(String baseQuestion, List<AgentContextItem> contexts, int nextRound, ToolDecision pendingDecision) {
            return new ConversationState("WAITING_USER", baseQuestion, contexts, nextRound, false, pendingDecision);
        }

        static ConversationState running(String baseQuestion, List<AgentContextItem> contexts, int nextRound) {
            return new ConversationState("RUNNING", baseQuestion, contexts, nextRound, false, null);
        }

        ConversationState withInterrupted(boolean interrupted) {
            return new ConversationState(status, baseQuestion, contexts, nextRound, interrupted, pendingDecision);
        }
    }
}
