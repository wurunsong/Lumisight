package com.lumisight.core.service.task;

import java.util.List;

public record TaskView(
        TaskRecord task,
        boolean canStart,
        List<String> blockingDependencies
) {
}
