package com.lumisight.common.exec;

public record CommandExecutionResult(
        boolean success,
        int exitCode,
        boolean timedOut,
        String output
) {
}
