package com.lumisight.common.exec;

public record CommandExecutionPolicy(
        boolean networkEnabled,
        long timeoutMs,
        int maxOutputBytes
) {
    public CommandExecutionPolicy {
        timeoutMs = Math.max(1000L, timeoutMs);
        maxOutputBytes = Math.max(1024, maxOutputBytes);
    }
}
