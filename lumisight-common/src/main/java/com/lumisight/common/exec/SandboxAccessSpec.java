package com.lumisight.common.exec;

import java.util.List;

public record SandboxAccessSpec(
        String subject,
        boolean networkEnabled,
        List<String> readablePaths,
        List<String> writablePaths,
        List<String> executablePaths,
        List<String> notes
) {
    public SandboxAccessSpec {
        subject = subject == null || subject.isBlank() ? "command" : subject.trim();
        readablePaths = readablePaths == null ? List.of() : List.copyOf(readablePaths);
        writablePaths = writablePaths == null ? List.of() : List.copyOf(writablePaths);
        executablePaths = executablePaths == null ? List.of() : List.copyOf(executablePaths);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public static SandboxAccessSpec readOnly(
            String subject,
            List<String> readablePaths,
            List<String> executablePaths,
            List<String> notes
    ) {
        return new SandboxAccessSpec(subject, false, readablePaths, List.of(), executablePaths, notes);
    }
}
