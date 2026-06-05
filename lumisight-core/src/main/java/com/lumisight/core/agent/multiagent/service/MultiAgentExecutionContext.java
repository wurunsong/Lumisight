package com.lumisight.core.agent.multiagent.service;

public final class MultiAgentExecutionContext {

    private static final ThreadLocal<Context> HOLDER = new ThreadLocal<>();

    private MultiAgentExecutionContext() {
    }

    public static Scope open(Context context) {
        HOLDER.set(context);
        return new Scope();
    }

    public static Context current() {
        return HOLDER.get();
    }

    public static final class Scope implements AutoCloseable {
        @Override
        public void close() {
            HOLDER.remove();
        }
    }

    public enum Role {
        LEAD_AGENT,
        SUB_AGENT,
        TEAM_AGENT
    }

    public record Context(
            Role role,
            String orchestrationId,
            String parentSessionId,
            String taskId,
            int depth,
            boolean allowSpawn,
            int maxRounds,
            long deadlineEpochMs,
            String teamId,
            String agentId
    ) {
        public boolean hasDeadline() {
            return deadlineEpochMs > 0L;
        }

        public boolean isDeadlineExceeded() {
            return hasDeadline() && System.currentTimeMillis() >= deadlineEpochMs;
        }
    }
}
