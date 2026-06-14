package com.lumisight.core.context.ambient;

import com.lumisight.common.concurrent.ThreadContextRegistry;

/**
 * 工具运行作用域
 */
public final class ToolRuntimeScope {

    private static final Support SUPPORT = new Support();

    private ToolRuntimeScope() {
    }

    public static Scope open(String repoRoot, Integer defaultLimit) {
        return open(repoRoot, defaultLimit, null);
    }

    public static Scope open(String repoRoot, Integer defaultLimit, String userId) {
        return new Scope(SUPPORT.open(new Context(repoRoot, defaultLimit, userId)));
    }

    public static Context required() {
        return SUPPORT.required("Tool runtime scope is missing");
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

    public record Context(String repoRoot, Integer defaultLimit, String userId) {
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
            super("toolRuntimeScope");
        }
    }
}
