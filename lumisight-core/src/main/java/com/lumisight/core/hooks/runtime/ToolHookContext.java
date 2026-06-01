package com.lumisight.core.hooks.runtime;

public record ToolHookContext(
        String sessionId,
        int round,
        String question
) {
    public static ToolHookContext empty() {
        return new ToolHookContext("", 0, "");
    }
}
