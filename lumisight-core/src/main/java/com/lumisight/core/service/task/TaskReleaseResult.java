package com.lumisight.core.service.task;

public record TaskReleaseResult(
        boolean released,
        String message,
        TaskView view
) {
}
