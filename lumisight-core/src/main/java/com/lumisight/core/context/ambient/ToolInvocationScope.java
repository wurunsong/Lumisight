package com.lumisight.core.context.ambient;

import com.lumisight.common.concurrent.ThreadContextRegistry;

public final class ToolInvocationScope {

    private static final Support SUPPORT = new Support();

    private ToolInvocationScope() {
    }

    public static Scope open(String sessionId, Integer round, String question) {
        return new Scope(SUPPORT.open(new Context(sessionId, round == null ? 0 : round, question)));
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

    public record Context(String sessionId, int round, String question) {
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

    private static final class Support extends AbstractAmbientScope<Context> {
        private Support() {
            super("toolInvocationScope");
        }
    }
}
