package com.lumisight.core.context;

public final class AgentToolInvocationContext {

    private static final ThreadLocal<Context> HOLDER = new ThreadLocal<>();

    private AgentToolInvocationContext() {
    }

    public static Scope open(String sessionId, Integer round, String question) {
        HOLDER.set(new Context(sessionId, round == null ? 0 : round, question));
        return new Scope();
    }

    public static Context current() {
        return HOLDER.get();
    }

    public static void restore(Context context) {
        if (context == null) {
            HOLDER.remove();
            return;
        }
        HOLDER.set(context);
    }

    public static void clear() {
        HOLDER.remove();
    }

    public record Context(String sessionId, int round, String question) {
    }

    public static final class Scope implements AutoCloseable {

        @Override
        public void close() {
            HOLDER.remove();
        }
    }
}
