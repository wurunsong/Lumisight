package com.lumisight.core.hooks.runtime;

public record ToolHookContext(
        String sessionId,
        int round,
        String question
) {
}
