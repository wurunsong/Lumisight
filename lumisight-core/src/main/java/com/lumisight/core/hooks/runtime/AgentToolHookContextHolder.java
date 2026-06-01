package com.lumisight.core.hooks.runtime;

public final class AgentToolHookContextHolder {

    private static final ThreadLocal<ToolHookContext> HOLDER = new ThreadLocal<>();

    private AgentToolHookContextHolder() {
    }

    public static void set(ToolHookContext context) {
        HOLDER.set(context);
    }

    public static ToolHookContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
