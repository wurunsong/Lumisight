package com.lumisight.core.context.ambient;

/**
 * 多agent执行作用域，承载编排期需要透传的运行约束
 * todo 后面抽象一个AgentRuntimeScope接口，作为非agent运行作用域的基类
 */
public final class MultiAgentExecutionScope {

    private static final ThreadLocal<Context> HOLDER = new ThreadLocal<>();

    private MultiAgentExecutionScope() {
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
