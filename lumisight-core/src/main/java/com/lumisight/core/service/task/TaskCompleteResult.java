package com.lumisight.core.service.task;

import java.util.List;

public record TaskCompleteResult(
        String message,
        TaskRecord task,
        List<TaskView> unblocked
) {
}
