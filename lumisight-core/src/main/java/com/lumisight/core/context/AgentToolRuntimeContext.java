package com.lumisight.core.context;

public final class AgentToolRuntimeContext {

    private static final ThreadLocal<Context> HOLDER = new ThreadLocal<>();

    private AgentToolRuntimeContext() {
    }

    public static Scope open(String repoRoot, Integer defaultLimit) {
        HOLDER.set(new Context(repoRoot, defaultLimit));
        return new Scope();
    }

    public static Context required() {
        Context context = HOLDER.get();
        if (context == null) {
            throw new IllegalStateException("Agent tool runtime context is missing");
        }
        return context;
    }

    public static Context current() {
        return HOLDER.get();
    }

    public record Context(String repoRoot, Integer defaultLimit) {
    }

    public static final class Scope implements AutoCloseable {

        @Override
        public void close() {
            HOLDER.remove();
        }
    }
}
