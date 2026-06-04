package com.lumisight.core.context;

public final class AgentToolInvocationContext {

    private static final Support SUPPORT = new Support();

    private AgentToolInvocationContext() {
    }

    public static Scope open(String sessionId, Integer round, String question) {
        return new Scope(SUPPORT.openValue(new Context(sessionId, round == null ? 0 : round, question)));
    }

    public static Context current() {
        return SUPPORT.currentValue();
    }

    public static void restore(Context context) {
        SUPPORT.restoreValue(context);
    }

    public static void clear() {
        SUPPORT.clearValue();
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

    private static final class Support extends AbstractThreadLocalAgentContext<Context> {
    }
}
