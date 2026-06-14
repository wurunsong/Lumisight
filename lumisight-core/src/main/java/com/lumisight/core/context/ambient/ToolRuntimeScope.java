package com.lumisight.core.context.ambient;

/**
 * 工具运行作用域
 * todo 后面抽象一个AgentRuntimeScope接口，作为非agent运行作用域的基类
 */
public final class ToolRuntimeScope {

    private static final Support SUPPORT = new Support();

    private ToolRuntimeScope() {
    }

    public static Scope open(String repoRoot, Integer defaultLimit) {
        return open(repoRoot, defaultLimit, null);
    }

    public static Scope open(String repoRoot, Integer defaultLimit, String userId) {
        return new Scope(SUPPORT.openValue(new Context(repoRoot, defaultLimit, userId)));
    }

    public static Context required() {
        return SUPPORT.requiredValue("Tool runtime scope is missing");
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

    private static final class Support extends AbstractThreadLocalAgentContext<Context> {
    }
}
