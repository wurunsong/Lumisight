package com.lumisight.core.agent.support;

import com.lumisight.core.agent.model.AgentContextItem;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentConversationManager {

    private final Map<String, ConversationState> states = new ConcurrentHashMap<>();

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
        states.put(sessionId, ConversationState.waiting(baseQuestion, new ArrayList<>(contexts), nextRound));
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
    }

    public record ConversationState(
            String status,
            String baseQuestion,
            List<AgentContextItem> contexts,
            int nextRound,
            boolean interrupted
    ) {
        static ConversationState waiting(String baseQuestion, List<AgentContextItem> contexts, int nextRound) {
            return new ConversationState("WAITING_USER", baseQuestion, contexts, nextRound, false);
        }

        static ConversationState running(String baseQuestion, List<AgentContextItem> contexts, int nextRound) {
            return new ConversationState("RUNNING", baseQuestion, contexts, nextRound, false);
        }

        ConversationState withInterrupted(boolean interrupted) {
            return new ConversationState(status, baseQuestion, contexts, nextRound, interrupted);
        }
    }
}
