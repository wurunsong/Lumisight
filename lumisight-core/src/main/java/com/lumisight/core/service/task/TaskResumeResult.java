package com.lumisight.core.service.task;

import java.util.List;

public record TaskResumeResult(
        String message,
        boolean autoClaimed,
        List<TaskView> activeTasks,
        List<TaskView> readyTasks,
        boolean allCompleted
) {
}
