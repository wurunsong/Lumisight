package com.lumisight.core.service.task;

import java.util.List;

public record TaskBoardView(
        int total,
        int pending,
        int inProgress,
        int completed,
        int ready,
        int blocked,
        boolean allCompleted,
        List<TaskView> readyTasks,
        List<TaskView> inProgressTasks,
        List<TaskView> blockedTasks,
        List<TaskView> completedTasks
) {
}
