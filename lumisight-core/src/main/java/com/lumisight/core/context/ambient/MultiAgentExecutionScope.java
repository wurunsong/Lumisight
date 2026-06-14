package com.lumisight.core.context.ambient;

import com.lumisight.common.concurrent.ThreadContextRegistry;

/**
 * 多agent执行作用域，承载编排期需要透传的运行约束
 */
public final class MultiAgentExecutionScope {

    private static final Support SUPPORT = new Support();

    private MultiAgentExecutionScope() {
    }

    public static Scope open(Context context) {
        return new Scope(SUPPORT.open(context));
    }

    public static Context current() {
        return SUPPORT.current();
    }

    public static void restore(Context context) {
        SUPPORT.restore(context);
    }

    public static void clear() {
        SUPPORT.clear();
    }

    public static ThreadContextRegistry.ContextCarrier carrier() {
        return SUPPORT.threadContextCarrier();
    }

    public static final class Scope implements AutoCloseable {
        private final AbstractThreadLocalAgentContext.Scope delegate;

        private Scope(AbstractThreadLocalAgentContext.Scope delegate) {
            this.delegate = delegate;
        }

        @Override
        public void close() {
            delegate.close();
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

    private static final class Support extends AbstractAmbientScope<Context> {
        private Support() {
            super("multiAgentExecutionScope");
        }
    }
}
