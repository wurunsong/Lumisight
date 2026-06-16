package com.lumisight.core.agent;

import com.lumisight.core.context.ambient.MultiAgentExecutionScope;
import com.lumisight.core.support.AgentConversationManager;
import org.springframework.stereotype.Component;

/**
 * 统一的 agent 执行中断信号源。
 * loop 运行在线程池后，中断来源会同时来自线程取消、会话 epoch 抢占、用户手动停止和子任务 deadline；
 * 这里把这些条件收敛成一个 signal，避免每个阶段各自拼一套判断逻辑。
 */
@Component
class AgentExecutionInterruptService {

    private final AgentConversationManager conversationManager;

    AgentExecutionInterruptService(AgentConversationManager conversationManager) {
        this.conversationManager = conversationManager;
    }

    Signal currentSignal(String sessionId, long runEpoch) {
        if (Thread.currentThread().isInterrupted()) {
            return Signal.threadInterrupted();
        }
        if (!conversationManager.isActiveEpoch(sessionId, runEpoch)) {
            return Signal.superseded();
        }
        AgentConversationManager.ConversationState state = conversationManager.get(sessionId);
        if (state != null && state.interrupted()) {
            return Signal.userInterrupted();
        }
        MultiAgentExecutionScope.Context executionContext = MultiAgentExecutionScope.current();
        if (executionContext != null && executionContext.isDeadlineExceeded()) {
            return Signal.deadlineExceeded();
        }
        return Signal.none();
    }

    boolean shouldStop(String sessionId, long runEpoch) {
        return currentSignal(sessionId, runEpoch).stop();
    }

    boolean shouldContinue(String sessionId, long runEpoch) {
        return !shouldStop(sessionId, runEpoch);
    }

    enum Kind {
        NONE,
        THREAD_INTERRUPTED,
        SUPERSEDED,
        USER_INTERRUPTED,
        DEADLINE_EXCEEDED
    }

    record Signal(
            Kind kind,
            boolean stop,
            String status,
            String message
    ) {
        static Signal none() {
            return new Signal(Kind.NONE, false, "", "");
        }

        static Signal threadInterrupted() {
            return new Signal(Kind.THREAD_INTERRUPTED, true, "interrupted", "执行线程已被取消。");
        }

        static Signal superseded() {
            return new Signal(Kind.SUPERSEDED, true, "interrupted", "当前请求已被新的请求抢占并终止。");
        }

        static Signal userInterrupted() {
            return new Signal(Kind.USER_INTERRUPTED, true, "interrupted", "会话中断");
        }

        static Signal deadlineExceeded() {
            return new Signal(Kind.DEADLINE_EXCEEDED, true, "timeout", "达到本次子任务的安全时间上限");
        }

        boolean deadlineExceededSignal() {
            return kind == Kind.DEADLINE_EXCEEDED;
        }
    }
}
