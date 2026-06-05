package com.lumisight.core.service.task;

import java.util.List;

public record TaskClaimResult(
        boolean claimed,
        String message,
        TaskView view
) {

    public List<String> blockingDependencies() {
        return view == null ? List.of() : view.blockingDependencies();
    }
}
