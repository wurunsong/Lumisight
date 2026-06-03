package com.lumisight.common.exec;

import java.util.List;

public record SandboxExecutionPlan(
        String subject,
        CommandExecutionPolicy policy,
        List<String> notes
) {
    public SandboxExecutionPlan {
        subject = subject == null || subject.isBlank() ? "command" : subject.trim();
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
